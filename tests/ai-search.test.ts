import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { aiSettingsSchema, getApiKey, getSettings, saveApiKey } from '../src/worker/ai-config';
import { complete } from '../src/worker/ai-http';
import { nativeSearch, nativeSources } from '../src/worker/ai-native-search';
import { mockFetch } from './fetch';

const source = { title: '虚构药品资料', url: 'https://example.com/medicine' };
const medicine = { name: '虚构药', generic_name: null, manufacturer: null, specification: null, indications: null,
  precautions: null, base_unit: null, units_per_package: null, sources: [source] };
const llm = (content: string) => Response.json({ choices: [{ message: { role: 'assistant', content } }] });
const anthropic = () => Response.json({ content: [
  { type: 'server_tool_use', id: 'search', name: 'web_search', input: { query: '虚构药' } },
  { type: 'web_search_tool_result', content: [{ type: 'web_search_result', ...source }] },
  { type: 'text', text: '只有药名，其他信息未知。', citations: [{ type: 'web_search_result_location', ...source }] },
] });
const responses = () => Response.json({ output: [
  { type: 'web_search_call', action: { sources: [source] } },
  { type: 'message', content: [{ type: 'output_text', text: '只有药名，其他信息未知。',
    annotations: [{ type: 'url_citation', ...source }] }] },
] });
async function call(path: string, body: object, options: { method?: string; admin?: boolean } = {}) {
  return app.request(`https://home.test/api${path}`, {
    method: options.method || 'POST', headers: { Origin: 'https://home.test', Cookie: `hm_session=${options.admin === false ? 'user' : 'admin'}-token`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, env);
}
async function settings(value: object = {}) {
  const parsed = aiSettingsSchema.parse(value);
  await env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('ai',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
    .bind(JSON.stringify(parsed)).run();
  return parsed;
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('a','管理','admin','g'),('u','本人','user','g')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('admin-token'), 'a', Date.now() + 86400000),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('user-token'), 'u', Date.now() + 86400000),
  ]);
});
afterEach(() => vi.restoreAllMocks());

