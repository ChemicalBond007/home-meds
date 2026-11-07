import { useEffect } from 'react';
import { downloadSettingsSchema } from '../shared/download';
import { useLoad } from './components';

export function DownloadPage() {
  const load = useLoad<unknown>('/download');
  useEffect(() => { document.title = '下载赛博药箱 · Android'; }, []);
  return <DownloadView data={load.data} loading={load.loading} error={load.error} retry={load.reload} />;
}
export function DownloadView({ data, loading = false, error = '', retry }: {
  data?: unknown; loading?: boolean; error?: string; retry?: () => void;
}) {
  const parsed = downloadSettingsSchema.safeParse(data);
  const failed = !!error || (!loading && data !== undefined && !parsed.success);
  const url = !failed && parsed.success ? parsed.data.android_apk_url : '';
  return <main className="download-page"><section className="download-card" aria-labelledby="download-title">
    <img className="download-icon" src="/icon.svg" alt="" width="72" height="72" />
    <p className="download-label">ANDROID 应用</p>
    <h1 id="download-title">赛博药箱</h1><p className="download-intro">把家人的用药，记在一起。</p>
    {loading ? <button className="download-button" disabled aria-busy="true">正在获取下载链接…</button>
      : failed ? <><p role="alert">下载信息暂时无法加载，请稍后重试。</p><button className="download-button" onClick={retry}>重新加载</button></>
      : url ? <a className="download-button" href={url} rel="noreferrer" referrerPolicy="no-referrer">下载安卓版 APK</a>
      : <><button className="download-button" disabled>暂未开放下载</button><p className="download-note" role="status">管理员尚未配置下载链接，请稍后再试。</p></>}
    <p className="download-meta">Android 10 及以上 · APK 安装包</p>
    <p className="download-note">首次使用请向家庭管理员索取分组码。</p>
  </section><p className="download-footer">仅帮助记录家庭用药，不代替医疗建议。</p></main>;
}
