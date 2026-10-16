import { useEffect, useRef, useState, type ReactNode } from 'react';
import { api } from './api';
export const errorText = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试';
export function useLoad<T>(path: string, version: string | number = '') {
  const [data, setData] = useState<T>(), [error, setError] = useState(''), [loading, setLoading] = useState(true);
  const [reloadCount, setReloadCount] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true); setError(''); setData(undefined);
    api<T>(path).then(d => { if (active) setData(d); }).catch(e => { if (active) setError(errorText(e)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [path, version, reloadCount]);
  return { data, error, loading, reload: () => setReloadCount(n => n + 1) };
}
export function Message({ children, warning = false }: { children: ReactNode; warning?: boolean }) {
  return <div className={`message ${warning ? 'warning' : ''}`} role={warning ? 'alert' : 'status'}>{children}</div>;
}
export function Modal({ title, children, close }: { title: string; children: ReactNode; close: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useRef(`modal-${crypto.randomUUID()}`);
  useEffect(() => {
    const el = ref.current!;
    el.showModal();
    return () => el.close();
  }, []);
  return <dialog ref={ref} aria-labelledby={titleId.current} onCancel={e => { e.preventDefault(); close(); }}>
    <div className="modal-heading"><h2 id={titleId.current}>{title}</h2><button type="button" className="plain" onClick={close} aria-label="关闭弹窗">关闭 ×</button></div>
    {children}
  </dialog>;
}
export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="empty"><div className="empty-mark" aria-hidden="true">＋</div><h3>{title}</h3>{children && <p>{children}</p>}</div>;
}
export function Icon({ name }: { name: 'home' | 'box' | 'plan' | 'chat' | 'settings' | 'scan' | 'plus' }) {
  const paths = {
    home: 'M3 10 12 3l9 7v11h-6v-7H9v7H3Z',
    box: 'M3 6h18v15H3ZM2 3h20v4H2m7 5h6',
    plan: 'M5 4h14v18H5Zm4-2v4m6-4v4M8 11l2 2 5-5M8 17h8',
    chat: 'M3 3h18v14H9l-6 5Zm4 5h10M7 12h7',
    settings: 'M12 3v3m0 12v3M3 12h3m12 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    scan: 'M3 8V3h5m8 0h5v5M3 16v5h5m8 0h5v-5M7 7v10m4-10v10m3-10v10m3-10v10',
    plus: 'M12 4v16M4 12h16',
  };
  return <svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
}
export function Speak({ text }: { text: string }) {
  const [voice, setVoice] = useState<SpeechSynthesisVoice>();
  useEffect(() => {
    if (!('speechSynthesis' in window)) return;
    const update = () => setVoice(speechSynthesis.getVoices().find(v => /^zh/i.test(v.lang)));
    update(); speechSynthesis.addEventListener('voiceschanged', update);
    return () => { speechSynthesis.removeEventListener('voiceschanged', update); speechSynthesis.cancel(); };
  }, []);
  if (!voice) return null;
  return <button type="button" className="secondary" onClick={() => {
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text); u.voice = voice; u.lang = voice.lang; u.rate = 0.85; speechSynthesis.speak(u);
  }}>朗读药品信息</button>;
}
export function Confirm({ title, children, action, close }: { title: string; children: ReactNode; action: () => Promise<void>; close: () => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  return <Modal title={title} close={() => { if (!busy) close(); }}>
    {children}{error && <Message warning>{error}</Message>}
    <div className="actions"><button disabled={busy} onClick={() => {
      setBusy(true); action().then(close).catch(e => { setError(errorText(e)); setBusy(false); });
    }}>{busy ? '正在保存…' : '确认'}</button><button className="secondary" disabled={busy} onClick={close}>返回</button></div>
  </Modal>;
}
