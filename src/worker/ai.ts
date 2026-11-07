import { Hono } from 'hono';
import { streamSSE } from 'hono/streaming';
import { z } from 'zod';
import { type AppEnv, type Ctx, admin, group, json, fail, limit } from './core';
import { aiSettingsSchema, getSettings, getApiKey, requestApiKey, apiKeyStatements, settingsState } from './ai-config';
import { connectionId } from '../shared/ai';
import { listModels, nativeSearch, probeNativeSearch, searchProbeQuery } from './ai-native-search';
import { complete, tavily, duckduckgo, parseModelJson, tool, publicUrl, type Message } from './ai-http';
import { chatSystemPrompt, objectParams, runChat, runLookup } from '../shared/ai-agent';
import { localClock } from '../shared/schedule';
import { writeAudit } from './audit';
import { chatToolExecutor, type ChatTarget } from './ai-tools';
import type { FamilyGroup } from '../shared/types';
export { planToDraft } from './ai-tools';

export const ai = new Hono<AppEnv>();export async function quota(c: Ctx, g: FamilyGroup, kind: 'lookup' | 'chat', maximum: number) {
  const u = c.get('user');
  if (kind === 'lookup' && u.role === 'admin') return;
  const scope = kind === 'lookup' ? `group:${g.id}` : `user:${u.id}`;
  const date = localClock(new Date(), g.timezone).date;
  const r = await c.env.DB.prepare(`INSERT INTO ai_usage(scope,local_date,kind,count) VALUES(?,?,?,1)
    ON CONFLICT(scope,local_date,kind) DO UPDATE SET count=count+1 WHERE count<?
    RETURNING count`).bind(scope, date, kind, maximum).first();
  if (!r) fail(429, `今天的${kind === 'lookup' ? '药品查询' : '计划对话'}次数已用完`);
}
ai.get('/ai/settings', async c => {
  admin(c);
  return c.json(await settingsState(c.env));
});
ai.put('/ai/settings', async c => {
  admin(c);
  const d = await json(c, z.object({
    settings: aiSettingsSchema, llm_key: z.string().max(1000).optional(), tavily_key: z.string().max(1000).optional(),
  }));
  try {
    const url = publicUrl(d.settings.base_url);
    if (url.protocol !== 'https:' || url.search || url.hash) fail(400, 'LLM 接口必须是无查询参数的 HTTPS 根地址');
  } catch { fail(400, 'LLM 接口地址无效'); }
  if (!d.llm_key?.trim() && connectionId(await getSettings(c.env)) !== connectionId(d.settings) && await getApiKey(c.env, 'llm'))
    fail(400, '更换提供商或根地址后，请输入对应的新 LLM 密钥');
  const statements = [c.env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('ai',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").bind(JSON.stringify(d.settings))];
  if (d.llm_key?.trim()) statements.push(...await apiKeyStatements(c.env, 'llm', d.llm_key.trim(), d.settings));
  if (d.tavily_key?.trim()) statements.push(...await apiKeyStatements(c.env, 'tavily', d.tavily_key.trim()));
  await c.env.DB.batch(statements);
  return c.json({ ok: true, ...await settingsState(c.env) });
});
const providerDraft = z.object({ settings: aiSettingsSchema, llm_key: z.string().max(1000).optional() });
ai.post('/ai/models', async c => {
  admin(c);
  await limit(c.env.DB, `models:${c.get('user').id}`, 20, 600);
  const d = await json(c, providerDraft), key = await requestApiKey(c.env, d.settings, d.llm_key);
  if (!key) fail(503, '请先输入此提供商的 LLM 密钥；不会将另一提供商的已有密钥自动发送到新地址');
  try { return c.json({ models: await listModels(d.settings, key) }); }
  catch (e) { fail(503, e instanceof Error ? e.message : '模型列表获取失败'); }
});
ai.post('/ai/search-probe', async c => {
  admin(c);
  await limit(c.env.DB, `search-probe:${c.get('user').id}`, 5, 600);
  const d = await json(c, providerDraft), key = await requestApiKey(c.env, d.settings, d.llm_key);
  if (!key) fail(503, '请先输入此提供商的 LLM 密钥');
  try {
    const result = await probeNativeSearch(d.settings, key);
    c.set('auditFailed', !result.supported);
    return c.json(result);
  }
  catch (e) { fail(503, `暂时无法判断内置搜索能力，未更改搜索方式。${e instanceof Error ? e.message : '请稍后重试'}`); }
});
ai.post('/ai/probe', async c => {
  admin(c);
  await limit(c.env.DB, `probe:${c.get('user').id}`, 5, 600);
  const settings = await getSettings(c.env), key = await getApiKey(c.env, 'llm', settings);
  if (!key) fail(503, '请先保存 LLM 密钥');
  const tests: Record<string, unknown> = {};
  const run = async (name: string, work: () => Promise<unknown>) => {
    const start = Date.now();
    try { tests[name] = { ok: true, milliseconds: Date.now() - start, result: await work() }; }
    catch (e) { tests[name] = { ok: false, error: e instanceof Error ? e.message : '检测失败' }; }
    const item = tests[name] as Record<string, unknown>; item.milliseconds = Date.now() - start;
  };
  await run('prompt', async () => {
    const r = await complete(settings, key, [{ role: 'user', content: '只输出 JSON 对象 {"name":null}，不要思考。' }]);
    return parseModelJson(r.content || '');
  });
  await run('tool_roundtrip', async () => {
    const messages: Message[] = [{ role: 'user', content: '请调用 echo 工具，然后复述工具返回的内容。' }];
    const r = await complete(settings, key, messages, { tools: [tool('echo', '返回测试消息', objectParams({}))] });
    if (!r.tool_calls?.length) throw new Error('接口没有返回 tool_calls');
    messages.push(r, ...r.tool_calls.map(t => ({ role: 'tool' as const, content: '{"message":"echo-ok"}', tool_call_id: t.id })));
    const final = await complete(settings, key, messages);
    return { tool_calls: true, roundtrip: final.content?.includes('echo-ok') || false };
  });
  await run('json_schema', async () => {
    const r = await complete(settings, key, [{ role: 'user', content: '返回 name 为 null 的对象。' }], {
      schema: { type: 'object', properties: { name: { type: 'null' } }, required: ['name'], additionalProperties: false },
    });
    return { raw: (r.content || '').slice(0, 500), normalized: parseModelJson(r.content || '') };
  });
  if (settings.search_mode === 'builtin') {
    await run('native_search', async () => {
      const result = await nativeSearch(settings, key, searchProbeQuery);
      return { protocol: result.protocol, sources: result.sources };
    });
    tests.tavily = tests.duckduckgo = { ok: true, skipped: true, reason: '内置搜索模式已停用外部搜索 API' };
  } else {
    const tavilyKey = await getApiKey(c.env, 'tavily');
    if (tavilyKey) await run('tavily', async () => ({ results: (await tavily('药品说明书 国家药监局', tavilyKey)).length }));
    await run('duckduckgo', async () => ({ reachable: (await duckduckgo('药品说明书')).length > 0 }));
  }
  c.set('auditFailed', Object.values(tests).some(t => !(t as { ok: boolean }).ok));
  return c.json(tests);
});
ai.post('/ai/lookup', async c => {
  const g = await group(c), d = await json(c, z.object({ query: z.string().trim().min(1).max(500) }));
  const settings = await getSettings(c.env), key = await getApiKey(c.env, 'llm', settings);
  const searchKey = settings.search_mode === 'external' ? await getApiKey(c.env, 'tavily') : '';
  if (!key) fail(503, '请管理员先配置 LLM 密钥');
  if (settings.search_mode === 'external' && !searchKey) fail(503, '请管理员先配置 Tavily 密钥，或检测并启用提供商内置搜索');
  await quota(c, g, 'lookup', settings.lookup_daily_limit);
  c.set('auditStreaming', true);
  return streamSSE(c, async stream => {
    let success = false;
    const emit = async (event: string, data: unknown) => { await stream.writeSSE({ event, data: JSON.stringify(data) }); };
    try {
      await runLookup({ settings, key, searchKey, query: d.query, emit });
      success = true;
      await emit('done', {});
    } catch (e) { success = false; await emit('error', e instanceof Error ? e.message : '查询失败'); }
    finally {
      success = success && !stream.aborted;
      try {
        await c.env.DB.prepare('INSERT INTO ai_lookup_logs(id,user_id,group_id,query,success) VALUES(?,?,?,?,?)')
          .bind(crypto.randomUUID(), c.get('user').id, g.id, d.query, Number(success)).run();
      } finally { await writeAudit(c, success ? 'success' : 'failure', 200); }
    }
  });
});

export async function chatTarget(c: Ctx, g: FamilyGroup, userId: string) {
  const target = await c.env.DB.prepare('SELECT id,username,is_elderly FROM users WHERE id=? AND group_id=?').bind(userId, g.id).first<ChatTarget>();
  if (!target) fail(400, '计划对象不属于本群');
  return target;
}
export const chatPrompt = (c: Ctx, g: FamilyGroup, target: ChatTarget, intent: 'chat' | 'draft') => chatSystemPrompt({
  timezone: g.timezone, clock: localClock(new Date(), g.timezone), operator: c.get('user').username, target, mealTimes: g.meal_times, intent,
});
ai.post('/ai/chat', async c => {
  const g = await group(c);
  const d = await json(c, z.object({
    user_id: z.string(),
    intent: z.enum(['chat', 'draft']).default('chat'),
    messages: z.array(z.object({ role: z.enum(['user', 'assistant']), content: z.string().max(6000) })).min(1).max(20),
  }));
  const target = await chatTarget(c, g, d.user_id);
  const settings = await getSettings(c.env), key = await getApiKey(c.env, 'llm', settings);
  if (!key) fail(503, '请管理员先配置 LLM 密钥');
  await quota(c, g, 'chat', settings.chat_daily_limit);
  c.set('auditStreaming', true);
  return streamSSE(c, async stream => {
    let success = false;
    const emit = async (event: string, value: unknown) => { await stream.writeSSE({ event, data: JSON.stringify(value) }); };
    try {
      const messages: Message[] = [{ role: 'system', content: chatPrompt(c, g, target, d.intent) }, ...d.messages];
      success = await runChat({ settings, key, messages, intent: d.intent, executeTool: chatToolExecutor(c.env.DB, g, target, new Set()), emit });
    } catch (e) { await emit('error', e instanceof Error ? e.message : '对话失败'); }
    finally { await writeAudit(c, success && !stream.aborted ? 'success' : 'failure', 200); }
  });
});
