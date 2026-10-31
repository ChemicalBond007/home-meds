import { useEffect, useState } from 'react';
import { api, sse } from './api';
import { Confirm, Message, Modal, errorText, useLoad } from './components';
import { Security } from './Auth';
import { AdminLogs } from './AdminLogs';
import { AISettings } from './AISettings';
import type { User, FamilyGroup } from '../shared/types';
import type { Warehouse } from './Inventory';
import { isApp, nativeCall, type ReminderStatus } from './native';
function unb64(s: string) { return Uint8Array.from(atob(s.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0)); }
export function SettingsPage({ user, family, groups, members, warehouses, changed, logout }: {
  user: User; family?: FamilyGroup; groups: FamilyGroup[]; members: User[]; warehouses: Warehouse[]; changed: () => void; logout: () => void;
}) {
  const [error, setError] = useState(''), [message, setMessage] = useState(''), [busy, setBusy] = useState(false);
  const [tab, setTab] = useState('general'), [groupEdit, setGroupEdit] = useState<Partial<FamilyGroup>>();
  const [groupName, setGroupName] = useState(''), [timezone, setTimezone] = useState('Asia/Shanghai'), [mealTimes, setMealTimes] = useState<Record<string, string>>({});
  const [warehouseName, setWarehouseName] = useState(''), [editWarehouse, setEditWarehouse] = useState<Warehouse>();
  const [resetUser, setResetUser] = useState<User>(), [temporary, setTemporary] = useState('');
  // Administration stays on the website; the app shows only everyday settings.
  const app = isApp(), manage = user.role === 'admin' && !app;
  const users = useLoad<User[]>(manage ? '/admin/users' : '/members', groups.length);
  const act = async (work: () => Promise<unknown>, success = '已保存') => {
    setBusy(true); setError(''); setMessage('');
    try { await work(); setMessage(success); changed(); users.reload(); } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  const edit = (g?: FamilyGroup) => {
    setGroupEdit(g || {}); setGroupName(g?.name || ''); setTimezone(g?.timezone || 'Asia/Shanghai');
    setMealTimes(g ? JSON.parse(g.meal_times) : { morning: '08:00', noon: '12:00', evening: '18:00', bedtime: '21:30' });
  };
  return <><div className="section-heading"><div><p className="eyebrow">MAKE YOURSELF AT HOME</p><h1>设置</h1><p className="muted">{user.username} · {user.role === 'admin' ? '全局管理员' : user.is_elderly ? '老人模式' : '家庭成员'}</p></div></div>
    <div className="segmented"><button className={tab === 'general' ? 'active' : ''} onClick={() => setTab('general')}>提醒与账号</button>{manage && <><button className={tab === 'family' ? 'active' : ''} onClick={() => setTab('family')}>家庭管理</button><button className={tab === 'ai' ? 'active' : ''} onClick={() => setTab('ai')}>AI 配置</button><button className={tab === 'logs' ? 'active' : ''} onClick={() => setTab('logs')}>使用日志</button></>}</div>
    {error && <Message warning>{error}</Message>}{message && <Message>{message}</Message>}
    {tab === 'general' && <>
      {app ? <CalendarPanel user={user} members={members} /> : <section className="panel"><h2>手机提醒</h2><p>先将应用添加到桌面，再从桌面打开并开启提醒。iPhone 需要 iOS 16.4 以上。</p><Message warning>首页清单是主要提醒。部分国内安卓手机无法接收推送；推送不代表已经服药。</Message>
        <div className="actions"><button disabled={busy} onClick={() => void act(async () => {
          const standalone = matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone;
          if (!standalone) throw new Error('请先通过浏览器菜单「添加到主屏幕 / 安装应用」，再从桌面打开。');
          if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) throw new Error('此浏览器不支持推送，请使用首页提醒。');
          const config = await api<{ configured: boolean; public_key: string }>('/push/config');
          if (!config.configured) throw new Error('管理员尚未配置 VAPID 推送密钥');
          if (await Notification.requestPermission() !== 'granted') throw new Error('未获得通知权限，请在系统设置中开启。');
          const registration = await navigator.serviceWorker.getRegistration();
          if (!registration?.active) throw new Error('提醒服务尚未就绪，请联网刷新页面后再试。');
          const sub = await registration.pushManager.getSubscription() || await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: unb64(config.public_key) });
          await api('/push/subscribe', 'POST', sub.toJSON());
        }, '提醒已开启。请保持系统通知权限。')}>开启提醒</button>
          <button className="secondary" disabled={busy} onClick={() => void act(async () => {
            if (!('serviceWorker' in navigator)) throw new Error('此浏览器不支持推送');
            const r = await navigator.serviceWorker.getRegistration(), s = await r?.pushManager?.getSubscription();
            if (s) { await api('/push/unsubscribe', 'POST', { endpoint: s.endpoint }); await s.unsubscribe(); }
          }, '此设备提醒已关闭')}>关闭此设备提醒</button></div>
      </section>}
      {manage && <Security done={changed} />}
      {app && user.role === 'admin' && <Message>家庭管理、AI 配置、使用日志和管理员安全设置只在网页版提供。</Message>}
      <section className="panel"><h2>当前家庭</h2><p>{family?.name || '还没有选择家庭'}{family ? ` · 时区 ${family.timezone}` : ''}</p><p>普通成员仅凭用户名登录，请不要在公共设备保持登录。老人身份由管理员管理。</p><button className="secondary" onClick={() => void act(async () => {
        if (app) await nativeCall('logout');
        else if ('serviceWorker' in navigator) {
          const reg = await navigator.serviceWorker.getRegistration();
          const sub = await reg?.pushManager?.getSubscription();
          if (sub) { await api('/push/unsubscribe', 'POST', { endpoint: sub.endpoint }); await sub.unsubscribe(); }
        }
        await api('/auth/logout', 'POST', {}); logout();
      })}>退出登录</button></section>
    </>}
    {tab === 'ai' && manage && <AISettings><TestLookup /></AISettings>}
    {tab === 'logs' && manage && <AdminLogs groups={groups} users={users.data || []} usersError={users.error} />}
    {tab === 'family' && manage && <>
      <section className="panel"><div className="between"><h2>群组与分组码</h2><button onClick={() => edit()}>新建群组</button></div>
        {groups.map(g => <div className="management-row" key={g.id}><div><h3>{g.name}</h3><p>{g.timezone} · 分组码 <code>{g.join_code}</code></p></div><div className="actions"><button className="secondary" onClick={() => edit(g)}>编辑</button><button disabled={busy} className="plain" onClick={() => void act(() => api(`/groups/${g.id}/rotate-code`, 'POST', {}), '分组码已更新，旧码已失效')}>换分组码</button></div></div>)}
      </section>
      <section className="panel"><h2>当前群组成员</h2>{members.map(m => <div className="management-row" key={m.id}><strong>{m.username}</strong><label className="check"><input type="checkbox" disabled={busy} checked={!!m.is_elderly} onChange={e => void act(() => api(`/members/${m.id}`, 'PATCH', { is_elderly: e.target.checked, group_id: m.group_id }))} />老人模式</label></div>)}</section>
      <section className="panel"><h2>成员所属群组</h2><p>已有服药计划的成员不能直接跨群组移动，以保留记录归属。</p>
        {users.data?.map(m => <div className="management-row" key={m.id}><strong>{m.username}{m.role === 'admin' ? '（管理员）' : ''}</strong>
          <label>所属群组<select aria-label={`${m.username}所属群组`} disabled={busy} value={m.group_id || ''} onChange={e => void act(() => api(`/members/${m.id}`, 'PATCH', { is_elderly: !!m.is_elderly, group_id: e.target.value || null }))}>
            <option value="">未分组</option>{groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select></label></div>)}
      </section>
      <section className="panel"><h2>当前群组的药箱</h2>{warehouses.map(w => <div className="management-row" key={w.id}><strong>{w.name}</strong><button className="secondary" onClick={() => { setEditWarehouse(w); setWarehouseName(w.name); }}>改名</button></div>)}
        <form onSubmit={e => { e.preventDefault(); void act(() => api(editWarehouse ? `/warehouses/${editWarehouse.id}` : '/warehouses', editWarehouse ? 'PUT' : 'POST', { name: warehouseName }).then(() => { setWarehouseName(''); setEditWarehouse(undefined); })); }}>
          <label>{editWarehouse ? '修改药箱名称' : '新药箱名称'}<input value={warehouseName} maxLength={100} required onChange={e => setWarehouseName(e.target.value)} /></label><button disabled={busy || !family}>{editWarehouse ? '保存名称' : '创建药箱'}</button>
        </form></section>
      <section className="panel"><h2>重置其他管理员</h2>{users.data?.filter(u => u.role === 'admin' && u.id !== user.id).map(u => <div className="management-row" key={u.id}><strong>{u.username}</strong><button className="secondary" onClick={() => setResetUser(u)}>重置身份验证</button></div>)}</section>
    </>}
    {groupEdit && <Modal title={groupEdit.id ? '编辑群组' : '新建群组'} close={() => { if (!busy) setGroupEdit(undefined); }}>
      <form onSubmit={e => { e.preventDefault(); void act(async () => {
        await api(groupEdit.id ? `/groups/${groupEdit.id}` : '/groups', groupEdit.id ? 'PUT' : 'POST', { name: groupName, timezone, meal_times: mealTimes });
        setGroupEdit(undefined);
      }); }}><label>家庭名称<input value={groupName} maxLength={100} required onChange={e => setGroupName(e.target.value)} /></label>
        <label>时区<input value={timezone} required onChange={e => setTimezone(e.target.value)} placeholder="Asia/Shanghai" /></label>
        {Object.entries(mealTimes).map(([key, value]) => <label key={key}>{({ morning: '早', noon: '中', evening: '晚', bedtime: '睡前' } as Record<string, string>)[key]}的默认时间<input type="time" value={value} required onChange={e => setMealTimes(prev => ({ ...prev, [key]: e.target.value }))} /></label>)}
        {error && <Message warning>{error}</Message>}<button disabled={busy}>确认保存群组</button></form>
    </Modal>}
    {resetUser && <Confirm title={`重置 ${resetUser.username}？`} close={() => setResetUser(undefined)} action={async () => {
      const r = await api<{ temporary_password: string }>(`/admin/users/${resetUser.id}/reset`, 'POST', {}); setTemporary(r.temporary_password);
    }}><Message warning>会撤销此管理员的所有会话、通行密钥、验证器和旧恢复码。确认后安全转交新的临时密码。</Message></Confirm>}
    {temporary && <Modal title="请安全转交临时密码" close={() => setTemporary('')}><p>仅显示在这里，7 天有效。对方必须重新绑定验证方式。</p><code className="secret">{temporary}</code><button onClick={() => setTemporary('')}>已安全保存，关闭</button></Modal>}
  </>;
}
function TestLookup() {
  const [query, setQuery] = useState(''), [result, setResult] = useState<unknown>(), [progress, setProgress] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState('');
  return <form onSubmit={e => {
    e.preventDefault(); setBusy(true); setError(''); setResult(undefined);
    sse('/ai/lookup', { query }, (event, data) => {
      if (event === 'progress') setProgress(String(data));
      if (event === 'result') setResult(data);
    }).catch(e => setError(errorText(e))).finally(() => setBusy(false));
  }}><h3>测试药品查询（不保存）</h3><label>药名或条码<input value={query} required maxLength={500} onChange={e => setQuery(e.target.value)} /></label><button className="secondary" disabled={busy}>{busy ? '正在查询…' : '测试查询'}</button>
    {progress && <Message>{progress}</Message>}{error && <Message warning>{error}</Message>}{result !== undefined && <pre className="probe-result">{JSON.stringify(result, null, 2)}</pre>}</form>;
}

