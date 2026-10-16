import { useCallback, useEffect, useState } from 'react';
import { api, selectGroup, selectedGroup } from './api';
import { Login, Security } from './Auth';
import { Confirm, Empty, Icon, Message, Modal, Speak, errorText, useLoad } from './components';
import { InventoryPage, Sources, type Warehouse } from './Inventory';
import { DoseDialog, PlansPage, mealLabel } from './Plans';
import { SettingsPage } from './Settings';
import { ChatPage } from './Chat';
import { Scanner } from './Scanner';
import type { DoseLog, FamilyGroup, Product, StockItem, Todo, User } from '../shared/types';
import type { ParsedCode } from '../shared/barcode';
import { dayNumber, localClock } from '../shared/schedule';
type Page = 'home' | 'inventory' | 'plans' | 'chat' | 'settings';
const navigation = [
  { id: 'home', icon: 'home', name: '今天' }, { id: 'inventory', icon: 'box', name: '我的药箱' },
  { id: 'plans', icon: 'plan', name: '吃药计划' }, { id: 'chat', icon: 'chat', name: '对话助手' }, { id: 'settings', icon: 'settings', name: '设置' },
] as const;
export function App() {
  const [authVersion, setAuthVersion] = useState(0);
  const me = useLoad<{ user: User; binding_only: boolean }>('/auth/me', authVersion);
  const refreshAuth = () => setAuthVersion(n => n + 1);
  if (me.loading) return <main className="loading"><img src="/icon.svg" alt="" /><h1>家药箱</h1><p role="status">正在打开你的药箱…</p></main>;
  if (!me.data) return <Login done={refreshAuth} />;
  if (me.data.binding_only) return <main className="binding-page"><div className="brand"><img src="/icon.svg" alt="" />家药箱</div><Security bindingOnly done={refreshAuth} /></main>;
  return <Workspace user={me.data.user} refreshAuth={refreshAuth} />;
}
function Workspace({ user, refreshAuth }: { user: User; refreshAuth: () => void }) {
  const [version, setVersion] = useState(0), [page, setPage] = useState<Page>('home');
  const [groupId, setGroupId] = useState(new URLSearchParams(location.search).get('group') || selectedGroup);
  const groups = useLoad<FamilyGroup[]>('/groups', version);
  const family = groups.data?.find(g => g.id === groupId) || groups.data?.find(g => g.id === user.group_id) || groups.data?.[0];
  useEffect(() => { if (family && family.id !== groupId) setGroupId(family.id); if (family) selectGroup(family.id); }, [family?.id, groupId]);
  useEffect(() => {
    document.documentElement.classList.toggle('elderly', !!user.is_elderly);
    return () => document.documentElement.classList.remove('elderly');
  }, [user.is_elderly]);
  useEffect(() => { window.scrollTo({ top: 0 }); document.title = `${navigation.find(n => n.id === page)?.name} · 家药箱`; }, [page]);
  const changed = () => setVersion(n => n + 1);
  return <div className={`app ${user.is_elderly ? 'elderly-layout' : ''}`}><a className="skip-link" href="#main">跳到主要内容</a>
    <aside className="sidebar"><div className="brand"><img src="/icon.svg" alt="" /><span>家药箱<small>家人一起照顾</small></span></div>
      <nav aria-label="主导航">{navigation.map(n => <button key={n.id} className={`nav-item ${page === n.id ? 'active' : ''}`} aria-current={page === n.id ? 'page' : undefined} onClick={() => setPage(n.id)}><Icon name={n.icon} /><span>{n.name}</span></button>)}</nav>
      <div className="sidebar-bottom"><span className="avatar">{user.username.slice(0, 1)}</span><div><strong>{user.username}</strong><small>{user.role === 'admin' ? '管理员' : user.is_elderly ? '老人模式' : '家庭成员'}</small></div></div>
    </aside>
    <div className="workspace"><header className="topbar"><div className="mobile-brand"><img src="/icon.svg" alt="" />家药箱</div><span className="topbar-note">一家人的健康小事，认真记好。</span>
      {groups.data?.length ? <label className="group-picker"><span>当前家庭</span><select aria-label="当前家庭" value={family?.id || ''} onChange={e => { selectGroup(e.target.value); setGroupId(e.target.value); }}>
        {groups.data.map(g => <option value={g.id} key={g.id}>{g.name}</option>)}</select></label> : <span>欢迎，{user.username}</span>}</header>
      <main id="main" tabIndex={-1}>
        {groups.error && <Message warning>{groups.error}</Message>}
        {groups.loading && !family ? <Message>正在读取家庭…</Message> : family ? <FamilyContent key={family.id} family={family} groups={groups.data || []} user={user} page={page} navigate={setPage} version={version} changed={changed} refreshAuth={refreshAuth} />
          : <><Empty title={user.role === 'admin' ? '先为家人建一个药箱' : '账号还没有加入群组'}>{user.role === 'admin' ? '在下面创建群组，将分组码交给家人注册。' : '请联系管理员安排群组。'}</Empty>
            <SettingsPage user={user} groups={groups.data || []} members={[]} warehouses={[]} changed={changed} logout={refreshAuth} /></>}
      </main>
      <footer>家药箱 · 按医嘱和说明书用药，记录不能代替医疗建议。</footer>
    </div>
  </div>;
}
function FamilyContent({ family, groups, user, page, navigate, version, changed, refreshAuth }: {
  family: FamilyGroup; groups: FamilyGroup[]; user: User; page: Page; navigate: (p: Page) => void; version: number; changed: () => void; refreshAuth: () => void;
}) {
  // Select synchronously before child effects so an administrator never loads a previous group's data.
  selectGroup(family.id);
  const members = useLoad<User[]>('/members', `${family.id}:${version}`), products = useLoad<Product[]>('/products', `${family.id}:${version}`);
  const warehouses = useLoad<Warehouse[]>('/warehouses', `${family.id}:${version}`);
  const [poll, setPoll] = useState(0);
  const today = useLoad<{ date: string; time: string; timezone: string; items: Todo[] }>('/today', `${family.id}:${version}:${poll}`);
  const [dose, setDose] = useState<Todo>(), [skip, setSkip] = useState<Todo>(), [undo, setUndo] = useState<DoseLog>();
  const [scanning, setScanning] = useState(false), [scanned, setScanned] = useState(''), [intake, setIntake] = useState<{ code?: string }>();
  const [notice, setNotice] = useState('');
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') setPoll(n => n + 1); };
    const id = setInterval(refresh, 60000); document.addEventListener('visibilitychange', refresh);
    return () => { clearInterval(id); document.removeEventListener('visibilitychange', refresh); };
  }, []);
  useEffect(() => {
    if (!today.data) return;
    const params = new URLSearchParams(location.search);
    const id = params.get('plan');
    if (!id) return;
    const item = today.data.items.find(t => t.plan.id === id && t.local_date === params.get('date') && t.scheduled_time === params.get('time'));
    if (item && ['pending', 'overdue'].includes(item.state)) setDose(item);
    else setNotice('这条通知的服药项已处理、已修改或不是今天的记录。请与家人核实，不要重复服药。');
    history.replaceState(null, '', location.pathname);
  }, [today.data]);
  const onScan = useCallback((value: string) => { setScanning(false); setScanned(value); }, []);
  const stock = useLoad<StockItem[]>('/stock', `${family.id}:${version}`);
  const errors = [members.error, products.error, warehouses.error, today.error, stock.error].filter(Boolean);
  const openIntake = (v?: { code?: string }) => { setIntake(v); if (v) navigate('inventory'); };
  return <>
    {errors.length > 0 && <Message warning>{[...new Set(errors)].join('；')}</Message>}
    {notice && <Message warning><p>{notice}</p><button className="secondary" onClick={() => setNotice('')}>知道了</button></Message>}
    {page === 'home' && <Home user={user} family={family} members={members.data || []} today={today.data?.items} stock={stock.data || []}
      openDose={setDose} skip={setSkip} undo={setUndo} scan={() => setScanning(true)} navigate={navigate} intake={() => openIntake({})} />}
    {page === 'inventory' && <InventoryPage family={family} user={user} products={products.data || []} warehouses={warehouses.data || []} version={version} changed={changed} intake={intake} openIntake={openIntake} />}
    {page === 'plans' && <PlansPage members={members.data || []} products={products.data || []} family={family} user={user} version={version} changed={changed} openDose={setDose} />}
    {page === 'chat' && members.data && <ChatPage user={user} family={family} members={members.data} products={products.data || []} changed={changed} />}
    {page === 'settings' && <SettingsPage user={user} family={family} groups={groups} members={members.data || []} warehouses={warehouses.data || []} changed={() => { changed(); }} logout={refreshAuth} />}
    {dose && <DoseDialog todo={dose} close={() => setDose(undefined)} saved={changed} />}
    {skip && <Confirm title="确认这次不吃？" close={() => setSkip(undefined)} action={async () => { await api('/doses', 'POST', {
      plan_id: skip.plan.id, local_date: skip.local_date, scheduled_time: skip.scheduled_time, status: 'skipped',
    }); changed(); }}><p>{skip.plan.medicine_name} · {skip.scheduled_time}</p><p>会记为跳过，不会扣库存。是否需要停药请先咨询医生。</p></Confirm>}
    {undo && <Confirm title="撤销这次服药记录？" close={() => setUndo(undefined)} action={async () => { await api(`/doses/${undo.id}/undo`, 'POST', {}); changed(); }}><p>只可撤销当天记录。如果扣过库存，会恢复到原来的药盒。请确认只是记录错误，避免重复服药。</p></Confirm>}
    {scanning && <Scanner onCode={onScan} close={() => setScanning(false)} />}
    {scanned && <ScanResult code={scanned} close={() => setScanned('')} intake={() => { openIntake({ code: scanned }); setScanned(''); }} />}
  </>;
}
function Home({ user, family, members, today, stock, openDose, skip, undo, scan, navigate, intake }: {
  user: User; family: FamilyGroup; members: User[]; today?: Todo[]; stock: StockItem[]; openDose: (t: Todo) => void; skip: (t: Todo) => void; undo: (l: DoseLog) => void; scan: () => void; navigate: (p: Page) => void; intake: () => void;
}) {
  const [viewFamily, setViewFamily] = useState<User>();
  const clock = localClock(new Date(), family.timezone), mine = today?.filter(t => t.plan.user_id === user.id);
  const done = mine?.filter(t => t.state === 'taken' || t.state === 'skipped').length || 0;
  const next = mine?.find(t => ['pending', 'overdue'].includes(t.state));
  const warning = stock.filter(s => s.status === 'active' && s.expiry_date && dayNumber(s.expiry_date) - dayNumber(clock.date) <= 30);
  const list = (items: Todo[]) => items.map(t => <TodoRow key={t.key} todo={t} highlight={next?.key === t.key} timezone={family.timezone} open={() => openDose(t)} skip={() => skip(t)} undo={() => t.log && undo(t.log)} />);
  const checklist = <section className="panel today-panel"><div className="between">{user.is_elderly ? <h1>今天的药</h1> : <h2>我的今日吃药</h2>}<span className="completion">{done} / {mine?.length || 0} 已处理</span></div>
    {!today ? <Message>正在读取今天的清单…</Message> : mine?.length ? <div className="todo-list">{list(mine)}</div> : <Empty title="今天没有定时服药">按需吃的药可到「吃药计划」记录，请以医嘱为准。</Empty>}
    <button className="plain full" onClick={() => navigate('plans')}>查看或修改吃药计划 →</button>
  </section>;
  const quick = <div className="quick-actions">
    <button className="quick-card scan-card" onClick={scan}><Icon name="scan" /><span><strong>扫一扫看看是什么药</strong><small>识别药盒，核对药品信息</small></span><span aria-hidden="true">↗</span></button>
    <button className="quick-card" onClick={() => navigate('inventory')}><Icon name="box" /><span><strong>我的药箱</strong><small>看看药在哪里，还剩多少</small></span></button>
    <button className="quick-card" onClick={intake}><Icon name="plus" /><span><strong>放进药箱</strong><small>新买的药，安心存好</small></span></button>
  </div>;
  return <>
    {!user.is_elderly && <div className="section-heading"><div><p className="eyebrow">A HEALTHIER DAY, TOGETHER</p><h1>{user.username}，今天也照顾好自己。</h1><p className="muted">{clock.date} · {family.name} · {family.timezone}</p></div><div className="date-tile"><span>{new Intl.DateTimeFormat('zh-CN', { weekday: 'long', timeZone: family.timezone }).format(new Date())}</span><strong>{clock.date.slice(8)}</strong></div></div>}
    {user.is_elderly ? <>{checklist}{quick}</> : <>{quick}<div className="home-columns">{checklist}<section className="panel family-panel"><div className="between"><h2>家人今日服药</h2><span className="badge neutral">一起照顾</span></div>
      {members.filter(m => m.id !== user.id).map(m => {
        const all = today?.filter(t => t.plan.user_id === m.id) || [], completed = all.filter(t => ['taken', 'skipped'].includes(t.state)).length, overdue = all.some(t => t.state === 'overdue');
        return <button className="family-row" key={m.id} onClick={() => setViewFamily(m)}><span className="avatar">{m.username.slice(0, 1)}</span><span><strong>{m.username}</strong><small className={overdue ? 'danger-text' : ''}>{overdue ? '！有过时未吃，请先核实' : all.length ? `已处理 ${completed} / ${all.length} 次` : '今天没有定时服药'}</small></span><span aria-hidden="true">→</span></button>;
      })}{members.length <= 1 && <p className="muted">家人加入群组后，会在这里显示。</p>}</section></div></>}
    {warning.length > 0 && <section className="panel expiry-panel"><div><span className="badge attention">！药盒有效期提醒</span><h2>{warning.filter(s => s.expiry_date! < clock.date).length} 盒已过期，{warning.filter(s => s.expiry_date! >= clock.date).length} 盒临近到期</h2><p>{warning.slice(0, 3).map(s => s.name).join('、')}{warning.length > 3 ? '等' : ''}。请核对药盒，不要使用过期药。</p></div><button className="secondary" onClick={() => navigate('inventory')}>去药箱核对</button></section>}
    {user.is_elderly && <button className="secondary full" onClick={() => navigate('chat')}>和助手说说吃药计划</button>}
    {viewFamily && <Modal title={`${viewFamily.username}今天的药`} close={() => setViewFamily(undefined)}><Message>代为记录前请和本人核实，避免重复服药。记录会保留操作人。</Message>
      <div className="todo-list">{list(today?.filter(t => t.plan.user_id === viewFamily.id) || [])}</div><button className="secondary" onClick={() => { setViewFamily(undefined); navigate('plans'); }}>查看或修改家人计划</button></Modal>}
  </>;
}
function TodoRow({ todo: t, highlight, timezone, open, skip, undo }: {
  todo: Todo; highlight: boolean; timezone: string; open: () => void; skip: () => void; undo: () => void;
}) {
  const finished = ['taken', 'skipped'].includes(t.state);
  return <article className={`todo-row ${t.state} ${highlight ? 'next' : ''}`}>
    <div className="todo-main"><button className={`dose-check ${finished ? 'checked' : ''}`} disabled={finished} aria-label={`${t.plan.medicine_name}，${t.plan.amount}${t.plan.unit}，${finished ? '已处理' : '打开确认服药'}`} onClick={open}>{t.state === 'taken' ? '✓' : t.state === 'skipped' ? '−' : '○'}</button>
      <div className="todo-info"><div className="todo-time">{t.scheduled_time.startsWith('as-needed:') ? '按需记录' : t.scheduled_time}{highlight && <span className="badge">下一次待确认</span>}</div><h3>{t.plan.medicine_name}</h3>
        <p>{t.plan.amount} {t.plan.unit} · {mealLabel[t.plan.meal_relation]}</p>
        {t.plan.notes && <p className="fine">{t.plan.notes}</p>}
        {t.state === 'overdue' && <p className="danger-text"><strong>！过时未吃，请先核实，不要补服或重复服药</strong></p>}
        {t.log && <p className="record-status">{t.state === 'taken' ? `✓ 已吃 ${t.log.amount}${t.log.unit} · ${t.log.taken_at ? new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, hour: '2-digit', minute: '2-digit' }).format(new Date(t.log.taken_at)) : ''}` : '− 已跳过'} · {t.log.actor_name || '家人'}记录</p>}
        {!finished && <span className="state-text">{t.state === 'pending' ? '○ 待吃，吃过后再确认' : ''}</span>}
      </div></div><div className="todo-actions">{finished ? <button className="plain" onClick={undo}>撤销记录</button> : <><button onClick={open}>我吃过了</button><button className="plain" onClick={skip}>这次不吃</button></>}</div>
  </article>;
}
function ScanResult({ code, close, intake }: { code: string; close: () => void; intake: () => void }) {
  const { data, error } = useLoad<{ parsed: ParsedCode; product: Product | null; cache: Partial<Product> | null; box: StockItem | null }>(`/codes/resolve?code=${encodeURIComponent(code)}`);
  const p = data?.product;
  return <Modal title="扫码结果" close={close}>{error && <Message warning>{error}</Message>}
    {!data ? <Message>正在查找药品…</Message> : p ? <><span className="badge">{p.status === 'pending' ? '！待补充' : '✓ 本群药品'}</span><h2 className="scan-name">{p.name}</h2><p>{p.specification}</p><p>{p.manufacturer}</p>
      <h3>资料中的适应症</h3><p>{p.indications || '尚无确认信息'}</p><h3>注意事项</h3><p>{p.precautions || '请对照说明书'}</p>
      <Speak text={`${p.name}。${p.specification || ''}。${p.indications || ''}。注意事项：${p.precautions || '请对照说明书'}`} /><Sources sources={JSON.parse(p.sources)} />
      {data.box ? <Message>这盒药已经在药箱里，无需重复入库。</Message> : <button className="full" onClick={intake}>确认这是我的药，放进药箱</button>}
    </> : <><Empty title="还不认识这盒药">可以让 AI 查资料，也可以先存下让家人补充。</Empty><p className="fine">识别类型：{data.parsed.kind} · 码 {code.slice(0, 100)}</p><button className="full" onClick={intake}>AI 帮我识别 / 手动入库</button><button className="secondary full" onClick={intake}>先存下，让家人补充</button></>}
    <p className="fine">扫码结果不代表药品安全，请核对包装、有效期和说明书。</p>
  </Modal>;
}
