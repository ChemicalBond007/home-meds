// Bridge to the Android app shell. The shell injects `HomeMedsNative` only on
// the site's own origin; in a normal browser none of this is active.
interface NativePort {
  postMessage(message: string): void;
  onmessage: ((event: { data: string }) => void) | null;
}
interface Reply { id: string; type: 'event' | 'result' | 'error'; data?: unknown; message?: string }
interface Pending { resolve: (value: unknown) => void; reject: (error: Error) => void; onEvent?: (data: unknown) => void }
const pending = new Map<string, Pending>();
let bound: NativePort | undefined, sequence = 0;
function port() {
  const p = typeof window === 'undefined' ? undefined : (window as Window & { HomeMedsNative?: NativePort }).HomeMedsNative;
  if (p && p !== bound) {
    bound = p;
    p.onmessage = event => {
      let reply: Reply;
      try { reply = JSON.parse(event.data) as Reply; } catch { return; }
      const request = pending.get(reply.id);
      if (!request) return;
      if (reply.type === 'event') { request.onEvent?.(reply.data); return; }
      pending.delete(reply.id);
      if (reply.type === 'result') request.resolve(reply.data);
      else request.reject(new Error(reply.message || '手机功能暂不可用'));
    };
  }
  return p;
}
export const isApp = () => !!port();
export function nativeRequest<T>(method: string, params: unknown = {}, onEvent?: (data: unknown) => void) {
  const p = port(), id = `r${++sequence}`;
  const promise = new Promise<T>((resolve, reject) => {
    if (!p) { reject(new Error('仅在家药箱手机应用中可用')); return; }
    pending.set(id, { resolve: resolve as (value: unknown) => void, reject, onEvent });
    p.postMessage(JSON.stringify({ id, method, params }));
  });
  return { id, promise };
}
export const nativeCall = <T>(method: string, params?: unknown) => nativeRequest<T>(method, params).promise;

export interface ReminderStatus {
  calendar_permission: boolean; notification_permission: boolean; exact_alarm: boolean; battery_optimized: boolean;
  members: string[]; calendar_alerts: boolean; app_notifications: boolean;
  last_sync: number | null; last_error: string | null; events: number; alarms: number;
}
let syncTimer: ReturnType<typeof setTimeout> | undefined;
/** Re-syncs calendar events and reminders after plan or dose changes. */
export function nativePlansChanged() {
  if (!isApp()) return;
  clearTimeout(syncTimer);
  syncTimer = setTimeout(() => { nativeCall('calendar.sync').catch(() => {}); }, 1500);
}

const fromBase64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0));
/**
 * A fetch implementation backed by the phone's native HTTP stack. Provider keys
 * are referenced by placeholders and inserted by the shell, never seen here.
 */
export function nativeHttp(sessionId: string, outer?: AbortSignal) {
  return (input: string | URL, init: RequestInit = {}) => new Promise<Response>((resolve, reject) => {
    const headers: Record<string, string> = {};
    new Headers(init.headers).forEach((value, key) => { headers[key] = value; });
    let controller: ReadableStreamDefaultController<Uint8Array> | undefined, settled = false, finished = false;
    const signals = [init.signal, outer].filter((s): s is AbortSignal => !!s);
    const fail = (error: unknown) => {
      if (finished) return;
      finished = true;
      if (!settled) { settled = true; reject(error); } else controller?.error(error);
    };
    const request = nativeRequest<null>('http.fetch', {
      session_id: sessionId, url: String(input instanceof URL ? input.href : input), method: init.method || 'GET', headers,
      body: typeof init.body === 'string' ? init.body : null, redirect: init.redirect || 'follow',
    }, data => {
      const event = data as { kind: 'head'; status: number; headers: Record<string, string> } | { kind: 'chunk'; data: string };
      if (event.kind === 'head') {
        const empty = [101, 204, 205, 304].includes(event.status);
        const body = empty ? null : new ReadableStream<Uint8Array>({
          start(c) { controller = c; },
          cancel() { abort(); },
        });
        settled = true;
        resolve(new Response(body, { status: event.status, headers: event.headers }));
      } else if (event.kind === 'chunk' && !finished) {
        try { controller?.enqueue(fromBase64(event.data)); } catch { /* The reader already cancelled. */ }
      }
    });
    const abort = () => {
      if (finished) return;
      nativeCall('http.abort', { request_id: request.id }).catch(() => {});
      fail(signals.find(s => s.aborted)?.reason ?? new DOMException('请求已取消', 'AbortError'));
    };
    for (const s of signals) { if (s.aborted) { abort(); return; } s.addEventListener('abort', abort, { once: true }); }
    request.promise.then(() => {
      if (finished) return;
      finished = true;
      if (!settled) { settled = true; reject(new Error('网络请求没有返回内容')); } else try { controller?.close(); } catch { /* Already cancelled. */ }
    }, fail).finally(() => signals.forEach(s => s.removeEventListener('abort', abort)));
  });
}
