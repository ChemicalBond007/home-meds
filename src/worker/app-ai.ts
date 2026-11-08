import { Hono } from 'hono';
import { z } from 'zod';
import { type AppEnv, group, json, fail } from './core';
import { getSettings, getApiKey } from './ai-config';
import { chatPrompt, chatTarget, quota } from './ai';
import { chatToolExecutor } from './ai-tools';
import { chatToolLimit, toolError } from '../shared/ai-agent';
import { idSchema } from '../shared/schemas';
import { publicUrl } from './ai-http';

// Android runs the agent loop; only the Worker contacts credentialed providers.
export const appAi = new Hono<AppEnv>();
export const appSessionMinutes = 15;
const sessionRequest = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('lookup'), query: z.string().trim().min(1).max(500) }),
  z.object({ kind: z.literal('chat'), user_id: z.string(), intent: z.enum(['chat', 'draft']).default('chat') }),
]);
interface AppSession { id: string; kind: 'lookup' | 'chat'; target_user_id: string | null; query: string | null; known_medicines: string }

appAi.post('/app/ai/session', async c => {
  const raw = await c.req.json().catch(() => null);
  if (raw && typeof raw === 'object' && 'public_key' in raw) fail(501, '请升级应用；旧版文字功能使用服务器模式，照片识别需要新版');
  const g = await group(c), d = await json(c, sessionRequest);
  c.set('auditAction', d.kind === 'lookup' ? 'ai.lookup' : 'ai.chat');
  const target = d.kind === 'chat' ? await chatTarget(c, g, d.user_id) : null;
  const settings = await getSettings(c.env), key = await getApiKey(c.env, 'llm', settings);
  const searchKey = d.kind === 'lookup' && settings.search_mode === 'external' ? await getApiKey(c.env, 'tavily') : '';
  if (!key) fail(503, '请管理员先配置 LLM 密钥');
  if (d.kind === 'lookup' && settings.search_mode === 'external' && !searchKey) fail(503, '请管理员先配置 Tavily 密钥，或检测并启用提供商内置搜索');
  await quota(c, g, d.kind, d.kind === 'lookup' ? settings.lookup_daily_limit : settings.chat_daily_limit);
  const id = crypto.randomUUID(), expiresAt = Date.now() + appSessionMinutes * 60000;
  await c.env.DB.prepare(`INSERT INTO app_ai_sessions(id,user_id,group_id,kind,target_user_id,query,expires_at) VALUES(?,?,?,?,?,?,?)`)
    .bind(id, c.get('user').id, g.id, d.kind, target?.id || null, d.kind === 'lookup' ? d.query : null, expiresAt).run();
  // The finish call records the final outcome, as the streaming routes do.
  c.set('auditSkip', true);
  return c.json({
    session_id: id, expires_at: expiresAt, settings,
    ...(target && d.kind === 'chat' ? { system_prompt: chatPrompt(c, g, target, d.intent) } : {}),
  });
});
appAi.post('/app/ai/provider', async c => {
  c.set('auditSkip', true);
  const g = await group(c), d = await json(c, z.object({
    session_id: idSchema, service: z.enum(['llm', 'tavily']), path: z.string(),
    body: z.record(z.string(), z.unknown()),
  }));
  const paths = ['/chat/completions', '/responses', '/messages', '/anthropic/v1/messages'];
  if (d.service === 'llm' ? !paths.includes(d.path) : d.path !== '/search') fail(400, '不允许的提供商路径');
  const settings = await getSettings(c.env);
  if (d.service === 'tavily' && settings.search_mode !== 'external') fail(403, '外部搜索未启用');
  const counter = d.service === 'llm' ? 'provider_calls' : 'search_calls';
  const s = await c.env.DB.prepare(`UPDATE app_ai_sessions SET ${counter}=${counter}+1
    WHERE id=? AND user_id=? AND group_id=? AND finished_at IS NULL AND expires_at>? AND ${counter}<?
    ${d.service === 'tavily' ? "AND kind='lookup'" : ''}
    RETURNING kind`).bind(d.session_id, c.get('user').id, g.id, Date.now(), d.service === 'llm' ? 12 : 5)
    .first<{ kind: 'lookup' | 'chat' }>();
  if (!s) fail(409, '会话无效、已结束或调用次数已达上限');
  const key = await getApiKey(c.env, d.service, d.service === 'llm' ? settings : undefined);
  if (!key) fail(503, '请管理员配置提供商密钥');
  const body = { ...d.body };
  let url: string;
  const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
  if (d.service === 'llm') {
    const base = publicUrl(settings.base_url);
    if (base.protocol !== 'https:' || base.search || base.hash) fail(400, 'LLM 根地址必须是无查询参数的 HTTPS 地址');
    url = base.href.replace(/\/+$/, '') + d.path;
    body.model = settings.model;
    const cap = (value: unknown) => typeof value === 'number' && Number.isFinite(value) && value > 0
      ? Math.min(Math.floor(value), settings.max_tokens) : settings.max_tokens;
    if (d.path === '/responses') body.max_output_tokens = cap(body.max_output_tokens);
    else body.max_tokens = cap(body.max_tokens);
    for (const field of ['max_tokens', 'max_output_tokens', 'max_completion_tokens']) {
      if (field in body) body[field] = cap(body[field]);
    }
    if (s.kind !== 'lookup') {
      for (const field of ['enable_search', 'search_options', 'web_search_options', 'search_parameters',
        'max_tool_calls', 'include', ...Object.keys(settings.native_search_body)]) delete body[field];
      if (Array.isArray(body.tools)) body.tools = body.tools.filter(t =>
        !!t && typeof t === 'object' && (t as { type?: string }).type === 'function');
      if (typeof body.tool_choice === 'object') delete body.tool_choice;
    }
    body.store = false;
    if (d.path.endsWith('/messages')) {
      headers['x-api-key'] = key; headers['anthropic-version'] = '2023-06-01';
    } else headers.Authorization = `Bearer ${key}`;
  } else {
    url = 'https://api.tavily.com/search';
    delete body.api_key;
    body.max_results = 5; body.search_depth = 'basic'; body.include_answer = false;
    headers.Authorization = `Bearer ${key}`;
  }
  let response: Response;
  try {
    response = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body),
      redirect: 'manual', signal: AbortSignal.timeout(settings.timeout_seconds * 1000) });
  } catch { return new Response(null, { status: 502 }); }
  if (!response.ok) {
    await response.body?.cancel();
    return new Response(null, { status: response.status });
  }
  // Do not forward provider headers (cookies, redirects or debugging metadata).
  return new Response(response.body, { status: response.status,
    headers: { 'Content-Type': response.headers.get('Content-Type') || 'application/json' } });
});
appAi.post('/app/ai/tool', async c => {
  c.set('auditSkip', true);
  const g = await group(c);
  const d = await json(c, z.object({ session_id: idSchema, name: z.string().min(1).max(60), args: z.record(z.string(), z.unknown()) }));
  const s = await c.env.DB.prepare(`UPDATE app_ai_sessions SET tool_calls=tool_calls+1
    WHERE id=? AND user_id=? AND group_id=? AND kind='chat' AND finished_at IS NULL AND expires_at>? AND tool_calls<?
    RETURNING id,kind,target_user_id,query,known_medicines`)
    .bind(d.session_id, c.get('user').id, g.id, Date.now(), chatToolLimit).first<AppSession>();
  if (!s) fail(409, '对话已结束或工具次数已达上限，请重新发送');
  const target = await c.env.DB.prepare('SELECT id,username,is_elderly FROM users WHERE id=? AND group_id=?')
    .bind(s.target_user_id, g.id).first<{ id: string; username: string; is_elderly: number }>();
  if (!target) fail(400, '计划对象不属于本群');
  const known = new Set(JSON.parse(s.known_medicines) as string[]), before = known.size;
  try {
    return c.json(await chatToolExecutor(c.env.DB, g, target, known)(d.name, d.args));
  } catch (e) {
    return c.json({ error: toolError(e) });
  } finally {
    if (known.size !== before) await c.env.DB.prepare('UPDATE app_ai_sessions SET known_medicines=? WHERE id=?')
      .bind(JSON.stringify([...known]), s.id).run();
  }
});
appAi.post('/app/ai/finish', async c => {
  const g = await group(c), d = await json(c, z.object({ session_id: idSchema, success: z.boolean() }));
  const s = await c.env.DB.prepare(`UPDATE app_ai_sessions SET finished_at=?,success=?
    WHERE id=? AND user_id=? AND group_id=? AND finished_at IS NULL RETURNING id,kind,target_user_id,query,known_medicines`)
    .bind(Date.now(), Number(d.success), d.session_id, c.get('user').id, g.id).first<AppSession>();
  if (!s) fail(409, '会话不存在或已结束');
  c.set('auditAction', s.kind === 'lookup' ? 'ai.lookup' : 'ai.chat');
  c.set('auditFailed', !d.success);
  if (s.kind === 'lookup') await c.env.DB.prepare('INSERT INTO ai_lookup_logs(id,user_id,group_id,query,success) VALUES(?,?,?,?,?)')
    .bind(crypto.randomUUID(), c.get('user').id, g.id, s.query || '', Number(d.success)).run();
  return c.json({ ok: true });
});
