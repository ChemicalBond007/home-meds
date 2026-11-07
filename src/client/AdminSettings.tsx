import { useRef, useState } from 'react';
import { api } from './api';
import { AISettings } from './AISettings';
import { Security } from './Auth';
import { GroupJoinQr } from './GroupJoinQr';
import { DownloadSettings } from './DownloadSettings';
import { Confirm, Message, Modal, errorText } from './components';
import type { AdminBootstrap } from '../shared/bootstrap';
import type { FamilyGroup, User } from '../shared/types';

export const adminSections = [
  { id: 'account', title: '账号安全', icon: 'settings', note: '验证器与恢复码', description: '管理管理员身份验证与账号恢复方式。' },
  { id: 'family', title: '家庭设置', icon: 'home', note: '家庭、成员与药箱', description: '集中配置家庭、成员权限、默认时间与药箱位置。' },
  { id: 'ai', title: 'AI 配置', icon: 'chat', note: '模型与搜索连接', description: '配置手机助手使用的模型、搜索来源与调用限制。' },
  { id: 'download', title: '应用下载', icon: 'box', note: '公开下载页与 APK 链接', description: '配置未登录访客可访问的安卓版下载入口。' },
] as const;
export type AdminSection = typeof adminSections[number]['id'];
type Mutate = <T = Record<string, unknown>>(path: string, method: string, body: object) => Promise<T>;

