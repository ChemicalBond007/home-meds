import { Hono } from 'hono';
import { z } from 'zod';
import { type AppEnv, group, json, fail } from './core';
import { getSettings, getApiKey } from './ai-config';
import { chatPrompt, chatTarget, quota } from './ai';
import { chatToolExecutor } from './ai-tools';
import { chatToolLimit, toolError } from '../shared/ai-agent';
import { idSchema } from '../shared/schemas';
import { importDeviceKey, sealForDevice } from './device-crypto';

// The Android app runs the AI loop on the phone and talks to the provider
// itself. The Worker keeps quotas, database tools, query logs and audit.
export const appAi = new Hono<AppEnv>();
export const appSessionMinutes = 15;
const publicKey = z.string().min(200).max(4000);
const sessionRequest = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('lookup'), public_key: publicKey, query: z.string().trim().min(1).max(500) }),
  z.object({ kind: z.literal('chat'), public_key: publicKey, user_id: z.string(), intent: z.enum(['chat', 'draft']).default('chat') }),
]);
interface AppSession { id: string; kind: 'lookup' | 'chat'; target_user_id: string | null; query: string | null; known_medicines: string }

appAi.post('/app/ai/session', async c => {
  const g = await group(c), d = await json(c, sessionRequest);
  c.set('auditAction', d.kind === 'lookup' ? 'ai.lookup' : 'ai.chat');
  const target = d.kind === 'chat' ? await chatTarget(c, g, d.user_id) : null;
  const settings = await getSettings(c.env), key = await getApiKey(c.env, 'llm', settings);
  const searchKey = d.kind === 'lookup' && settings.search_mode === 'external' ? await getApiKey(c.env, 'tavily') : '';
  if (!key) fail(503, '请管理员先配置 LLM 密钥');
  if (d.kind === 'lookup' && settings.search_mode === 'external' && !searchKey) fail(503, '请管理员先配置 Tavily 密钥，或检测并启用提供商内置搜索');
  let deviceKey: CryptoKey;
  try { deviceKey = await importDeviceKey(d.public_key); } catch { fail(400, '设备密钥无效，请重新打开应用'); }
  await quota(c, g, d.kind, d.kind === 'lookup' ? settings.lookup_daily_limit : settings.chat_daily_limit);
  const id = crypto.randomUUID(), expiresAt = Date.now() + appSessionMinutes * 60000;
  await c.env.DB.prepare(`INSERT INTO app_ai_sessions(id,user_id,group_id,kind,target_user_id,query,expires_at) VALUES(?,?,?,?,?,?,?)`)
    .bind(id, c.get('user').id, g.id, d.kind, target?.id || null, d.kind === 'lookup' ? d.query : null, expiresAt).run();
  const credentials = await sealForDevice(deviceKey, {
    session_id: id, expires_at: expiresAt, llm_key: key, llm_base_url: settings.base_url,
    tavily_key: searchKey || null, tavily_url: 'https://api.tavily.com',
  });
  // The finish call records the final outcome, as the streaming routes do.
  c.set('auditSkip', true);
  return c.json({
    session_id: id, expires_at: expiresAt, settings, credentials,
    ...(target && d.kind === 'chat' ? { system_prompt: chatPrompt(c, g, target, d.intent) } : {}),
  });
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
