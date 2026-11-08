import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { aiSettingsSchema, saveApiKey } from '../src/worker/ai-config';
import { runReminders } from '../src/worker/push';
import { runChat, runLookup, chatToolLimit } from '../src/shared/ai-agent';
import type { Http } from '../src/shared/ai-http';

const call = (path: string, body: object, token = 'member') => app.request(`https://home.test/api${path}`, {
  method: 'POST', headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', 'X-HomeMeds-Client': 'android' },
  body: JSON.stringify(body),
}, env);
beforeEach(async () => {
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
  const session = async (kind = 'lookup') => (await (await call('/app/ai/session',
    kind === 'lookup' ? { kind, query: '药' } : { kind, user_id: 'u' })).json() as { session_id: string }).session_id;
  const provider = (id: string, body: object = {}, path = '/chat/completions', service = 'llm') =>
    call('/app/ai/provider', { session_id: id, service, path, body });
  it('locks ownership, group, expiry and finish state before forwarding', async () => {
    const id = await session();
    const fetch = vi.spyOn(globalThis, 'fetch');
    for (const sql of ["user_id='x'", "user_id='u',group_id='other'", "group_id='g',expires_at=0",
      `expires_at=${Date.now() + 60000},finished_at=1`]) {
      await env.DB.prepare(`UPDATE app_ai_sessions SET ${sql} WHERE id=?`).bind(id).run();
      expect((await provider(id)).status).toBe(409);
    }
    expect(fetch).not.toHaveBeenCalled();
  });
  it('rejects non-whitelisted paths and disallows search on chat or builtin sessions', async () => {
    const id = await session('chat');
    const fetch = vi.spyOn(globalThis, 'fetch');
    for (const path of ['/models', '//evil.example.com', '/responses?x=1', '/messages/../models', '/search']) {
      expect((await provider(id, {}, path)).status).toBe(400);
    }
    expect((await provider(id, {}, '/search', 'tavily')).status).toBe(409);
    await env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('ai',?)")
      .bind(JSON.stringify(aiSettingsSchema.parse({ search_mode: 'builtin' }))).run();
    expect((await provider(await session(), {}, '/search', 'tavily')).status).toBe(403);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('atomically limits concurrent model and search calls', async () => {
    const id = await session();
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json({ ok: true }));
    const model = await Promise.all(Array.from({ length: 15 }, () => provider(id)));
    expect(model.filter(r => r.status === 200)).toHaveLength(12);
    expect(model.filter(r => r.status === 409)).toHaveLength(3);
    const search = await Promise.all(Array.from({ length: 7 }, () => provider(id, {}, '/search', 'tavily')));
    expect(search.filter(r => r.status === 200)).toHaveLength(5);
    expect(search.filter(r => r.status === 409)).toHaveLength(2);
    expect(fetch).toHaveBeenCalledTimes(17);
  });
  it('injects the saved search key only into the fixed Tavily destination', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, init) => {
      const request = new Request(url, init);
      expect(request.url).toBe('https://api.tavily.com/search');
      expect(request.redirect).toBe('manual');
      expect(request.headers.get('Authorization')).toBe('Bearer phone-tavily-key');
      expect(await request.json()).toEqual({ query: '药', max_results: 5, search_depth: 'basic', include_answer: false });
      return Response.json({ results: [] });
    });
    expect((await provider(await session(), { query: '药', api_key: 'attacker', max_results: 100 },
      '/search', 'tavily')).status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it('overrides model, caps tokens, strips chat search and injects keys only at the configured destination', async () => {
    const requests: Request[] = [];
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, init) => {
      requests.push(new Request(url, init)); return Response.json({ ok: true });
    });
    const id = await session('chat');
    for (const path of ['/chat/completions', '/responses', '/messages', '/anthropic/v1/messages']) {
      expect((await provider(id, { model: 'expensive', max_tokens: 99999, max_output_tokens: 99999,
        enable_search: true, search_options: {}, web_search_options: {},
        tools: [{ type: 'web_search' }, { type: 'function', function: { name: 'local' } }] }, path)).status).toBe(200);
    }
    for (const request of requests) {
      const body = await request.json() as Record<string, unknown>;
      expect(body).toMatchObject({ model: 'qwen3.5-27b', max_tokens: 2048, max_output_tokens: 2048,
        tools: [{ type: 'function', function: { name: 'local' } }], store: false });
      expect(body).not.toHaveProperty('enable_search');
      expect(body).not.toHaveProperty('search_options');
      expect(body).not.toHaveProperty('web_search_options');
      expect(request.redirect).toBe('manual');
      expect(request.url).toMatch(/^https:\/\/llm.example.com\/api\//);
      expect(request.headers.get(request.url.endsWith('/messages') ? 'x-api-key' : 'Authorization'))
        .toBe(request.url.endsWith('/messages') ? 'phone-llm-key' : 'Bearer phone-llm-key');
    }
  });
  it('returns only upstream error status and streams successful SSE without buffering or forwarding headers', async () => {
    const id = await session();
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('secret-provider-body', {
      status: 307, headers: { Location: 'https://evil.example.com', 'Set-Cookie': 'secret' },
    }));
    const error = await provider(id);
    expect(error.status).toBe(307); expect(await error.text()).toBe('');
    expect(error.headers.has('Location')).toBe(false); expect(error.headers.has('Set-Cookie')).toBe(false);
    fetch.mockResolvedValueOnce(new Response('provider-error-with-key', { status: 500 }));
    const failed = await provider(id);
    expect(failed.status).toBe(500); expect(await failed.text()).toBe('');
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
    fetch.mockResolvedValueOnce(new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } }));
    const response = await provider(id, { stream: true });
    expect(response.headers.get('Content-Type')).toBe('text/event-stream');
    const reader = response.body!.getReader(), text = 'data: {"choices":[]}\n\n';
    controller.enqueue(new TextEncoder().encode(text));
    expect(new TextDecoder().decode((await reader.read()).value)).toBe(text);
    controller.close(); expect((await reader.read()).done).toBe(true);
  });
  it('accepts a six-photo sized provider body but preserves both request size limits', async () => {
    const id = await session('chat');
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json({ ok: true }));
    expect((await provider(id, { messages: [{ content: 'A'.repeat(3300000) }] })).status).toBe(200);
    expect((await provider(id, { content: 'A'.repeat(4 * 1024 * 1024) })).status).toBe(413);
    expect((await call('/app/ai/session', { kind: 'lookup', query: 'A'.repeat(300000) })).status).toBe(413);
  });
  it('never returns provider keys, counts quota once and logs the reported outcome', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const r = await call('/app/ai/session', { kind: 'lookup', query: '虚构药' });
    expect(r.status).toBe(200);
    const body = await r.json() as { session_id: string; settings: { model: string } };
    expect(JSON.stringify(body)).not.toContain('phone-llm-key');
    expect(JSON.stringify(body)).not.toContain('phone-tavily-key');
    expect(body).not.toHaveProperty('credentials');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(await env.DB.prepare("SELECT count FROM ai_usage WHERE kind='lookup'").first('count')).toBe(1);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM audit_logs').first('n')).toBe(0);
    expect((await call('/app/ai/finish', { session_id: body.session_id, success: true })).status).toBe(200);
    expect(await env.DB.prepare('SELECT query,success FROM ai_lookup_logs').first()).toEqual({ query: '虚构药', success: 1 });
    expect(await env.DB.prepare('SELECT action,outcome FROM audit_logs').first()).toEqual({ action: 'ai.lookup', outcome: 'success' });
    expect((await call('/app/ai/finish', { session_id: body.session_id, success: true })).status).toBe(409);
  });
  it('rejects legacy device keys with 501 and records quota exhaustion', async () => {
    expect((await call('/app/ai/session', { kind: 'lookup', public_key: 'A'.repeat(300), query: '药' })).status).toBe(501);
    await env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('ai',?)").bind(JSON.stringify(aiSettingsSchema.parse({ lookup_daily_limit: 1 }))).run();
    expect((await call('/app/ai/session', { kind: 'lookup', query: '药' })).status).toBe(200);
    expect((await call('/app/ai/session', { kind: 'lookup', query: '药' })).status).toBe(429);
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.lookup' AND http_status=429").first('outcome')).toBe('failure');
  });
  it('runs chat database tools on the server with the read-before-draft rule, target lock and tool cap', async () => {
    expect((await call('/app/ai/session', { kind: 'chat', user_id: 'x' })).status).toBe(400);
    const s = await (await call('/app/ai/session', { kind: 'chat', user_id: 'u', intent: 'draft' })).json() as { session_id: string; system_prompt: string };
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
    const s = await (await call('/app/ai/session', { kind: 'lookup', query: '未完成' })).json() as { session_id: string };
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
