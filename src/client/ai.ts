import { api, sse } from './api';
import { isApp, nativeCall, nativeHttp } from './native';
import { runChat, runLookup, type ChatToolOutcome } from '../shared/ai-agent';
import type { AISettings } from '../shared/ai';
import type { Message } from '../shared/ai-http';

// Same events as the server-sent routes. In the Android app the loop runs on
// the phone: requests go straight to the provider, database tools go to the Worker.
type OnEvent = (event: string, data: unknown) => void;
interface AppSession { session_id: string; settings: AISettings; credentials: unknown; system_prompt?: string }
async function openSession(body: object) {
  const { public_key } = await nativeCall<{ public_key: string }>('crypto.publicKey');
  const s = await api<AppSession>('/app/ai/session', 'POST', { ...body, public_key });
  await nativeCall('ai.open', { session_id: s.session_id, credentials: s.credentials });
  return s;
}
async function close(s: AppSession, success: boolean) {
  await Promise.allSettled([
    api('/app/ai/finish', 'POST', { session_id: s.session_id, success }),
    nativeCall('ai.close', { session_id: s.session_id }),
  ]);
}
const keyRef = (s: AppSession, name: 'llm' | 'tavily') => `hm-key:${s.session_id}:${name}`;
export async function aiLookup(query: string, onEvent: OnEvent, signal?: AbortSignal) {
  if (!isApp()) return sse('/ai/lookup', { query }, onEvent, signal);
  const s = await openSession({ kind: 'lookup', query });
  let success = false;
  try {
    await runLookup({ settings: s.settings, key: keyRef(s, 'llm'), searchKey: keyRef(s, 'tavily'), query,
      http: nativeHttp(s.session_id, signal), emit: async (event, data) => onEvent(event, data) });
    success = !signal?.aborted;
    onEvent('done', {});
  } finally { await close(s, success); }
}
export async function aiChat(body: { user_id: string; intent: 'chat' | 'draft'; messages: { role: 'user' | 'assistant'; content: string }[] }, onEvent: OnEvent, signal?: AbortSignal) {
  if (!isApp()) return sse('/ai/chat', body, onEvent, signal);
  const s = await openSession({ kind: 'chat', user_id: body.user_id, intent: body.intent });
  let success = false;
  try {
    const messages: Message[] = [{ role: 'system', content: s.system_prompt || '' }, ...body.messages];
    success = await runChat({
      settings: s.settings, key: keyRef(s, 'llm'), messages, intent: body.intent, http: nativeHttp(s.session_id, signal),
      emit: async (event, data) => onEvent(event, data),
      executeTool: async (name, args) => {
        const r = await api<ChatToolOutcome | { error: string }>('/app/ai/tool', 'POST', { session_id: s.session_id, name, args });
        if ('error' in r) throw new Error(r.error);
        return r;
      },
    }) && !signal?.aborted;
  } finally { await close(s, success); }
}
