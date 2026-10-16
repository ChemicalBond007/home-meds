import { z } from 'zod';
import type { AISettings } from './ai-config';
export function publicUrl(value: string) {
  const u = new URL(value);
  const h = u.hostname.toLowerCase();
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password ||
    (u.port && !['80', '443'].includes(u.port)) || !h.includes('.') ||
    /^\d[\d.]*$/.test(h) || h.includes(':') || h.includes('[') ||
    /\.(localhost|local|internal|test|invalid|onion)$/.test(h) ||
    ['localhost', 'metadata.google.internal'].includes(h)) throw new Error('只允许公开的 HTTP/HTTPS 网页地址');
  return u;
}
export async function readBounded(response: Response, max = 2 * 1024 * 1024) {
  if (!response.body) return '';
  const reader = response.body.getReader(), decoder = new TextDecoder();
  let total = 0, text = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > max) throw new Error('响应超过 2MB 限制');
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally { await reader.cancel().catch(() => {}); }
}
export async function fetchPage(url: string) {
  const signal = AbortSignal.timeout(10000);
  let target = publicUrl(url);
  for (let i = 0; i <= 4; i++) {
    const r = await fetch(target, { redirect: 'manual', signal, headers: { Accept: 'text/html,text/plain,application/json' } });
    if (r.status >= 300 && r.status < 400 && r.headers.get('Location')) {
      await r.body?.cancel();
      target = publicUrl(new URL(r.headers.get('Location')!, target).href);
      continue;
    }
    if (!r.ok) { await r.body?.cancel(); throw new Error(`网页返回 ${r.status}`); }
    const type = r.headers.get('Content-Type') || '';
    if (!/text\/|application\/(json|xhtml)/i.test(type)) { await r.body?.cancel(); throw new Error('只读取文字网页'); }
    const text = (await readBounded(r)).replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');
    const bytes = new TextEncoder().encode(text);
    return { url: target.href, content: new TextDecoder().decode(bytes.slice(0, 20 * 1024)) };
  }
  throw new Error('网页重定向过多');
}
export interface SearchResult { title: string; url: string; content: string }
export async function tavily(query: string, key: string): Promise<SearchResult[]> {
  if (!key) throw new Error('请管理员先配置 Tavily 密钥');
  const r = await fetch('https://api.tavily.com/search', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
    body: JSON.stringify({ query, max_results: 5, search_depth: 'basic', include_answer: false }),
    signal: AbortSignal.timeout(10000),
  });
  if (!r.ok) { await r.body?.cancel(); throw new Error(`搜索服务返回 ${r.status}`); }
  const data = z.object({ results: z.array(z.object({ title: z.string(), url: z.string(), content: z.string() })) }).parse(JSON.parse(await readBounded(r)));
  return data.results.filter(x => { try { publicUrl(x.url); return true; } catch { return false; } })
    .map(x => ({ ...x, content: x.content.slice(0, 8000) }));
}
export async function duckduckgo(query: string): Promise<SearchResult[]> {
  const page = await fetchPage(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`);
  if (/anomaly|captcha|bots|challenge/i.test(page.content)) throw new Error('DuckDuckGo 需要人机验证，请保持关闭');
  return [{ title: 'DuckDuckGo 搜索页面', ...page }];
}
export interface ToolCall { id: string; type: 'function'; function: { name: string; arguments: string } }
export interface Message { role: 'system' | 'user' | 'assistant' | 'tool'; content: string | null; tool_calls?: ToolCall[]; tool_call_id?: string }
export interface Tool { type: 'function'; function: { name: string; description: string; parameters: Record<string, unknown> } }
export function tool(name: string, description: string, parameters: Record<string, unknown>): Tool {
  return { type: 'function', function: { name, description, parameters } };
}
export async function complete(settings: AISettings, key: string, messages: Message[], options: {
  tools?: Tool[]; delta?: (text: string) => Promise<void>; schema?: Record<string, unknown>;
} = {}): Promise<Message> {
  const base = publicUrl(settings.base_url);
  if (base.protocol !== 'https:') throw new Error('LLM 接口必须使用 HTTPS');
  const endpoint = `${base.href.replace(/\/$/, '')}/chat/completions`;
  const r = await fetch(endpoint, {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(settings.timeout_seconds * 1000),
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...settings.extra_body, model: settings.model, messages, max_tokens: settings.max_tokens, stream: !!options.delta,
      ...(options.tools?.length ? { tools: options.tools, tool_choice: 'auto' } : {}),
      ...(options.schema ? { response_format: { type: 'json_schema', json_schema: { name: 'medicine', strict: true, schema: options.schema } } } : {}),
    }),
  });
  if (!r.ok) { await r.body?.cancel(); throw new Error(`LLM 接口返回 ${r.status}，请检查后台配置`); }
  if (!options.delta) {
    const data = z.object({ choices: z.array(z.object({ message: z.object({
      role: z.literal('assistant'), content: z.string().nullable().optional(),
      tool_calls: z.array(z.object({ id: z.string(), type: z.literal('function'), function: z.object({ name: z.string(), arguments: z.string() }) })).max(5).optional(),
    }) })).min(1) }).parse(JSON.parse(await readBounded(r)));
    return { ...data.choices[0].message, content: data.choices[0].message.content || null };
  }
  if (!r.body) throw new Error('LLM 没有返回内容');
  const reader = r.body.getReader(), decoder = new TextDecoder();
  let buffer = '', content = '', bytes = 0;
  const calls = new Map<number, ToolCall>();
  const line = async (s: string) => {
    if (!s.startsWith('data:')) return;
    const raw = s.slice(5).trim();
    if (!raw || raw === '[DONE]') return;
    const chunk = JSON.parse(raw) as { choices?: { delta?: { content?: string; tool_calls?: { index: number; id?: string; function?: { name?: string; arguments?: string } }[] } }[] };
    const d = chunk.choices?.[0]?.delta;
    if (d?.content) { content += d.content; await options.delta!(d.content); }
    for (const t of d?.tool_calls || []) {
      if (calls.size >= 5 && !calls.has(t.index)) throw new Error('工具调用超过限制');
      const call = calls.get(t.index) || { id: '', type: 'function' as const, function: { name: '', arguments: '' } };
      call.id += t.id || ''; call.function.name += t.function?.name || ''; call.function.arguments += t.function?.arguments || '';
      calls.set(t.index, call);
    }
  };
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > 2 * 1024 * 1024) throw new Error('LLM 响应过大');
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n'); buffer = lines.pop() || '';
      for (const l of lines) await line(l.trimEnd());
    }
    buffer += decoder.decode();
    if (buffer.trim()) await line(buffer.trimEnd());
  } finally { await reader.cancel().catch(() => {}); }
  return { role: 'assistant', content: content || null, ...(calls.size ? { tool_calls: [...calls.values()] } : {}) };
}
export function parseModelJson(text: string) {
  const clean = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').trim();
  const data: unknown = JSON.parse(clean);
  return Array.isArray(data) && data.length === 1 ? data[0] : data;
}
