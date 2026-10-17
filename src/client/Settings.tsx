import { useState } from 'react';
import { api, sse } from './api';
import { Confirm, Message, Modal, errorText, useLoad } from './components';
import { Security } from './Auth';
import { AdminLogs } from './AdminLogs';
import type { User, FamilyGroup } from '../shared/types';
import type { Warehouse } from './Inventory';
interface AIConfig {
  base_url: string; model: string; extra_body: Record<string, unknown>; query_mode: string; output_mode: string;
  duckduckgo_enabled: boolean; lookup_daily_limit: number; chat_daily_limit: number;
  max_llm_calls: number; max_tool_calls: number; max_tokens: number; timeout_seconds: number;
}
function unb64(s: string) { return Uint8Array.from(atob(s.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0)); }
export function SettingsPage({ user, family, groups, members, warehouses, changed, logout }: {
  user: User; family?: FamilyGroup; groups: FamilyGroup[]; members: User[]; warehouses: Warehouse[]; changed: () => void; logout: () => void;
}) {
  const [error, setError] = useState(''), [message, setMessage] = useState(''), [busy, setBusy] = useState(false);
  const [tab, setTab] = useState('general'), [groupEdit, setGroupEdit] = useState<Partial<FamilyGroup>>();
  const [groupName, setGroupName] = useState(''), [timezone, setTimezone] = useState('Asia/Shanghai'), [mealTimes, setMealTimes] = useState<Record<string, string>>({});
  const [warehouseName, setWarehouseName] = useState(''), [editWarehouse, setEditWarehouse] = useState<Warehouse>();
  const [resetUser, setResetUser] = useState<User>(), [temporary, setTemporary] = useState('');
  const users = useLoad<User[]>(user.role === 'admin' ? '/admin/users' : '/members', groups.length);
  const act = async (work: () => Promise<unknown>, success = '已保存') => {
    setBusy(true); setError(''); setMessage('');
    try { await work(); setMessage(success); changed(); users.reload(); } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  const edit = (g?: FamilyGroup) => {
    setGroupEdit(g || {}); setGroupName(g?.name || ''); setTimezone(g?.timezone || 'Asia/Shanghai');
    setMealTimes(g ? JSON.parse(g.meal_times) : { morning: '08:00', noon: '12:00', evening: '18:00', bedtime: '21:30' });
  };
  return <><div className="section-heading"><div><p className="eyebrow">MAKE YOURSELF AT HOME</p><h1>设置</h1><p className="muted">{user.username} · {user.role === 'admin' ? '全局管理员' : user.is_elderly ? '老人模式' : '家庭成员'}</p></div></div>
    <div className="segmented"><button className={tab === 'general' ? 'active' : ''} onClick={() => setTab('general')}>提醒与账号</button>{user.role === 'admin' && <><button className={tab === 'family' ? 'active' : ''} onClick={() => setTab('family')}>家庭管理</button><button className={tab === 'ai' ? 'active' : ''} onClick={() => setTab('ai')}>AI 配置</button><button className={tab === 'logs' ? 'active' : ''} onClick={() => setTab('logs')}>使用日志</button></>}</div>
    {error && <Message warning>{error}</Message>}{message && <Message>{message}</Message>}
    {tab === 'general' && <>
      <section className="panel"><h2>手机提醒</h2><p>先将应用添加到桌面，再从桌面打开并开启提醒。iPhone 需要 iOS 16.4 以上。</p><Message warning>首页清单是主要提醒。部分国内安卓手机无法接收推送；推送不代表已经服药。</Message>
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
      </section>
      {user.role === 'admin' && <Security done={changed} />}
      <section className="panel"><h2>当前家庭</h2><p>{family?.name || '还没有选择家庭'}{family ? ` · 时区 ${family.timezone}` : ''}</p><p>普通成员仅凭用户名登录，请不要在公共设备保持登录。老人身份由管理员管理。</p><button className="secondary" onClick={() => void act(async () => {
        if ('serviceWorker' in navigator) {
          const reg = await navigator.serviceWorker.getRegistration();
          const sub = await reg?.pushManager?.getSubscription();
          if (sub) { await api('/push/unsubscribe', 'POST', { endpoint: sub.endpoint }); await sub.unsubscribe(); }
        }
        await api('/auth/logout', 'POST', {}); logout();
      })}>退出登录</button></section>
    </>}
    {tab === 'ai' && user.role === 'admin' && <AISettings />}
    {tab === 'logs' && user.role === 'admin' && <AdminLogs groups={groups} users={users.data || []} usersError={users.error} />}
    {tab === 'family' && user.role === 'admin' && <>
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
function AISettings() {
  const load = useLoad<{ settings: AIConfig; has_llm_key: boolean; has_tavily_key: boolean }>('/ai/settings');
  const [d, setD] = useState<AIConfig>(), [extra, setExtra] = useState(''), [llmKey, setLlmKey] = useState(''), [searchKey, setSearchKey] = useState('');
  const [message, setMessage] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [probe, setProbe] = useState<unknown>();
  const config = d || load.data?.settings;
  if (!config) return <Message warning={!!load.error}>{load.error || '正在读取配置…'}</Message>;
  const patch = (p: Partial<AIConfig>) => setD({ ...config, ...p });
  return <section className="panel"><h2>AI 与搜索配置</h2><p>正式密钥只会加密保存到 D1，不会回传。留空表示保留已有密钥。</p>
    <form onSubmit={e => {
      e.preventDefault(); setBusy(true); setError(''); setMessage('');
      (async () => {
        const settings = { ...config, extra_body: extra ? JSON.parse(extra) : config.extra_body };
        await api('/ai/settings', 'PUT', { settings, llm_key: llmKey || undefined, tavily_key: searchKey || undefined });
        setLlmKey(''); setSearchKey(''); setMessage('配置已保存'); load.reload();
      })().catch(e => setError(errorText(e))).finally(() => setBusy(false));
    }}>
      <label>OpenAI 兼容接口根地址<input type="url" value={config.base_url} required onChange={e => patch({ base_url: e.target.value })} /></label>
      <label>模型<input value={config.model} required maxLength={100} onChange={e => patch({ model: e.target.value })} /></label>
      <label>LLM 密钥（{load.data?.has_llm_key ? '已设置' : '未设置'}）<input type="password" value={llmKey} onChange={e => setLlmKey(e.target.value)} autoComplete="new-password" /></label>
      <label>Tavily 密钥，药品查询必需（{load.data?.has_tavily_key ? '已设置' : '未设置'}）<input type="password" value={searchKey} onChange={e => setSearchKey(e.target.value)} autoComplete="new-password" /></label>
      <label>额外请求参数（JSON）<textarea value={extra || JSON.stringify(config.extra_body, null, 2)} onChange={e => setExtra(e.target.value)} spellCheck={false} /></label>
      <div className="form-row"><label>查询模式<select value={config.query_mode} onChange={e => patch({ query_mode: e.target.value })}><option value="tools">工具调用（默认）</option><option value="prefetch">预检索</option></select></label>
        <label>输出格式<select value={config.output_mode} onChange={e => patch({ output_mode: e.target.value })}><option value="prompt">提示词约束（默认）</option><option value="json_schema">json_schema（可选）</option></select></label></div>
      <label className="check"><input type="checkbox" checked={config.duckduckgo_enabled} onChange={e => patch({ duckduckgo_enabled: e.target.checked })} />启用 DuckDuckGo（先在 Worker 检测，默认关闭）</label>
      <div className="form-row">{(['lookup_daily_limit', 'chat_daily_limit', 'max_llm_calls', 'max_tool_calls', 'max_tokens', 'timeout_seconds'] as const).map(key => <label key={key}>{({
        lookup_daily_limit: '每群每天药品查询次数', chat_daily_limit: '每人每天对话轮数', max_llm_calls: '查询最多 LLM 轮数（3–6）',
        max_tool_calls: '查询最多工具次数（1–5）', max_tokens: '最大输出 tokens', timeout_seconds: 'LLM 超时（秒）',
      })[key]}<input type="number" value={config[key]} min="1" onChange={e => patch({ [key]: Number(e.target.value) })} required /></label>)}</div>
      {error && <Message warning>{error}</Message>}{message && <Message>{message}</Message>}
      <div className="actions"><button disabled={busy}>保存 AI 配置</button><button type="button" className="secondary" disabled={busy} onClick={() => {
        setBusy(true); setError(''); setProbe(undefined);
        api('/ai/probe', 'POST', {}).then(setProbe).catch(e => setError(errorText(e))).finally(() => setBusy(false));
      }}>{busy ? '正在处理…' : '检测接口能力（使用已保存配置）'}</button></div>
    </form>
    {probe !== undefined && <pre className="probe-result">{JSON.stringify(probe, null, 2)}</pre>}
    <TestLookup />
  </section>;
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
