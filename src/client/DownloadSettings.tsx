import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import { Message, errorText, useLoad } from './components';
import { downloadSettingsSchema, type DownloadSettings as Settings } from '../shared/download';

export function DownloadSettings({ initial, onBusy, onSaved }: {
  initial?: Settings; onBusy: (value: boolean) => void; onSaved?: (settings: Settings) => void;
}) {
  const load = useLoad<Settings>('/admin/download', '', initial);
  const [url, setUrl] = useState(initial?.android_apk_url || '');
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const saving = useRef(false);
  useEffect(() => { if (load.data) setUrl(load.data.android_apk_url); }, [load.data]);
  useEffect(() => { onBusy(busy); return () => onBusy(false); }, [busy, onBusy]);
  return <section className="panel"><h2>安卓版 APK 下载链接</h2>
    <p>访客无需登录即可访问 <a href="/download" target="_blank" rel="noreferrer">公开下载页 /download</a>。保存后，页面的下载按钮使用此链接。</p>
    {!load.data ? <><Message warning={!!load.error}>{load.error || '正在读取下载设置…'}</Message>
      {load.error && <button className="secondary" onClick={load.reload}>重试</button>}</>
      : <form onSubmit={async e => {
        e.preventDefault();
        if (saving.current) return;
        const parsed = downloadSettingsSchema.safeParse({ android_apk_url: url });
        if (!parsed.success) { setError(parsed.error.issues[0].message); setMessage(''); return; }
        saving.current = true; setBusy(true); setError(''); setMessage('');
        try {
          const saved = await api<Settings>('/admin/download', 'PUT', parsed.data);
          setUrl(saved.android_apk_url);
          setMessage(saved.android_apk_url ? '下载链接已保存，访客下载页已更新。' : '下载已关闭，访客页面将提示暂未开放下载。');
          onSaved?.(saved);
        } catch (e) { setError(errorText(e)); }
        finally { saving.current = false; setBusy(false); }
      }}>
        <label>APK 下载链接<input type="url" inputMode="url" autoComplete="off" maxLength={4096} value={url} disabled={busy}
          placeholder="https://downloads.example.com/app.apk" onChange={e => { setUrl(e.target.value); setError(''); setMessage(''); }} /></label>
        <p className="fine">仅支持公开 HTTPS 链接，可包含下载参数，无须以 .apk 结尾。链接会公开展示，请勿填写私密凭据；留空并保存可关闭下载。安装包由链接对应的平台托管，本站不会上传或转存 APK。</p>
        {error && <Message warning>{error}</Message>}{message && <Message>{message}</Message>}
        <button disabled={busy}>{busy ? '正在保存…' : '保存下载设置'}</button>
      </form>}
  </section>;
}
