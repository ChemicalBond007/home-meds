import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { aiSettingsSchema, saveApiKey } from '../src/worker/ai-config';
import { runReminders } from '../src/worker/push';
import { runChat, runLookup, chatToolLimit } from '../src/shared/ai-agent';
import type { Http } from '../src/shared/ai-http';

const std64 = (bytes: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const unstd64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0));
async function deviceKeys() {
  const pair = await crypto.subtle.generateKey({ name: 'RSA-OAEP', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
    true, ['encrypt', 'decrypt']) as CryptoKeyPair;
  return { pair, spki: std64(await crypto.subtle.exportKey('spki', pair.publicKey) as ArrayBuffer) };
}
async function open(sealed: { wrapped_key: string; iv: string; ciphertext: string }, key: CryptoKey) {
  const raw = await crypto.subtle.decrypt({ name: 'RSA-OAEP' }, key, unstd64(sealed.wrapped_key));
  const aes = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']);
  return JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unstd64(sealed.iv) }, aes, unstd64(sealed.ciphertext))));
}
const call = (path: string, body: object, token = 'member') => app.request(`https://home.test/api${path}`, {
  method: 'POST', headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', 'X-HomeMeds-Client': 'android' },
  body: JSON.stringify(body),
}, env);
let keys: Awaited<ReturnType<typeof deviceKeys>>;
beforeEach(async () => {
  keys ??= await deviceKeys();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','另一家','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','测试家人','user','g'),('x','他群人','user','other')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('member'), 'u', Date.now() + 86400000),
    env.DB.prepare("INSERT INTO products(id,group_id,name,created_by,units_per_package) VALUES('med','g','正式测试药','u',10)"),
  ]);
  await saveApiKey(env, 'llm', 'phone-llm-key');
  await saveApiKey(env, 'tavily', 'phone-tavily-key');
});
afterEach(() => vi.restoreAllMocks());