describe('provider settings and model discovery', () => {
  it('preserves legacy external search defaults and normalizes the fixed DeepSeek connection', () => {
    expect(aiSettingsSchema.parse({})).toMatchObject({ provider: 'custom', search_mode: 'external', duckduckgo_enabled: false });
    expect(aiSettingsSchema.parse({ provider: 'deepseek', base_url: 'https://other.example.com', search_mode: 'builtin', duckduckgo_enabled: true }))
      .toMatchObject({ base_url: 'https://api.deepseek.com', native_search_api: 'anthropic', duckduckgo_enabled: false });
  });
  it('fetches DeepSeek models with an unsaved key, without persisting it or trusting a supplied base URL', async () => {
    const mock = mockFetch(Response.json({ data: [{ id: 'model-one' }, { id: 'model-two', name: '模型二' }, { id: 'model-one' }] }));
    const r = await call('/ai/models', { settings: { provider: 'deepseek', base_url: 'https://other.example.com' }, llm_key: 'mock-provider' });
    expect(await r.json()).toEqual({ models: [{ id: 'model-one', name: 'model-one' }, { id: 'model-two', name: '模型二' }] });
    expect(mock.requests.map(r => [r.url, r.method, r.redirect])).toEqual([['https://api.deepseek.com/models', 'GET', 'manual']]);
    expect(mock.requests[0].headers.get('Authorization')).toBe('Bearer mock-provider');
    expect(await getApiKey(env, 'llm')).toBe('');
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.models'").first('outcome')).toBe('success');
    expect(JSON.stringify((await env.DB.prepare('SELECT * FROM audit_logs').all()).results)).not.toContain('mock-provider');
  });
  it('reuses an encrypted key only for the saved provider and address', async () => {
    await saveApiKey(env, 'llm', 'mock-saved');
    const mock = mockFetch(Response.json({ data: [{ id: 'existing-model' }] }));
    expect((await call('/ai/models', { settings: {} })).status).toBe(200);
    expect(mock.requests[0].headers.get('Authorization')).toBe('Bearer mock-saved');
    expect((await call('/ai/models', { settings: { provider: 'deepseek' } })).status).toBe(503);
    expect((await call('/ai/models', { settings: { base_url: 'https://new.example.com' } })).status).toBe(503);
    expect(mock.requests).toHaveLength(1);
  });
  it('requires a fresh key when saving a different connection and preserves the old configuration on failure', async () => {
    await saveApiKey(env, 'llm', 'mock-saved');
    const before = await getSettings(env);
    expect((await call('/ai/settings', { settings: { provider: 'deepseek' } }, { method: 'PUT' })).status).toBe(400);
    expect(await getSettings(env)).toEqual(before);
    expect((await call('/ai/settings', { settings: { provider: 'deepseek' }, llm_key: 'mock-new' }, { method: 'PUT' })).status).toBe(200);
    expect(await getApiKey(env, 'llm')).toBe('mock-new');
    expect(await getSettings(env)).toMatchObject({ provider: 'deepseek', base_url: 'https://api.deepseek.com' });
    // A lookup that read the old config before rotation must not send the new key to the old provider.
    expect(await getApiKey(env, 'llm', before)).toBe('');
    expect(await getApiKey(env, 'llm', await getSettings(env))).toBe('mock-new');
  });
  it.each(['/ai/models', '/ai/search-probe'])('restricts %s to administrators', async path => {
    const mock = mockFetch();
    expect((await call(path, { settings: {}, llm_key: 'mock-provider' }, { admin: false })).status).toBe(403);
    expect(mock).not.toHaveBeenCalled();
  });
  it.each(['http://127.0.0.1', 'https://localhost', 'https://example.com/?key=x'])('blocks unsafe provider roots: %s', async base_url => {
    const mock = mockFetch();
    expect((await call('/ai/models', { settings: { base_url }, llm_key: 'mock-provider' })).status).toBe(503);
    expect(mock).not.toHaveBeenCalled();
  });
  it('does not follow a model-list redirect or expose its response body', async () => {
    const mock = mockFetch(new Response('private-upstream-body', { status: 307, headers: { Location: 'https://other.example.com' } }));
    const r = await call('/ai/models', { settings: {}, llm_key: 'mock-provider' });
    expect(r.status).toBe(503); expect(await r.text()).not.toContain('private-upstream-body');
    expect(mock.requests.map(r => r.redirect)).toEqual(['manual']);
  });
});