function CalendarPanel({ user, members }: { user: User; members: User[] }) {
  const [status, setStatus] = useState<ReminderStatus>(), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const run = (method: string, params?: unknown) => {
    setBusy(true); setError('');
    nativeCall<ReminderStatus>(method, params).then(setStatus).catch(e => setError(errorText(e))).finally(() => setBusy(false));
  };
  useEffect(() => { run('calendar.status'); }, []);
  const configure = (patch: Partial<ReminderStatus>) => status && run('calendar.configure', {
    members: patch.members ?? status.members, calendar_alerts: patch.calendar_alerts ?? status.calendar_alerts,
    app_notifications: patch.app_notifications ?? status.app_notifications,
  });
  const selected = new Set(status?.members || []);
  return <section className="panel"><h2>日历与提醒</h2>
    <p>选中的家人的吃药计划会写入手机日历「家药箱」，并在服药时间提醒。点通知会打开确认服药，<strong>提醒不代表已经服药</strong>。</p>
    {error && <Message warning>{error}</Message>}
    {!status ? <Message>正在读取手机提醒设置…</Message> : <>
      {(!status.calendar_permission || !status.notification_permission) && <Message warning>需要日历和通知权限才能写入日历和发送提醒。<button className="secondary" disabled={busy} onClick={() => run('calendar.requestPermissions')}>授权日历和通知</button></Message>}
      {!status.exact_alarm && status.app_notifications && <Message warning>系统未允许准时闹钟，提醒可能推迟几分钟。<button className="secondary" disabled={busy} onClick={() => run('calendar.openExactAlarmSettings')}>去允许准时提醒</button></Message>}
      {status.battery_optimized && <Message>部分国产手机会在后台关闭应用。如果提醒不准时，请在系统设置中允许家药箱自启动并关闭电池优化。<button className="secondary" disabled={busy} onClick={() => run('calendar.openBatterySettings')}>打开电池设置</button></Message>}
      <fieldset><legend>同步哪些家人的计划</legend>{members.map(m => <label className="check" key={m.id}><input type="checkbox" disabled={busy} checked={selected.has(m.id)}
        onChange={e => configure({ members: e.target.checked ? [...selected, m.id] : [...selected].filter(id => id !== m.id) })} />{m.username}{m.id === user.id ? '（我）' : ''}</label>)}</fieldset>
      <label className="check"><input type="checkbox" disabled={busy} checked={status.calendar_alerts} onChange={e => configure({ calendar_alerts: e.target.checked })} />日历事件也响铃提醒</label>
      <label className="check"><input type="checkbox" disabled={busy} checked={status.app_notifications} onChange={e => configure({ app_notifications: e.target.checked })} />应用通知（到点提醒本人；家人超过 30 分钟未记录时提醒我）</label>
      {user.is_elderly ? <p className="fine">老人模式不接收家人未服药提醒。</p> : null}
      <p className="fine">日历中有 {status.events} 个重复事件，已安排 {status.alarms} 个提醒。{status.last_sync ? `上次同步 ${new Date(status.last_sync).toLocaleString('zh-CN')}` : '尚未同步'}{status.last_error ? `；${status.last_error}` : ''}</p>
      <button className="secondary" disabled={busy} onClick={() => run('calendar.sync')}>{busy ? '正在处理…' : '立即同步'}</button>
    </>}
  </section>;
}
