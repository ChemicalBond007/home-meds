import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { b64, hash, randomToken } from '../src/worker/crypto';
import { getApiKey, saveApiKey, aiSettingsSchema } from '../src/worker/ai-config';
import { parseModelJson, publicUrl, fetchPage } from '../src/worker/ai-http';
import { runReminders } from '../src/worker/push';
const currentDate = '2026-01-01';
async function call(path: string, body: object, admin = false) {
  return app.request(`https://home.test/api${path}`, {
    method: 'POST', headers: { Origin: 'https://home.test', Cookie: `hm_session=${admin ? 'admin-token' : 'user-token'}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, env);
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','本人','user','g'),('f','家人','user','g'),('a','管理','admin','g')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('user-token'), 'u', Date.now() + 86400000),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('admin-token'), 'a', Date.now() + 86400000),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,medicine_name,amount,unit,times,frequency,start_date,notify_family,created_by,updated_by)
      VALUES('p','g','u','药',1,'片','["08:00"]','daily','2020-01-01',1,'u','u')`),
  ]);
});
afterEach(() => vi.restoreAllMocks());
const medicine = { name: '测试药', generic_name: null, manufacturer: null, specification: null, indications: null, precautions: null, base_unit: null, units_per_package: null, sources: [{ title: '药盒资料', url: 'https://example.com/medicine' }] };
const llm = (content: string | null, calls?: { id: string; type: 'function'; function: { name: string; arguments: string } }[]) =>
  Response.json({ choices: [{ message: { role: 'assistant', content, ...(calls ? { tool_calls: calls } : {}) } }] });