describe('built-in search evidence and detection', () => {
  it('routes DeepSeek search through Anthropic and returns real provider source metadata', async () => {
    const mock = mockFetch(anthropic());
    const config = aiSettingsSchema.parse({ provider: 'deepseek', search_mode: 'builtin', extra_body: {} });
    const r = await call('/ai/search-probe', { settings: config, llm_key: 'mock-provider' });
    expect(await r.json()).toMatchObject({ supported: true, search_mode: 'builtin', native_search_api: 'anthropic', sources: [source] });
    expect(mock.requests[0].url).toBe('https://api.deepseek.com/anthropic/v1/messages');
    expect(mock.requests[0].headers.get('x-api-key')).toBe('mock-provider');
    expect(await mock.requests[0].json()).toMatchObject({ tools: [{ type: 'web_search_20250305', name: 'web_search', max_uses: 5 }] });
    expect(await getSettings(env)).toMatchObject({ search_mode: 'external' }); // Probe never saves implicitly.
  });
  it('tries the compatible chat search protocol after an unsupported Responses endpoint', async () => {
    const mock = mockFetch(new Response(null, { status: 404 }), Response.json({
      choices: [{ message: { content: '检索摘要' } }], search_info: { search_results: [source] },
    }));
    const r = await call('/ai/search-probe', { settings: {}, llm_key: 'mock-provider' });
    expect(await r.json()).toMatchObject({ supported: true, native_search_api: 'chat_completions', search_mode: 'builtin', sources: [source] });
    expect(mock.requests.map(r => new URL(r.url).pathname)).toEqual(['/api/responses', '/api/chat/completions']);
    expect(await mock.requests[1].json()).toMatchObject({ enable_search: true });
  });
  it('selects external search when endpoints are unsupported or only claim to have searched', async () => {
    await settings({ search_mode: 'builtin' });
    const mock = mockFetch(new Response(null, { status: 404 }), llm(`我已联网，来源是 ${source.url}`));
    const r = await call('/ai/search-probe', { settings: {}, llm_key: 'mock-provider' });
    expect(await r.json()).toMatchObject({ supported: false, search_mode: 'external', sources: [] });
    expect(mock.requests).toHaveLength(2);
    expect(await getSettings(env)).toMatchObject({ search_mode: 'builtin' });
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.search.probe'").first('outcome')).toBe('failure');
  });
  it.each([401, 403, 429, 500])('does not mistake HTTP %s for lack of search support or silently switch modes', async status => {
    await settings({ search_mode: 'builtin' });
    const mock = mockFetch(new Response('private-upstream-body', { status }));
    const r = await call('/ai/search-probe', { settings: {}, llm_key: 'mock-provider' });
    expect(r.status).toBe(503);
    expect(await r.text()).not.toContain('private-upstream-body');
    expect(mock.requests).toHaveLength(1);
    expect(await getSettings(env)).toMatchObject({ search_mode: 'builtin' });
  });
  it('accepts Responses and chat citation formats, but rejects invented links in model text and private metadata URLs', async () => {
    expect(nativeSources(await responses().json(), 'responses').sources).toEqual([source]);
    expect(nativeSources({ choices: [{ message: { content: '摘要', annotations: [{ type: 'url_citation', url_citation: source }] } }] }, 'chat_completions').sources).toEqual([source]);
    expect(nativeSources({ choices: [{ message: { content: '摘要' } }], citations: [source.url] }, 'chat_completions').sources[0].url).toBe(source.url);
    expect(() => nativeSources({ output_text: JSON.stringify({ sources: [source] }) }, 'responses')).toThrow('未返回可核对');
    expect(() => nativeSources({ citations: ['http://127.0.0.1/private', 'javascript:alert(1)'] }, 'chat_completions')).toThrow('未返回可核对');
  });
  it('honors dedicated chat-search parameters without adding an incompatible enable_search field', async () => {
    const mock = mockFetch(Response.json({ citations: [source.url], choices: [{ message: { content: '摘要' } }] }));
    await nativeSearch(aiSettingsSchema.parse({ native_search_api: 'chat_completions', native_search_body: { web_search_options: {} } }), 'mock-provider', '虚构药');
    const body = await mock.requests[0].json() as Record<string, unknown>;
    expect(body.web_search_options).toEqual({}); expect(body).not.toHaveProperty('enable_search');
  });
  it('protects transport, privacy, messages, tools and limits from custom search parameter overrides', () => {
    for (const key of ['input', 'messages', 'model', 'tools', 'tool_choice', 'stream', 'store', 'include', 'max_tokens', 'max_output_tokens', 'max_tool_calls']) {
      expect(aiSettingsSchema.safeParse({ native_search_body: { [key]: 'override' } }).success).toBe(false);
    }
  });
});

