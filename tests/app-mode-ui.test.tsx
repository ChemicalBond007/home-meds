import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SettingsPage } from '../src/client/Settings';
import { Login } from '../src/client/Auth';
import { useLoad } from '../src/client/components';
import { isApp, nativeHttp } from '../src/client/native';
import type { User } from '../src/shared/types';

vi.mock('../src/client/api', () => ({ api: vi.fn(), sse: vi.fn() }));
vi.mock('../src/client/components', async importOriginal => ({
  ...await importOriginal<typeof import('../src/client/components')>(), useLoad: vi.fn(),
}));
const admin: User = { id: 'a', username: '管理员', role: 'admin', group_id: 'g', is_elderly: 0 };
const props = { groups: [], members: [admin], warehouses: [], changed: vi.fn(), logout: vi.fn() };
type Port = { postMessage: (m: string) => void; onmessage: ((e: { data: string }) => void) | null };
function installPort(handle: (request: { id: string; method: string; params: Record<string, unknown> }, reply: (m: object) => void) => void) {
  const port: Port = { onmessage: null, postMessage: raw => {
    const request = JSON.parse(raw);
    handle(request, m => queueMicrotask(() => port.onmessage?.({ data: JSON.stringify({ id: request.id, ...m }) })));
  } };
  vi.stubGlobal('window', { HomeMedsNative: port });
  return port;
}
afterEach(() => vi.unstubAllGlobals());

describe('Android app mode', () => {
  it('keeps administration on the website and shows calendar reminders instead of web push', () => {
    vi.mocked(useLoad).mockReturnValue({ data: [], error: '', loading: false, reload: vi.fn() });
    vi.stubGlobal('window', {});
    expect(isApp()).toBe(false);
    const web = renderToStaticMarkup(<SettingsPage {...props} user={admin} />);
    expect(web).toContain('>使用日志<'); expect(web).toContain('<h2>手机提醒');
    installPort(() => {});
    expect(isApp()).toBe(true);
    const html = renderToStaticMarkup(<SettingsPage {...props} user={admin} />);
    for (const text of ['>使用日志<', '>家庭管理<', '>AI 配置<', '<h2>手机提醒', '开启提醒']) expect(html).not.toContain(text);
    expect(html).toContain('日历与提醒');
    expect(html).toContain('只在网页版提供');
    expect(renderToStaticMarkup(<Login done={() => {}} />)).not.toContain('使用通行密钥登录');
  });
  it('streams native HTTP responses into a standard Response without exposing keys', async () => {
    const seen: Record<string, unknown>[] = [];
    installPort((request, reply) => {
      seen.push(request.params);
      if (request.method !== 'http.fetch') return;
      reply({ type: 'event', data: { kind: 'head', status: 200, headers: { 'content-type': 'text/event-stream' } } });
      for (const part of ['data: 你', '好\n\n']) reply({ type: 'event', data: { kind: 'chunk', data: btoa(String.fromCharCode(...new TextEncoder().encode(part))) } });
      reply({ type: 'result', data: null });
    });
    const r = await nativeHttp('s1')('https://api.example.com/chat', { method: 'POST', headers: { Authorization: 'Bearer hm-key:s1:llm' }, body: '{}' });
    expect(r.status).toBe(200);
    expect(await r.text()).toBe('data: 你好\n\n');
    expect(seen[0]).toMatchObject({ session_id: 's1', url: 'https://api.example.com/chat', method: 'POST', headers: { authorization: 'Bearer hm-key:s1:llm' } });
  });
  it('rejects aborted native requests and tells the shell to cancel', async () => {
    const methods: string[] = [];
    installPort(request => { methods.push(request.method); });
    const controller = new AbortController();
    const pending = nativeHttp('s1', controller.signal)('https://api.example.com/x');
    controller.abort();
    await expect(pending).rejects.toBeTruthy();
    expect(methods).toEqual(['http.fetch', 'http.abort']);
  });
});