const toolCall = (name: string, args: unknown) => ({ id: randomToken(4), type: 'function' as const, function: { name, arguments: JSON.stringify(args) } });
describe('AI validation, limits and drafts', () => {
  it('normalizes think tags, fences and single arrays', () => {
    expect(parseModelJson('<think>private reasoning</think>\n```json\n[{"name":null}]\n```')).toEqual({ name: null });
  });
  it('blocks private URLs and dangerous schemes', () => {
    for (const url of ['file:///etc/passwd', 'http://127.0.0.1', 'http://[::1]', 'http://metadata.google.internal', 'https://x.local', 'https://u:p@example.com']) expect(() => publicUrl(url)).toThrow();
  });
  it('rejects redirect hops to internal addresses', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 302, headers: { Location: 'http://127.0.0.1' } }));
    await expect(fetchPage('https://example.com')).rejects.toThrow();
  });
  it('encrypts settings and never returns keys', async () => {
    await saveApiKey(env, 'llm', 'mock-key');
    expect(await getApiKey(env, 'llm')).toBe('mock-key');
    const stored = await env.DB.prepare("SELECT value FROM app_settings WHERE key='key:llm'").first<string>('value');
    expect(stored).not.toContain('mock-key');
    const r = await app.request('https://home.test/api/ai/settings', { headers: { Cookie: 'hm_session=admin-token' } }, env);
    expect(await r.text()).not.toContain('mock-key');
  });
  it('does one repair, retrieves sources, and does not write products/cache', async () => {
    await saveApiKey(env, 'llm', 'mock-key'); await saveApiKey(env, 'tavily', 'mock-search');
    const mock = vi.spyOn(globalThis, 'fetch');
    mock.mockResolvedValueOnce(llm(null, [toolCall('web_search', { query: '测试药' })]))
      .mockResolvedValueOnce(Response.json({ results: [{ ...medicine.sources[0], content: '只有药名为测试药，其他信息未知。' }] }))
      .mockResolvedValueOnce(llm('not-json'))
      .mockResolvedValueOnce(llm(JSON.stringify([medicine])));
    const r = await call('/ai/lookup', { query: '测试药' });
    const s = await r.text();
    expect(s).toContain('event: result');
    expect(s).toContain('"requires_confirmation":true');
    expect(mock).toHaveBeenCalledTimes(4);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM products').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM catalog_cache').first('n')).toBe(0);
  });
  it('atomically applies group daily lookup quota', async () => {
    await saveApiKey(env, 'llm', 'mock-key'); await saveApiKey(env, 'tavily', 'mock-search');
    await env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('ai',?)").bind(JSON.stringify(aiSettingsSchema.parse({ lookup_daily_limit: 1 }))).run();
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(llm('{}'));
    await (await call('/ai/lookup', { query: '药' })).text();
    expect((await call('/ai/lookup', { query: '药' })).status).toBe(429);
  });
  it('streams a validated proposal but never saves a plan', async () => {
    await saveApiKey(env, 'llm', 'mock-key');
    const draft = { user_id: 'u', product_id: null, medicine_name: '测试药', amount: 1, unit: '片', frequency: 'daily', times: ['08:00'], start_date: currentDate };
    const streamResponse = (delta: unknown) => new Response(`data: ${JSON.stringify({ choices: [{ delta }] })}\n\ndata: [DONE]\n\n`, { headers: { 'Content-Type': 'text/event-stream' } });
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(streamResponse({ tool_calls: [{
      index: 0, ...toolCall('propose_plan', { draft }),
    }] })).mockResolvedValueOnce(streamResponse({ content: '请核对确认卡片。' }));
    const r = await call('/ai/chat', { user_id: 'u', messages: [{ role: 'user', content: '帮我记录' }] });
    const body = await r.text();
    expect(body).toContain('event: draft');
    expect(body).toContain('event: delta');
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM medication_plans').first('n')).toBe(1);
  });
});
async function pushEnv() {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const privateJwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
  const publicKey = b64(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)));
  const receiver = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const p256dh = b64(new Uint8Array(await crypto.subtle.exportKey('raw', receiver.publicKey)));
  for (const id of ['u', 'f']) await env.DB.prepare('INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth) VALUES(?,?,?,?,?)')
    .bind(id, id, `https://push.example.com/${id}`, p256dh, randomToken(16)).run();
  return { ...env, VAPID_PUBLIC_KEY: publicKey, VAPID_PRIVATE_KEY: privateJwk.d!, VAPID_SUBJECT: 'https://example.com' };
}
describe('encrypted cron notifications', () => {
  it('sends self then family after 30 minutes, deduplicating both', async () => {
    const e = await pushEnv(), mock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(null, { status: 201 }));
    await runReminders(e, new Date('2026-01-01T00:00:00Z'));
    expect(mock).toHaveBeenCalledTimes(1);
    await runReminders(e, new Date('2026-01-01T00:30:00Z'));
    expect(mock).toHaveBeenCalledTimes(2);
    await runReminders(e, new Date('2026-01-01T00:35:00Z'));
    expect(mock).toHaveBeenCalledTimes(2);
    const payload = mock.mock.calls[0][1]!;
    expect(payload.headers).toHaveProperty('content-encoding', 'aes128gcm');
  });
  it('cleans subscriptions when a push service returns 410', async () => {
    const e = await pushEnv();
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(null, { status: 410 }));
    await runReminders(e, new Date('2026-01-01T00:30:00Z'));
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM push_subscriptions').first('n')).toBe(0);
  });
  it('does not notify for a skipped dose', async () => {
    const e = await pushEnv(), mock = vi.spyOn(globalThis, 'fetch');
    await env.DB.prepare(`INSERT INTO dose_logs(id,plan_id,user_id,group_id,local_date,scheduled_time,status,amount,unit,actor_id)
      VALUES('log','p','u','g','2026-01-01','08:00','skipped',0,'片','u')`).run();
    await runReminders(e, new Date('2026-01-01T00:30:00Z'));
    expect(mock).not.toHaveBeenCalled();
  });
  it('catches family reminders across local midnight', async () => {
    const e = await pushEnv(), mock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(null, { status: 201 }));
    await env.DB.prepare(`UPDATE medication_plans SET times='["23:50"]'`).run();
    await runReminders(e, new Date('2026-01-01T16:20:00Z'));
    expect(mock).toHaveBeenCalledTimes(2);
    expect(await env.DB.prepare("SELECT local_date FROM notification_log WHERE kind='family'").first('local_date')).toBe('2026-01-01');
  });
});