describe('medicine lookup search routing', () => {
  it.each(['tools', 'prefetch'])('uses DeepSeek built-in search without a Tavily key in legacy %s mode', async query_mode => {
    await settings({ provider: 'deepseek', search_mode: 'builtin', duckduckgo_enabled: true, extra_body: {}, query_mode });
    await saveApiKey(env, 'llm', 'mock-provider');
    const mock = mockFetch(anthropic(), llm(JSON.stringify(medicine)));
    const body = await (await call('/ai/lookup', { query: '虚构药' }, { admin: false })).text();
    expect(body).toContain('event: result'); expect(body).toContain('"requires_confirmation":true');
    expect(mock.requests.map(r => r.url)).toEqual(['https://api.deepseek.com/anthropic/v1/messages', 'https://api.deepseek.com/chat/completions']);
    const formatting = await mock.requests[1].json() as Record<string, unknown>;
    expect(formatting).not.toHaveProperty('tools'); expect(formatting).not.toHaveProperty('enable_search');
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM products').first('n')).toBe(0);
  });
  it('uses Responses search, removes fabricated citations, and allows exactly one format repair within the shared budget', async () => {
    await settings({ search_mode: 'builtin', max_llm_calls: 3 });
    await saveApiKey(env, 'llm', 'mock-provider');
    const mock = mockFetch(responses(), llm('not JSON'), llm(JSON.stringify({ ...medicine, sources: [{ title: '假来源', url: 'https://fake.example.com' }] })));
    const body = await (await call('/ai/lookup', { query: '虚构药' }, { admin: false })).text();
    expect(body).toContain('event: result'); expect(body).toContain(source.url); expect(body).not.toContain('fake.example.com');
    expect(mock.requests).toHaveLength(3);
    expect(await mock.requests[0].json()).toMatchObject({ store: false, max_tool_calls: 5 });
  });
  it('fails closed on missing search evidence and never calls configured external APIs as a hidden fallback', async () => {
    await settings({ search_mode: 'builtin', duckduckgo_enabled: true });
    await saveApiKey(env, 'llm', 'mock-provider'); await saveApiKey(env, 'tavily', 'mock-unused');
    const mock = mockFetch(Response.json({ output: [{ type: 'message', content: [{ type: 'output_text', text: '我已联网。' }] }] }));
    const body = await (await call('/ai/lookup', { query: '虚构药' }, { admin: false })).text();
    expect(body).toContain('event: error'); expect(body).not.toContain('event: result');
    expect(mock.requests).toHaveLength(1); expect(new URL(mock.requests[0].url).pathname).toBe('/api/responses');
    expect(await env.DB.prepare("SELECT success FROM ai_lookup_logs").first('success')).toBe(0);
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.lookup'").first('outcome')).toBe('failure');
  });
  it('still requires Tavily for legacy external search before making any network request', async () => {
    await saveApiKey(env, 'llm', 'mock-provider');
    const mock = mockFetch();
    const r = await call('/ai/lookup', { query: '虚构药' }, { admin: false });
    expect(r.status).toBe(503); expect(mock).not.toHaveBeenCalled();
  });
  it('keeps dedicated search parameters away from private chat and ordinary formatting', async () => {
    const mock = mockFetch(llm('完成'));
    const config = aiSettingsSchema.parse({ search_mode: 'builtin', extra_body: { enable_search: true, search_options: {} },
      native_search_body: { web_search_options: { search_context_size: 'low' } } });
    await complete(config, 'mock-provider', [{ role: 'user', content: 'private-plan-data' }]);
    const body = await mock.requests[0].json() as Record<string, unknown>;
    expect(body).not.toHaveProperty('enable_search'); expect(body).not.toHaveProperty('search_options');
    expect(body).not.toHaveProperty('web_search_options'); expect(body).not.toHaveProperty('tools');
  });
  it('skips both external providers during the saved-config capability probe', async () => {
    await settings({ search_mode: 'builtin' });
    await saveApiKey(env, 'llm', 'mock-provider'); await saveApiKey(env, 'tavily', 'mock-unused');
    const tool = { id: 'echo', type: 'function', function: { name: 'echo', arguments: '{}' } };
    const mock = mockFetch(llm('{"name":null}'), Response.json({ choices: [{ message: { role: 'assistant', content: null, tool_calls: [tool] } }] }),
      llm('echo-ok'), llm('{"name":null}'), responses());
    expect(await (await call('/ai/probe', {})).json()).toMatchObject({
      native_search: { ok: true }, tavily: { skipped: true }, duckduckgo: { skipped: true },
    });
    expect(mock.requests).toHaveLength(5);
    expect(mock.requests.every(r => new URL(r.url).host === 'claw.shu.edu.cn')).toBe(true);
  });
});