describe('Android app AI sessions', () => {
  it('seals provider keys to the device key, counts quota once and logs the reported outcome', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const r = await call('/app/ai/session', { kind: 'lookup', public_key: keys.spki, query: '虚构药' });
    expect(r.status).toBe(200);
    const body = await r.json() as { session_id: string; settings: { model: string }; credentials: { alg: string; wrapped_key: string; iv: string; ciphertext: string } };
    expect(JSON.stringify(body)).not.toContain('phone-llm-key');
    expect(body.credentials.alg).toBe('RSA-OAEP-SHA1+A256GCM');
    expect(await open(body.credentials, keys.pair.privateKey)).toMatchObject({
      session_id: body.session_id, llm_key: 'phone-llm-key', tavily_key: 'phone-tavily-key', llm_base_url: 'https://llm.example.com/api',
    });
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(await env.DB.prepare("SELECT count FROM ai_usage WHERE kind='lookup'").first('count')).toBe(1);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM audit_logs').first('n')).toBe(0);
    expect((await call('/app/ai/finish', { session_id: body.session_id, success: true })).status).toBe(200);
    expect(await env.DB.prepare('SELECT query,success FROM ai_lookup_logs').first()).toEqual({ query: '虚构药', success: 1 });
    expect(await env.DB.prepare('SELECT action,outcome FROM audit_logs').first()).toEqual({ action: 'ai.lookup', outcome: 'success' });
    expect((await call('/app/ai/finish', { session_id: body.session_id, success: true })).status).toBe(409);
  });
  it('rejects invalid device keys and records quota exhaustion as a failed lookup', async () => {
    expect((await call('/app/ai/session', { kind: 'lookup', public_key: 'A'.repeat(300), query: '药' })).status).toBe(400);
    await env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('ai',?)").bind(JSON.stringify(aiSettingsSchema.parse({ lookup_daily_limit: 1 }))).run();
    expect((await call('/app/ai/session', { kind: 'lookup', public_key: keys.spki, query: '药' })).status).toBe(200);
    expect((await call('/app/ai/session', { kind: 'lookup', public_key: keys.spki, query: '药' })).status).toBe(429);
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.lookup' AND http_status=429").first('outcome')).toBe('failure');
  });
  it('runs chat database tools on the server with the read-before-draft rule, target lock and tool cap', async () => {
    expect((await call('/app/ai/session', { kind: 'chat', public_key: keys.spki, user_id: 'x' })).status).toBe(400);
    const s = await (await call('/app/ai/session', { kind: 'chat', public_key: keys.spki, user_id: 'u', intent: 'draft' })).json() as { session_id: string; system_prompt: string };
    expect(s.system_prompt).toContain('计划对象测试家人（ID u）');
    const tool = async (name: string, args: object) => (await call('/app/ai/tool', { session_id: s.session_id, name, args })).json() as Promise<Record<string, unknown>>;
    const draft = { medicine_name: '手填', amount: 1, unit: '片', frequency: 'daily', times: ['08:00'], product_id: 'med' };
    expect((await tool('add_plan_draft', draft)).error).toContain('请先通过药品查询工具');
    expect(await tool('find_medicines', { query: '正式测试药' })).toHaveProperty('result.medicines');
    expect(await tool('add_plan_draft', draft)).toMatchObject({ draft: { operation: 'create', draft: { user_id: 'u', medicine_name: '正式测试药' } } });
    expect((await tool('list_plans', { userId: 'x' })).error).toBe('只能查询当前计划对象');
    for (let i = 4; i < chatToolLimit; i++) await tool('list_family_members', {});
    expect((await call('/app/ai/tool', { session_id: s.session_id, name: 'list_family_members', args: {} })).status).toBe(409);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM medication_plans').first('n')).toBe(0);
    await call('/app/ai/finish', { session_id: s.session_id, success: false });
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.chat'").first('outcome')).toBe('failure');
  });
  it('logs abandoned app lookups as failures when the session expires', async () => {
    const s = await (await call('/app/ai/session', { kind: 'lookup', public_key: keys.spki, query: '未完成' })).json() as { session_id: string };
    await runReminders(env, new Date(Date.now() + 16 * 60000));
    expect(await env.DB.prepare('SELECT query,success FROM ai_lookup_logs').first()).toEqual({ query: '未完成', success: 0 });
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM app_ai_sessions').first('n')).toBe(0);
    expect(s.session_id).toBeTruthy();
  });
});
describe('shared agent loops on an injected HTTP client', () => {
  const settings = aiSettingsSchema.parse({ query_mode: 'prefetch' });
  it('performs lookup requests only through the provided client', async () => {
    const globalFetch = vi.spyOn(globalThis, 'fetch');
    const seen: string[] = [];
    const http: Http = async (input, init) => {
      const request = new Request(input, init); seen.push(`${request.url} ${request.headers.get('Authorization')}`);
      if (request.url.includes('tavily')) return Response.json({ results: [{ title: '资料', url: 'https://example.com/a', content: '虚构药' }] });
      return Response.json({ choices: [{ message: { role: 'assistant', content: JSON.stringify({ name: '虚构药', generic_name: null, manufacturer: null,
        specification: null, indications: null, precautions: null, base_unit: null, units_per_package: null, sources: [{ title: '资料', url: 'https://example.com/a' }] }) } }] });
    };
    const events: [string, unknown][] = [];
    await runLookup({ settings, key: 'hm-key:s:llm', searchKey: 'hm-key:s:tavily', query: '虚构药', http, emit: async (e, d) => { events.push([e, d]); } });
    expect(globalFetch).not.toHaveBeenCalled();
    expect(seen).toEqual(['https://api.tavily.com/search Bearer hm-key:s:tavily', 'https://llm.example.com/api/chat/completions Bearer hm-key:s:llm']);
    expect(events.at(-1)).toMatchObject(['result', { name: '虚构药', requires_confirmation: true }]);
  });
  it('stages drafts returned by a remote tool executor and reports remote tool errors', async () => {
    let n = 0;
    const sse = (delta: object) => new Response(`data: ${JSON.stringify({ choices: [{ delta }] })}\n\ndata: [DONE]\n\n`);
    const http: Http = async () => n++ === 0 ? sse({ tool_calls: [
      { index: 0, id: 'a', type: 'function', function: { name: 'add_plan_draft', arguments: '{}' } },
      { index: 1, id: 'b', type: 'function', function: { name: 'propose_plan_pause', arguments: '{}' } }] }) : sse({ content: '好的' });
    const events: string[] = [];
    const ok = await runChat({ settings, key: 'k', intent: 'chat', messages: [{ role: 'user', content: '记录' }], http,
      emit: async e => { events.push(e); },
      executeTool: async name => { if (name === 'propose_plan_pause') throw new Error('计划不存在或无权修改'); return { draft: { operation: 'create', draft: { x: 1 } } }; } });
    expect(ok).toBe(true);
    expect(events.filter(e => e !== 'delta')).toEqual(['draft', 'draft_error', 'done']);
  });
});