export function AdminSettings({ section, data, changed, onBusy }: {
  section: AdminSection; data: AdminBootstrap; changed: (next?: AdminBootstrap) => void; onBusy: (value: boolean) => void;
}) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
  const saving = useRef(false);
  const [editing, setEditing] = useState<Partial<FamilyGroup>>(), [name, setName] = useState(''), [zone, setZone] = useState('Asia/Shanghai');
  const [meals, setMeals] = useState<Record<string, string>>({});
  const [warehouse, setWarehouse] = useState(''), [warehouseId, setWarehouseId] = useState('');
  const [reset, setReset] = useState<User>(), [temporary, setTemporary] = useState('');
  const [qrGroupId, setQrGroupId] = useState<string>();
  const qrGroup = data.groups.find(g => g.id === qrGroupId);
  const family = data.groups.find(g => g.id === data.group_id);
  const save = async (work: (mutate: Mutate) => Promise<unknown>, success = '设置已保存') => {
    if (saving.current) return;
    saving.current = true; setBusy(true); onBusy(true); setError(''); setMessage('');
    let snapshot: AdminBootstrap | undefined;
    let refreshError = '';
    const mutate: Mutate = async <T,>(path: string, method: string, body: object) => {
      const result = await api<T & { admin_state?: AdminBootstrap; state_error?: string }>(path, method, body, { state: 'admin' });
      snapshot = result.admin_state;
      refreshError = result.state_error || '';
      return result;
    };
    try { await work(mutate); setMessage(refreshError || success); changed(snapshot); }
    catch (e) { setError(errorText(e)); }
    finally { saving.current = false; setBusy(false); onBusy(false); }
  };
  const edit = (g?: FamilyGroup) => {
    setEditing(g || {}); setName(g?.name || ''); setZone(g?.timezone || 'Asia/Shanghai');
    setMeals(g ? JSON.parse(g.meal_times) : { morning: '08:00', noon: '12:00', evening: '18:00', bedtime: '21:30' });
  };
  if (section === 'account') return <><section className="admin-note"><strong>账户安全与设备使用分离</strong>
    <p>验证器、通行密钥和恢复码在此管理；手机提醒与日历同步在 APK 设置中配置。</p></section><Security initialStatus={data.security}
      passkeyAdmins={data.passkey_admins} currentUserId={data.user.id} onBusy={onBusy} done={() => changed()} /></>;
  if (section === 'ai') return <AISettings initial={data.ai} onBusy={onBusy} />;
  if (section === 'download') return <DownloadSettings initial={data.download} onBusy={onBusy}
    onSaved={settings => changed({ ...data, download: settings })} />;
  return <>{error && <Message warning>{error}</Message>}{message && <Message>{message}</Message>}
    <section className="panel"><div className="between"><div><h2>家庭与分组码</h2><p className="muted">共 {data.groups.length} 个家庭。分组码用于手机端注册。</p></div>
      <button disabled={busy} onClick={() => edit()}>新建家庭</button></div>
      {!data.groups.length && <p>请先创建一个家庭，再配置成员和药箱。</p>}
      {data.groups.map(g => <div className="management-row" key={g.id}><div><h3>{g.name}</h3><p>{g.timezone} · 分组码 <code>{g.join_code}</code></p></div>
        <div className="actions"><button className="secondary" disabled={busy || !g.join_code} onClick={() => setQrGroupId(g.id)}>显示分组码二维码</button>
          <button className="secondary" disabled={busy} onClick={() => edit(g)}>编辑设置</button>
          <button className="plain" disabled={busy} onClick={() => void save(m => m(`/groups/${g.id}/rotate-code`, 'POST', {}), '分组码已更新，旧码已失效')}>更换分组码</button></div></div>)}
    </section>
    <div className="admin-columns"><section className="panel"><h2>成员与所属家庭</h2><p className="fine">已有服药计划的成员不能跨家庭移动，以保留记录归属。</p>
      {data.users.map(u => <div className="admin-member" key={u.id}><strong>{u.username}{u.role === 'admin' ? ' · 管理员' : ''}</strong>
        <label>所属家庭<select aria-label={`${u.username}所属家庭`} value={u.group_id || ''} disabled={busy} onChange={e => void save(m => m(`/members/${u.id}`, 'PATCH', { group_id: e.target.value || null, is_elderly: !!u.is_elderly }))}>
          <option value="">未分组</option>{data.groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label>
        <label className="check"><input type="checkbox" disabled={busy} checked={!!u.is_elderly} onChange={e => void save(m => m(`/members/${u.id}`, 'PATCH', { group_id: u.group_id, is_elderly: e.target.checked }))} />老人模式</label></div>)}
    </section><div><section className="panel"><h2>{family?.name || '当前家庭'}的药箱</h2>
      {data.warehouses.map(w => <div className="management-row" key={w.id}><strong>{w.name}</strong><button className="secondary" disabled={busy} onClick={() => { setWarehouse(w.name); setWarehouseId(w.id); }}>改名</button></div>)}
      <form onSubmit={e => { e.preventDefault(); void save(async m => { await m(warehouseId ? `/warehouses/${warehouseId}` : '/warehouses', warehouseId ? 'PUT' : 'POST', { name: warehouse }); setWarehouse(''); setWarehouseId(''); }); }}>
        <label>{warehouseId ? '药箱新名称' : '新药箱名称'}<input value={warehouse} maxLength={100} required disabled={busy || !family} onChange={e => setWarehouse(e.target.value)} /></label>
        <button disabled={busy || !family}>{warehouseId ? '保存名称' : '创建药箱'}</button>
        {warehouseId && <button className="plain" type="button" disabled={busy} onClick={() => { setWarehouseId(''); setWarehouse(''); }}>取消改名</button>}</form>
    </section><section className="panel"><h2>管理员身份重置</h2><p className="fine">仅用于协助其他管理员恢复访问。操作会撤销其现有凭证和会话。</p>
      {data.users.filter(u => u.role === 'admin' && u.id !== data.user.id).map(u => <div className="management-row" key={u.id}><strong>{u.username}</strong>
        <button className="secondary" disabled={busy} onClick={() => setReset(u)}>重置身份验证</button></div>)}
    </section></div></div>
    {qrGroup?.join_code && <Modal title={`${qrGroup.name} · 分组码二维码`} close={() => setQrGroupId(undefined)}>
      <GroupJoinQr name={qrGroup.name} code={qrGroup.join_code} />
    </Modal>}
    {editing && <Modal title={editing.id ? '编辑家庭设置' : '新建家庭'} close={() => { if (!busy) setEditing(undefined); }}>
      <form onSubmit={e => { e.preventDefault(); void save(async m => { await m(editing.id ? `/groups/${editing.id}` : '/groups', editing.id ? 'PUT' : 'POST', { name, timezone: zone, meal_times: meals }); setEditing(undefined); }); }}>
        <label>家庭名称<input value={name} required maxLength={100} disabled={busy} onChange={e => setName(e.target.value)} /></label>
        <label>时区<input value={zone} required disabled={busy} onChange={e => setZone(e.target.value)} /></label>
        <div className="form-row">{Object.entries(meals).map(([key, value]) => <label key={key}>{({ morning: '早晨', noon: '中午', evening: '晚上', bedtime: '睡前' } as Record<string, string>)[key]}默认时间
          <input type="time" value={value} required disabled={busy} onChange={e => setMeals(old => ({ ...old, [key]: e.target.value }))} /></label>)}</div>
        {error && <Message warning>{error}</Message>}<button disabled={busy}>确认保存</button></form>
    </Modal>}
    {reset && <Confirm title={`重置 ${reset.username} 的身份验证？`} close={() => setReset(undefined)} action={async () => {
      const result = await api<{ temporary_password: string }>(`/admin/users/${reset.id}/reset`, 'POST', {});
      setTemporary(result.temporary_password);
    }}><Message warning>所有会话、通行密钥、验证器和旧恢复码都会失效。请安全转交新临时密码。</Message></Confirm>}
    {temporary && <Modal title="请安全转交临时密码" close={() => setTemporary('')}><p>仅此处显示，7 天内有效。对方必须重新绑定身份验证。</p><code className="secret">{temporary}</code>
      <button onClick={() => setTemporary('')}>已安全保存，关闭</button></Modal>}
  </>;
}
