import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { api } from './api';
export const errorText = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试';
export function useLoad<T>(path: string, version: string | number = '', initial?: T) {
  const [data, setData] = useState<T | undefined>(initial), [error, setError] = useState(''), [loading, setLoading] = useState(initial === undefined);
  const firstVersion = useRef(version);
  const [reloadCount, setReloadCount] = useState(0);
  useEffect(() => {
    let active = true;
    if (initial !== undefined && version === firstVersion.current && reloadCount === 0) {
      setData(initial); setLoading(false); setError(''); return;
    }
    setLoading(true); setError(''); setData(undefined);
    api<T>(path).then(d => { if (active) setData(d); }).catch(e => { if (active) setError(errorText(e)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [path, version, reloadCount, initial]);
  return { data, error, loading, reload: () => setReloadCount(n => n + 1) };
}
export function Message({ children, warning = false }: { children: ReactNode; warning?: boolean }) {
  return <div className={`message ${warning ? 'warning' : ''}`} role={warning ? 'alert' : 'status'}>{children}</div>;
}
export function Modal({ title, children, close }: { title: string; children: ReactNode; close: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const el = ref.current!;
    el.showModal();
    return () => el.close();
  }, []);
  return <dialog ref={ref} aria-labelledby={titleId} onCancel={e => { e.preventDefault(); close(); }}>
    <div className="modal-heading"><h2 id={titleId}>{title}</h2><button type="button" className="plain" onClick={close} aria-label="关闭弹窗">关闭 ×</button></div>
    {children}
  </dialog>;
}
export function Icon({ name }: { name: 'home' | 'box' | 'chat' | 'settings' }) {
  const paths = {
    home: 'M3 10 12 3l9 7v11h-6v-7H9v7H3Z',
    box: 'M3 6h18v15H3ZM2 3h20v4H2m7 5h6',
    chat: 'M3 3h18v14H9l-6 5Zm4 5h10M7 12h7',
    settings: 'M12 3v3m0 12v3M3 12h3m12 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  };
  return <svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>;
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
