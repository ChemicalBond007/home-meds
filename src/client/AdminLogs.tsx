import { useState } from 'react';
import { Empty, Message, useLoad } from './components';
import { logActions, logSourceKeys, logSources, type LogPage, type LogSource } from '../shared/logs';
import type { FamilyGroup, User } from '../shared/types';
import './AdminLogs.css';

const day = (offset: number) => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);
const displayTime = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'medium',
}).format(new Date(`${value.replace(' ', 'T')}Z`));
export function AdminLogs({ groups, users, usersError }: { groups: FamilyGroup[]; users: User[]; usersError?: string }) {
  const [source, setSource] = useState<LogSource>('audit');
  const [draft, setDraft] = useState({ user_id: '', group_id: '', from: day(-29), to: day(0), outcome: '' });
  const [filters, setFilters] = useState(draft), [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const params = new URLSearchParams({ source });
  for (const [key, value] of Object.entries(filters)) {
    if (value && !(source === 'usage' && key === 'outcome')) params.set(key, value);
  }
  const cursor = cursors.at(-1);
  if (cursor) params.set('cursor', cursor);
  const load = useLoad<LogPage>(`/admin/logs?${params}`);
  return <section className="panel" aria-labelledby="usage-logs-title">
    <div className="between"><h2 id="usage-logs-title">使用日志</h2><button className="secondary" disabled={load.loading} onClick={() => {
      setCursors([undefined]); load.reload();
    }}>刷新</button></div>
    <p className="muted">全局管理员可查看所有家庭的记录，不受顶部“当前家庭”限制。此页面只读。</p>
    <Message>操作审计从本功能上线后开始记录，包含写入操作、登录验证、配置查看和日志查看，不记录日常页面轮询。
      不保存密码、验证码、恢复码、密钥或聊天原文；AI 查询页会展示原有药品查询内容。</Message>
    <div className="segmented" aria-label="日志类型">{logSourceKeys.map(key => <button key={key} aria-pressed={source === key}
      className={source === key ? 'active' : ''} onClick={() => { setSource(key); setCursors([undefined]); }}>{logSources[key]}</button>)}</div>
    <form onSubmit={e => { e.preventDefault(); setFilters({ ...draft }); setCursors([undefined]); }}>
      <div className="log-filters">
        <label>操作用户<select value={draft.user_id} onChange={e => setDraft({ ...draft, user_id: e.target.value })}>
          <option value="">所有用户（含未登录请求）</option>{users.map(u => <option key={u.id} value={u.id}>{u.username}{u.role === 'admin' ? '（管理员）' : ''}</option>)}
        </select></label>
        <label>记录所属家庭<select value={draft.group_id} onChange={e => setDraft({ ...draft, group_id: e.target.value })}>
          <option value="">所有家庭（含全局操作）</option>{groups.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}
        </select></label>
        <label>开始日期<input type="date" value={draft.from} max={draft.to || undefined} onChange={e => setDraft({ ...draft, from: e.target.value })} /></label>
        <label>结束日期<input type="date" value={draft.to} min={draft.from || undefined} onChange={e => setDraft({ ...draft, to: e.target.value })} /></label>
        {source !== 'usage' && <label>操作结果<select value={draft.outcome} onChange={e => setDraft({ ...draft, outcome: e.target.value })}>
          <option value="">全部结果</option><option value="success">成功</option><option value="failure">失败</option>
        </select></label>}
      </div>
      <button className="secondary" disabled={load.loading}>应用筛选</button>
    </form>
    <p className="fine">时间显示为当前设备时区；日期筛选按 UTC 日期。服药记录展示当前状态，撤销轨迹见操作审计。</p>
    {source === 'usage' && <Message>用量是按家庭本地日期累计的配额计数，不等同于成功次数或 Token 账单。药品查询按家庭统计，管理员查询不计配额；
      对话按个人统计。筛选个人时仅显示其对话用量，家庭归属按当前成员关系。</Message>}
    {usersError && <Message warning>用户列表读取失败：{usersError}</Message>}
    {load.error && <Message warning>{load.error}<button className="plain" onClick={load.reload}>重新读取日志</button></Message>}
    {load.loading ? <Message>正在读取日志…</Message> : load.data?.items.length === 0 ? <Empty title="没有符合条件的记录">可以调整用户、家庭、日期或结果后重试。</Empty>
      : <div className="log-list">{load.data?.items.map(item => <article className="log-entry" key={item.id}>
        <header><strong>{logActions[item.action] || item.action}</strong>
          {item.outcome && <span className={`badge ${item.outcome === 'failure' ? 'attention' : ''}`}>{item.outcome === 'success' ? '成功' : '失败'}</span>}
          <time dateTime={`${item.created_at.replace(' ', 'T')}Z`}>{source === 'usage' ? item.created_at.slice(0, 10) : displayTime(item.created_at)}</time></header>
        <p>{item.actor_name || (source === 'usage' ? '家庭累计' : '未登录 / 身份未确认')} · {item.group_name || '全局 / 无家庭'}
          {item.subject_name && ` · 目标账号 / 服药人：${item.subject_name}`}{item.http_status !== null && ` · HTTP ${item.http_status}`}</p>
        {item.detail && <p className="log-detail">{source === 'usage' ? `已使用 ${item.detail} 次` : item.detail}</p>}
        {item.target_id && <p className="fine">对象编号：<code>{item.target_id}</code></p>}
      </article>)}</div>}
    {load.data && <div className="log-pagination"><span className="fine">第 {cursors.length} 页 · 本页 {load.data.items.length} 条</span><div className="actions">
      <button className="secondary" disabled={load.loading || cursors.length === 1} onClick={() => setCursors([undefined])}>第一页</button>
      <button className="secondary" disabled={load.loading || cursors.length === 1} onClick={() => setCursors(prev => prev.slice(0, -1))}>上一页</button>
      <button className="secondary" disabled={load.loading || !load.data.next_cursor} onClick={() => {
        const next = load.data?.next_cursor;
        if (next) setCursors(prev => [...prev, next]);
      }}>下一页</button>
    </div></div>}
  </section>;
}
