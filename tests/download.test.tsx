import { beforeEach, describe, expect, it, vi } from 'vitest';
import { env } from 'cloudflare:workers';
import { renderToStaticMarkup } from 'react-dom/server';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { downloadSettingsSchema, isDownloadPath } from '../src/shared/download';
import { DownloadView } from '../src/client/DownloadPage';
import { DownloadSettings } from '../src/client/DownloadSettings';
import { Site } from '../src/client/App';

vi.mock('../src/client/api', () => ({ api: vi.fn(), selectGroup: vi.fn(), selectedGroup: '' }));
const apk = 'https://downloads.example.com/get?file=app.apk&signature=demo%2Bvalue%2F123%3D';
async function call(path: string, token?: string, method = 'GET', body?: object, extra: Record<string, string> = {}) {
  return app.request(`https://home.test/api${path}`, { method,
    headers: { Origin: 'https://home.test', 'Content-Type': 'application/json', ...(token ? { Cookie: `hm_session=${token}` } : {}), ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  }, env);
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO users(id,username,role) VALUES('a','管理员','admin'),('u','成员','user')"),
    env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('key:llm','private-test-key')"),
  ]);
  for (const [token, user, limited] of [['admin', 'a', 0], ['member', 'u', 0], ['limited', 'a', 1]] as const)
    await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,binding_only,expires_at) VALUES(?,?,?,?)')
      .bind(await hash(token), user, limited, Date.now() + 365 * 86400000).run();
});
describe('public application download configuration', () => {
  it('allows guests and all session states to read only public download data without exposing account or AI settings', async () => {
    for (const token of [undefined, 'invalid', 'admin', 'member', 'limited']) {
      const response = await call('/download', token);
      expect(response.status).toBe(200);
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      expect(await response.json()).toEqual({ android_apk_url: '' });
    }
    expect(await env.DB.prepare("SELECT COUNT(*) n FROM audit_logs WHERE method='GET'").first('n')).toBe(0);
  });
  it('restricts private reads and all saves to full administrator sessions', async () => {
    for (const [token, status] of [[undefined, 401], ['member', 403], ['limited', 403]] as const) {
      expect((await call('/admin/download', token)).status).toBe(status);
      expect((await call('/admin/download', token, 'PUT', { android_apk_url: apk })).status).toBe(status);
    }
    expect((await call('/admin/download', 'admin')).status).toBe(200);
    expect((await call('/admin/download', 'admin', 'PUT', { android_apk_url: apk }, { Origin: 'https://evil.test' })).status).toBe(403);
    expect(await env.DB.prepare("SELECT COUNT(*) n FROM app_settings WHERE key='app:download'").first('n')).toBe(0);
  });
  it('saves once and returns state immediately, preserving signed URLs and exposing changes to the next guest request', async () => {
    const saved = await call('/admin/download', 'admin', 'PUT', { android_apk_url: ` ${apk} ` });
    expect(saved.status).toBe(200);
    expect(await saved.json()).toEqual({ android_apk_url: apk });
    expect(await (await call('/download')).json()).toEqual({ android_apk_url: apk });
    const bootstrap = await (await call('/admin/bootstrap', 'admin')).json() as { download: unknown };
    expect(bootstrap.download).toEqual({ android_apk_url: apk });
    const replacement = 'https://cdn.example.com/releases/new.apk';
    expect((await call('/admin/download', 'admin', 'PUT', { android_apk_url: replacement })).status).toBe(200);
    expect(await (await call('/download')).json()).toEqual({ android_apk_url: replacement });
    expect((await call('/admin/download', 'admin', 'PUT', { android_apk_url: '' })).status).toBe(200);
    expect(await (await call('/download')).json()).toEqual({ android_apk_url: '' });
    const audits = (await env.DB.prepare("SELECT * FROM audit_logs WHERE action='download.settings.update'").all()).results;
    expect(audits).toHaveLength(3);
    expect(audits.every(a => a.actor_id === 'a' && a.outcome === 'success')).toBe(true);
    expect(JSON.stringify(audits)).not.toContain('signature=');
  });
  it('rejects unsafe, private, malformed and overlong links without replacing the saved link', async () => {
    await call('/admin/download', 'admin', 'PUT', { android_apk_url: apk });
    for (const url of ['javascript:alert(1)', 'data:text/html,test', 'file:///app.apk', 'http://downloads.example.com/app.apk',
      'https://localhost/app.apk', 'https://127.0.0.1/app.apk', 'https://10.0.0.1/app.apk', 'https://[::1]/app.apk',
      'https://intranet.local/app.apk', 'https://user:password@downloads.example.com/app.apk', '/app.apk', 'x'.repeat(4097)]) {
      expect((await call('/admin/download', 'admin', 'PUT', { android_apk_url: url })).status).toBe(400);
    }
    expect((await call('/admin/download', 'admin', 'PUT', {})).status).toBe(400);
    expect(await (await call('/download')).json()).toEqual({ android_apk_url: apk });
  });
  it('does not permit writes through the public route or bypass authentication for neighboring routes', async () => {
    expect((await call('/download', undefined, 'PUT', { android_apk_url: apk })).status).toBe(404);
    expect((await call('/download/private')).status).toBe(401);
    expect((await call('/admin/bootstrap')).status).toBe(401);
    expect(await (await call('/download')).json()).toEqual({ android_apk_url: '' });
  });
  it('fails closed on corrupted stored settings without exposing unrelated secrets', async () => {
    for (const value of ['{bad', JSON.stringify({ android_apk_url: 'javascript:alert(1)' }), JSON.stringify({ android_apk_url: 123 })]) {
      await env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('app:download',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").bind(value).run();
      expect(await (await call('/download')).json()).toEqual({ android_apk_url: '' });
    }
  });
});
describe('public download UI and routes', () => {
  it('renders a direct download link without login, credentials or misleading forced download behavior', () => {
    const html = renderToStaticMarkup(<DownloadView data={{ android_apk_url: apk }} />);
    expect(html).toContain('下载安卓版 APK');
    expect(html).toContain('href="https://downloads.example.com/get?file=app.apk&amp;signature=demo%2Bvalue%2F123%3D"');
    expect(html).toContain('referrerPolicy="no-referrer"');
    expect(html).not.toContain('管理员登录');
    expect(html).not.toContain('download=""');
  });
  it('shows clear loading, unconfigured and retry states, and never renders an unsafe link', () => {
    expect(renderToStaticMarkup(<DownloadView loading />)).toContain('正在获取下载链接');
    const empty = renderToStaticMarkup(<DownloadView data={{ android_apk_url: '' }} />);
    expect(empty).toContain('暂未开放下载');
    expect(empty).not.toMatch(/<a\b[^>]*href=/);
    for (const data of [{ android_apk_url: 'javascript:alert(1)' }, { android_apk_url: 1 }]) {
      const html = renderToStaticMarkup(<DownloadView data={data} />);
      expect(html).toContain('重新加载');
      expect(html).not.toMatch(/<a\b[^>]*href=/);
    }
    expect(renderToStaticMarkup(<DownloadView error="network failed" />)).toContain('下载信息暂时无法加载');
  });
  it('routes both download spellings and trailing slashes to the public entry without mounting administrator loading', () => {
    for (const pathname of ['/download', '/download/', '/downloads', '/downloads/']) {
      expect(isDownloadPath(pathname)).toBe(true);
      const html = renderToStaticMarkup(<Site pathname={pathname} />);
      expect(html).toContain('正在获取下载链接');
      expect(html).not.toContain('正在验证管理员身份');
    }
    for (const path of ['/', '/download/private', '/downloader']) expect(isDownloadPath(path)).toBe(false);
  });
  it('prefills administrator settings from bootstrap and supports the full documented URL limit', () => {
    const html = renderToStaticMarkup(<DownloadSettings initial={{ android_apk_url: apk }} onBusy={() => {}} />);
    expect(html).toContain('保存下载设置');
    expect(html).toContain('公开下载页 /download');
    expect(html).not.toContain('正在读取下载设置');
    const prefix = 'https://downloads.example.com/';
    const maximum = prefix + 'a'.repeat(4096 - prefix.length);
    expect(downloadSettingsSchema.parse({ android_apk_url: maximum }).android_apk_url).toHaveLength(4096);
    expect(downloadSettingsSchema.safeParse({ android_apk_url: maximum + 'a' }).success).toBe(false);
  });
});
