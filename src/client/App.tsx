import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, selectGroup, selectedGroup } from './api';
import { Login, Security } from './Auth';
import { Message, errorText, Icon } from './components';
import { AdminSettings, adminSections, type AdminSection } from './AdminSettings';
import type { AdminBootstrap } from '../shared/bootstrap';
import { isDownloadPath } from '../shared/download';
import { DownloadPage } from './DownloadPage';

export function Site({ pathname = window.location.pathname }: { pathname?: string }) {
  return isDownloadPath(pathname) ? <DownloadPage /> : <App />;
}

export function App() {
  const [data, setData] = useState<AdminBootstrap>(), [loading, setLoading] = useState(true);
  const [status, setStatus] = useState(0), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const generation = useRef(0);
  const load = useCallback(async () => {
    const epoch = ++generation.current;
    setLoading(true); setError('');
    try {
      const next = await api<AdminBootstrap>('/admin/bootstrap');
      if (epoch !== generation.current) return;
      selectGroup(next.group_id || '');
      setData(next); setStatus(200);
    } catch (e) {
      if (epoch !== generation.current) return;
      setStatus(e instanceof ApiError ? e.status : 0); setError(errorText(e)); setData(undefined);
    } finally { if (epoch === generation.current) setLoading(false); }
  }, []);
  useEffect(() => { void load(); return () => { generation.current++; }; }, [load]);
  const logout = async () => {
    setBusy(true); setError('');
    try {
      await api('/auth/logout', 'POST', {});
      generation.current++; selectGroup(''); setData(undefined); setStatus(401);
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  if (loading) return <main className="loading"><img src="/icon.svg" alt="" /><h1>赛博药箱管理台</h1><p role="status">正在验证管理员身份…</p></main>;
  if (status === 401) return <Login done={() => void load()} />;
  if (!data || data.user.role !== 'admin') return <main className="binding-page"><div className="brand"><img src="/icon.svg" alt="" />赛博药箱</div>
    <section className="panel"><h1>{status === 403 ? '此入口仅供管理员使用' : '管理台暂时无法打开'}</h1>
      <p>{status === 403 ? '普通家庭成员请使用手机应用。网页版仅提供管理员设置。' : error || '请检查网络后重试。'}</p>
      <div className="actions"><button onClick={() => void load()}>重新验证</button>
        <button className="secondary" disabled={busy} onClick={() => void logout()}>退出并切换账号</button></div></section></main>;
  if (data.binding_only) return <main className="binding-page"><div className="brand"><img src="/icon.svg" alt="" />赛博药箱管理台</div>
    <Security bindingOnly onBusy={setBusy} done={() => void load()} /><button className="secondary" disabled={busy} onClick={() => void logout()}>退出登录</button></main>;
  return <AdminWorkspace data={data} busy={busy} error={error} logout={logout} select={id => { selectGroup(id); void load(); }}
    changed={next => {
      if (next) { generation.current++; selectGroup(next.group_id || ''); setData(next); setLoading(false); }
      else void load();
    }} setBusy={setBusy} />;
}

export function AdminWorkspace({ data, busy, error, logout, select, changed, setBusy }: {
  data: AdminBootstrap; busy: boolean; error: string; logout: () => Promise<void>; select: (id: string) => void;
  changed: (next?: AdminBootstrap) => void; setBusy: (busy: boolean) => void;
}) {
  const [section, setSection] = useState<AdminSection>('account');
  const family = data.groups.find(g => g.id === data.group_id);
  const current = adminSections.find(s => s.id === section)!;
  useEffect(() => {
    document.title = `${current.title} · 赛博药箱管理台`;
    window.scrollTo({ top: 0, behavior: 'auto' });
    document.getElementById('main')?.focus({ preventScroll: true });
  }, [current.title]);
  return <div className="admin-shell"><a className="skip-link" href="#main">跳到主要内容</a>
    <aside className="admin-sidebar"><div className="brand"><img src="/icon.svg" alt="" /><span>赛博药箱<small>管理员控制台</small></span></div>
      <nav aria-label="管理员设置">{adminSections.map(item => <button className={`admin-nav ${section === item.id ? 'active' : ''}`}
        key={item.id} aria-current={section === item.id ? 'page' : undefined} disabled={busy} onClick={() => setSection(item.id)}>
        <Icon name={item.icon} /><span>{item.title}<small>{item.note}</small></span></button>)}</nav>
      <div className="admin-identity"><span className="avatar">{data.user.username.slice(0, 1)}</span><span><strong>{data.user.username}</strong><small>全局管理员</small></span></div>
    </aside>
    <div className="admin-workspace"><header className="admin-topbar"><span className="badge">管理员专用</span>
      <label className="admin-group">当前家庭<select aria-label="当前家庭" disabled={busy || !data.groups.length} value={data.group_id || ''} onChange={e => select(e.target.value)}>
        {!data.groups.length && <option value="">尚无家庭</option>}{data.groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
      <button className="secondary" disabled={busy} onClick={() => void logout()}>退出登录</button></header>
      <main id="main" tabIndex={-1}><div className="admin-heading"><div><p className="eyebrow">赛博药箱 / 设置中心</p><h1>{current.title}</h1>
        <p className="muted">{current.description}</p></div><span className="badge neutral">{family?.name || '全局设置'}</span></div>
        {error && <Message warning>{error}</Message>}
        <AdminSettings key={data.group_id || 'global'} section={section} data={data} changed={next => {
          if ((data.group_id || '') === selectedGroup) changed(next);
        }} onBusy={setBusy} />
      </main><footer>日常用药、药品入库和计划记录请使用手机应用。此管理台不提供服药操作。</footer>
    </div>
  </div>;
}
