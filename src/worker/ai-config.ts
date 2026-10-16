import { z } from 'zod';
import { decrypt, encrypt } from './crypto';
import type { Bindings } from './core';
export const aiSettingsSchema = z.object({
  base_url: z.url().default('https://claw.shu.edu.cn/api'),
  model: z.string().min(1).max(100).default('qwen3.5-27b'),
  extra_body: z.record(z.string(), z.unknown()).default({ enable_thinking: false }).refine(
    x => !['messages', 'tools', 'tool_choice', 'stream', 'model', 'response_format'].some(k => k in x), '额外参数不能覆盖消息、工具或输出配置',
  ),
  query_mode: z.enum(['tools', 'prefetch']).default('tools'),
  output_mode: z.enum(['prompt', 'json_schema']).default('prompt'),
  duckduckgo_enabled: z.boolean().default(false),
  lookup_daily_limit: z.number().int().min(1).max(1000).default(20),
  chat_daily_limit: z.number().int().min(1).max(1000).default(50),
  max_llm_calls: z.number().int().min(3).max(6).default(6),
  max_tool_calls: z.number().int().min(1).max(5).default(5),
  max_tokens: z.number().int().min(256).max(8192).default(2048),
  timeout_seconds: z.number().int().min(10).max(120).default(60),
});
export type AISettings = z.infer<typeof aiSettingsSchema>;
export async function getSettings(env: Bindings) {
  const r = await env.DB.prepare("SELECT value FROM app_settings WHERE key='ai'").first<{ value: string }>();
  return aiSettingsSchema.parse(r ? JSON.parse(r.value) : {});
}
export async function getApiKey(env: Bindings, name: 'llm' | 'tavily') {
  const r = await env.DB.prepare('SELECT value FROM app_settings WHERE key=?').bind(`key:${name}`).first<{ value: string }>();
  return r ? decrypt(env.APP_ENC_KEY, 'settings', r.value) : '';
}
export async function saveApiKey(env: Bindings, name: 'llm' | 'tavily', value: string) {
  const encrypted = await encrypt(env.APP_ENC_KEY, 'settings', value);
  await env.DB.prepare('INSERT INTO app_settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value')
    .bind(`key:${name}`, encrypted).run();
}
