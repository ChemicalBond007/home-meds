import { decrypt, encrypt } from './crypto';
import type { Bindings } from './core';
import { aiSettingsSchema, connectionId, type AISettings } from '../shared/ai';
export { aiSettingsSchema, type AISettings } from '../shared/ai';
export async function getSettings(env: Bindings) {
  const r = await env.DB.prepare("SELECT value FROM app_settings WHERE key='ai'").first<{ value: string }>();
  return aiSettingsSchema.parse(r ? JSON.parse(r.value) : {});
}
export async function getApiKey(env: Bindings, name: 'llm' | 'tavily', settings?: AISettings) {
  // Read the ciphertext and its connection together, even during an admin rotation.
  const r = await env.DB.prepare(`SELECT secret.value,binding.value AS connection_id FROM app_settings secret
    LEFT JOIN app_settings binding ON binding.key=? WHERE secret.key=?`)
    .bind(`key:${name}:connection`, `key:${name}`).first<{ value: string; connection_id: string | null }>();
  if (name === 'llm' && settings && r?.connection_id && r.connection_id !== connectionId(settings)) return '';
  return r ? decrypt(env.APP_ENC_KEY, 'settings', r.value) : '';
}
export async function apiKeyStatements(env: Bindings, name: 'llm' | 'tavily', value: string, settings?: AISettings) {
  const encrypted = await encrypt(env.APP_ENC_KEY, 'settings', value);
  const statement = (key: string, value: string) => env.DB.prepare('INSERT INTO app_settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').bind(key, value);
  return [statement(`key:${name}`, encrypted),
    ...(name === 'llm' ? [statement('key:llm:connection', connectionId(settings || await getSettings(env)))] : [])];
}
export async function saveApiKey(env: Bindings, name: 'llm' | 'tavily', value: string) {
  await env.DB.batch(await apiKeyStatements(env, name, value));
}
export async function requestApiKey(env: Bindings, settings: AISettings, supplied?: string) {
  if (supplied?.trim()) return supplied.trim();
  // Never send a saved credential to a newly selected provider automatically.
  return connectionId(await getSettings(env)) === connectionId(settings) ? getApiKey(env, 'llm', settings) : '';
}
