import { useCallback, useEffect, useState } from 'react';
import { z } from 'zod';
import { api } from './api';
import { Confirm, Empty, Message, Modal, errorText, useLoad } from './components';
import { planSchema } from '../shared/schemas';
import { localClock } from '../shared/schedule';
import { parseCode } from '../shared/barcode';
import type { User, Product, Plan, Todo, StockItem, FamilyGroup } from '../shared/types';
import { Scanner } from './Scanner';
import { availableBoxes, boxLabel, canConfirmDoseBox, expiryLabel, initialDoseBox, productLabel, type PlanBoxes } from './plan-boxes';
export type Draft = z.infer<typeof planSchema>;
export function toDraft(p: Plan): Draft {
  return { ...p, times: JSON.parse(p.times), weekdays: JSON.parse(p.weekdays), enabled: !!p.enabled, notify_family: !!p.notify_family };
}
export const mealLabel = { none: '不限用餐', before: '饭前', after: '饭后', with: '随餐' };
export const frequencyLabel = { daily: '每天', weekly: '每周固定几天', interval: '每隔 N 天', as_needed: '需要时吃' };
export function PlanEditor({ initial, planId, members, products, stock, family, close, saved }: {
  initial?: Draft; planId?: string; members: User[]; products: Product[]; stock?: StockItem[]; family: FamilyGroup; close: () => void; saved: () => void;
}) {
  const [d, setD] = useState<Draft>(initial || {
    user_id: members[0]?.id || '', product_id: null, preferred_stock_item_id: null, medicine_name: '', amount: 1, unit: '片',
    times: [JSON.parse(family.meal_times).morning], frequency: 'daily', weekdays: [], interval_days: 1,
    start_date: localClock(new Date(), family.timezone).date, end_date: null, meal_relation: 'none', notes: '', enabled: true,
    notify_family: !!members[0]?.is_elderly,
  });
  const [timeText, setTimeText] = useState(d.times.join(','));
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const patch = (v: Partial<Draft>) => setD(prev => ({ ...prev, ...v }));
  const today = localClock(new Date(), family.timezone).date, boxes = availableBoxes(stock || [], d.product_id);
  const preferred = stock?.find(b => b.id === d.preferred_stock_item_id && b.product_id === d.product_id);
  const unavailable = !!d.preferred_stock_item_id && !boxes.some(b => b.id === d.preferred_stock_item_id);
  return <Modal title={planId ? '核对并修改计划' : '核对并添加计划'} close={() => { if (!busy) close(); }}>
    <p className="muted">这里只记录计划。请按医嘱和说明书确认用量。</p>
    <form onSubmit={e => {
      e.preventDefault(); setError('');
      const result = planSchema.safeParse({ ...d, times: d.frequency === 'as_needed' ? [] : timeText.split(/[,，、\s]+/).filter(Boolean) });
      if (!result.success) { setError(result.error.issues.map(i => i.message).join('；')); return; }
      setBusy(true);
      api(planId ? `/plans/${planId}` : '/plans', planId ? 'PUT' : 'POST', result.data)
        .then(() => { saved(); close(); }).catch(e => setError(errorText(e))).finally(() => setBusy(false));
    }}>
      <label>谁的计划<select value={d.user_id} required onChange={e => patch({ user_id: e.target.value, notify_family: !!members.find(m => m.id === e.target.value)?.is_elderly })}>
        {members.map(m => <option key={m.id} value={m.id}>{m.username}{m.is_elderly ? '（老人）' : ''}</option>)}</select></label>
      <label>药箱里的药<select value={d.product_id || ''} onChange={e => {
        const p = products.find(p => p.id === e.target.value);
        patch({ product_id: p?.id || null, preferred_stock_item_id: null, ...(p ? { medicine_name: p.name, unit: p.base_unit } : {}) });
      }}><option value="">暂未入库，手填名称</option>{products.map(p => <option key={p.id} value={p.id}>{productLabel(p, stock)}</option>)}</select></label>
      {d.product_id && <>
        <label>优先药盒（按有效期选择，可不指定）<select value={d.preferred_stock_item_id || ''} disabled={!stock} onChange={e => patch({ preferred_stock_item_id: e.target.value || null })}>
          <option value="">不指定，每次服药时核对选择</option>
          {unavailable && <option value={d.preferred_stock_item_id!} disabled>原优先药盒不可用 · {preferred ? boxLabel(preferred, today) : '请重新选择'}</option>}
          {boxes.map(b => <option key={b.id} value={b.id}>{boxLabel(b, today)}</option>)}
        </select></label>
        {!stock ? <Message>正在读取药盒有效期…</Message> : unavailable ? <Message warning>原优先药盒已用尽、报废或不可用。请重新选择；记录服药时不会自动改用另一盒。</Message> : null}
        {preferred && <Message warning={!preferred.expiry_date || preferred.expiry_date < today}>优先药盒：{boxLabel(preferred, today)}。请核对实物药名、规格、有效期和批号；记录服药时仍可换盒。</Message>}
      </>}
      <label>药品名称<input value={d.medicine_name} maxLength={200} required readOnly={!!d.product_id} onChange={e => patch({ medicine_name: e.target.value })} /></label>
      <div className="form-row"><label>每次用量<input type="number" min="0.001" max="100000" step="0.001" value={d.amount} required onChange={e => patch({ amount: Number(e.target.value) })} /></label>
        <label>基本单位<input value={d.unit} required maxLength={20} readOnly={!!d.product_id} onChange={e => patch({ unit: e.target.value })} /></label></div>
      <label>频率<select value={d.frequency} onChange={e => patch({ frequency: e.target.value as Draft['frequency'] })}>{Object.entries(frequencyLabel).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></label>
      {d.frequency === 'weekly' && <fieldset><legend>每周几</legend><div className="weekday">{['日', '一', '二', '三', '四', '五', '六'].map((day, i) => <label className="check" key={day}><input type="checkbox" checked={d.weekdays.includes(i)} onChange={e => patch({ weekdays: e.target.checked ? [...d.weekdays, i] : d.weekdays.filter(x => x !== i) })} />{day}</label>)}</div></fieldset>}
      {d.frequency === 'interval' && <label>每隔几天<input type="number" min="1" max="365" value={d.interval_days} onChange={e => patch({ interval_days: Number(e.target.value) })} required /></label>}
      {d.frequency !== 'as_needed' && <><label>每天的时间，用逗号分开<input value={timeText} placeholder="08:00,20:00" onChange={e => setTimeText(e.target.value)} required /></label>
        <div className="quick-replies">{Object.entries(JSON.parse(family.meal_times) as Record<string, string>).map(([key, time]) => <button type="button" className="secondary" key={key} onClick={() => setTimeText(prev => [...new Set([...prev.split(',').filter(Boolean), time])].sort().join(','))}>{({ morning: '早', noon: '中', evening: '晚', bedtime: '睡前' } as Record<string, string>)[key]} {time}</button>)}</div></>}
      <div className="form-row"><label>开始日期<input type="date" value={d.start_date} onChange={e => patch({ start_date: e.target.value })} required /></label>
        <label>结束日期（可不填）<input type="date" value={d.end_date || ''} min={d.start_date} onChange={e => patch({ end_date: e.target.value || null })} /></label></div>
      <label>用餐关系<select value={d.meal_relation} onChange={e => patch({ meal_relation: e.target.value as Draft['meal_relation'] })}>{Object.entries(mealLabel).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></label>
      <label>备注<textarea value={d.notes} maxLength={2000} onChange={e => patch({ notes: e.target.value })} /></label>
      <label className="check"><input type="checkbox" checked={d.enabled} onChange={e => patch({ enabled: e.target.checked })} />启用计划</label>
      <label className="check"><input type="checkbox" checked={!!d.notify_family} onChange={e => patch({ notify_family: e.target.checked })} />错过 30 分钟提醒家人</label>
      {error && <Message warning>{error}</Message>}<button type="submit" disabled={busy || (!!d.product_id && !stock)}>{busy ? '正在保存…' : '已核对，确认保存'}</button>
    </form>
  </Modal>;
}
export function PlansPage({ members, products, stock, family, user, version, changed, openDose }: {
  members: User[]; products: Product[]; stock?: StockItem[]; family: FamilyGroup; user: User; version: number; changed: () => void; openDose: (t: Todo) => void;
}) {
  const { data, error } = useLoad<Plan[]>('/plans', `${family.id}:${version}`);
  const [editing, setEditing] = useState<{ id?: string; draft?: Draft }>(), [pause, setPause] = useState<Plan>();
  return <><div className="section-heading"><div><p className="eyebrow">MEDICATION PLANS</p><h1>吃药计划</h1><p className="muted">家人的计划，一起记住。</p></div><button onClick={() => setEditing({})}>＋ 添加计划</button></div>
    {error && <Message warning>{error}</Message>}
    {data?.length === 0 && <Empty title="从一份计划开始">手动添加，或到「对话助手」用一句话记录。</Empty>}
    <div className="card-grid">{data?.map(p => <section className="panel" key={p.id}><div className="between"><span className={`badge ${p.enabled ? '' : 'neutral'}`}>{p.enabled ? '● 已启用' : 'Ⅱ 已暂停'}</span><span className="muted">{p.username}</span></div>
      <h2>{p.medicine_name}</h2><p className="dose-amount">{p.amount} <span>{p.unit} / 次</span></p><p>{frequencyLabel[p.frequency]} · {mealLabel[p.meal_relation]}</p>
      <p className="times">{(JSON.parse(p.times) as string[]).join(' / ') || '按需记录，不提醒'}</p>
      {p.preferred_stock_item_id && <p className="fine">优先药盒：{(() => {
        const b = stock?.find(b => b.id === p.preferred_stock_item_id && b.product_id === p.product_id);
        return !stock ? '正在读取…' : b ? `${boxLabel(b, localClock(new Date(), family.timezone).date)}${b.status !== 'active' || b.units_remaining <= 0 ? '（不可用，请重新选盒）' : ''}` : '不可用，请重新选择';
      })()}</p>}
      {p.notes && <p>{p.notes}</p>}<p className="fine">最后修改：{members.find(u => u.id === p.updated_by)?.username || '管理员'}</p>
      <div className="actions"><button className="secondary" onClick={() => setEditing({ id: p.id, draft: toDraft(p) })}>修改</button>
        {p.enabled ? <button className="plain" onClick={() => setPause(p)}>暂停</button> : null}
        {p.enabled && p.frequency === 'as_needed' && <button onClick={() => openDose({ key: crypto.randomUUID(), plan: p, local_date: localClock(new Date(), family.timezone).date,
          scheduled_time: `as-needed:${crypto.randomUUID()}`, state: 'pending' })}>记录一次</button>}</div>
    </section>)}</div>
    {editing && <PlanEditor initial={editing.draft || (members.find(m => m.id === user.id) ? {
      user_id: user.id, product_id: null, medicine_name: '', amount: 1, unit: '片', times: ['08:00'], frequency: 'daily', weekdays: [], interval_days: 1,
      start_date: localClock(new Date(), family.timezone).date, end_date: null, meal_relation: 'none', notes: '', enabled: true, notify_family: !!user.is_elderly,
    } : undefined)} planId={editing.id} members={members} products={products} stock={stock} family={family} close={() => setEditing(undefined)} saved={changed} />}
    {pause && <Confirm title="暂停这个计划？" close={() => setPause(undefined)} action={async () => { await api(`/plans/${pause.id}`, 'DELETE'); changed(); }}><p>暂停后不再提醒，历史服药记录会保留。</p></Confirm>}
  </>;
}
function deviceDateTime() {
  const date = new Date();
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
export function DoseDialog({ todo, close, saved }: { todo: Todo; close: () => void; saved: () => void }) {
  const { data, error: loadError, reload } = useLoad<PlanBoxes>(`/plans/${todo.plan.id}/boxes`);
  const [step, setStep] = useState(1), [amount, setAmount] = useState(todo.plan.amount), [time, setTime] = useState(deviceDateTime);
  const [selected, setSelected] = useState<string | null>(null), [verified, setVerified] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [scanning, setScanning] = useState(false), [scanMessage, setScanMessage] = useState('');
  const choose = (id: string | null) => { setSelected(id); setVerified(false); };
  useEffect(() => { if (data) { setSelected(initialDoseBox(data)); setVerified(false); } }, [data]);
  const box = data?.boxes.find(b => b.id === selected), expired = !!box?.expiry_date && box.expiry_date < todo.local_date;
  const submit = async (confirmExpired: boolean) => {
    if (!canConfirmDoseBox(data, selected, verified)) { setError('请选择实际药盒或明确不扣库存，并核对实物有效期'); return; }
    setBusy(true); setError('');
    try {
      await api('/doses', 'POST', { plan_id: todo.plan.id, local_date: todo.local_date, scheduled_time: todo.scheduled_time,
        status: 'taken', amount, taken_at: new Date(time).toISOString(), stock_item_id: selected || null, confirm_expired: confirmExpired,
        ...(box ? { expected_stock_version: box.metadata_version } : {}) });
      saved(); close();
    } catch (e) { setError(errorText(e)); setVerified(false); setSelected(null); setStep(2); reload(); } finally { setBusy(false); }
  };
  const scan = useCallback((value: string) => {
    setScanning(false); setScanMessage(''); choose(null); const p = parseCode(value);
    if (p.trace_code) {
      const b = data?.boxes.find(b => b.trace_code === p.trace_code);
      if (b) { choose(b.id); setScanMessage('已找到这盒药，请核对实物有效期和批号后确认。'); }
      else setScanMessage('没找到这盒可用的同种药，请核对药品或先入库。');
    } else if (p.gtin) {
      api<{ product: Product | null }>(`/codes/resolve?code=${encodeURIComponent(value)}`).then(r => {
        setScanMessage(r.product?.id === todo.plan.product_id ? '商品码只能确认药品，不能区分批次。请按实物有效期和批号选一盒。' : '这个码不是计划里的药品，请重新核对。');
      }).catch(e => setScanMessage(errorText(e)));
    } else setScanMessage('没有识别出有效药码，请从列表选盒。');
  }, [data, todo.plan.product_id]);
  return <>
    <Modal title={step === 1 ? '第一步 · 确认用药' : step === 2 ? '第二步 · 选对药盒' : '请再次核对过期药'} close={() => { if (!busy) close(); }}>
      <div className="dose-summary"><span>{todo.plan.username || '家人'}的药</span><h2>{todo.plan.medicine_name}</h2><p>{mealLabel[todo.plan.meal_relation]} {todo.plan.notes}</p></div>
      {error && <Message warning>{error}</Message>}{loadError && <Message warning>{loadError}</Message>}
      {step === 1 ? <form onSubmit={e => { e.preventDefault(); setStep(2); }}>
        <label>实际服药时间（此设备时区）<input type="datetime-local" value={time} required onChange={e => setTime(e.target.value)} /></label>
        <label>这次吃多少</label><div className="amount-control"><button type="button" className="secondary" aria-label="减少用量" onClick={() => setAmount(n => Math.max(0.001, Math.round((n - 1) * 1000) / 1000))}>−</button>
          <input aria-label={`实际用量，${todo.plan.unit}`} type="number" min="0.001" max="100000" step="0.001" value={amount} required onChange={e => setAmount(Number(e.target.value))} /><strong>{todo.plan.unit}</strong>
          <button type="button" className="secondary" aria-label="增加用量" onClick={() => setAmount(n => Math.round((n + 1) * 1000) / 1000)}>＋</button></div>
        <button className="full">已核对时间和用量，下一步</button>
      </form> : step === 2 ? <>
        <p>将记录 {amount} {todo.plan.unit}，请选择实际吃的那盒。</p>
        <button className="secondary full" onClick={() => setScanning(true)}>扫码选药盒</button>
        {scanMessage && <Message>{scanMessage}</Message>}
        <DoseBoxChoices data={data} selected={selected} choose={choose} today={todo.local_date} verified={verified} verify={setVerified} busy={busy} />
        <div className="actions"><button disabled={busy || !canConfirmDoseBox(data, selected, verified)} onClick={() => expired ? setStep(3) : void submit(false)}>{busy ? '正在记录…' : '确认吃过这次药'}</button><button className="secondary" disabled={busy} onClick={() => { setVerified(false); setStep(1); }}>返回核对</button></div>
      </> : <><Message warning><h3>⚠ 这盒药已过期</h3><p>有效期：{box?.expiry_date}。不建议使用过期药，请联系医生或药师核实。记录不会代表药品安全。</p></Message>
        <div className="actions"><button disabled={busy} className="danger" onClick={() => void submit(true)}>{busy ? '正在记录…' : '我已再次核实，确认记录'}</button><button disabled={busy} className="secondary" onClick={() => setStep(2)}>重新选盒</button></div></>}
    </Modal>
    {scanning && <Scanner onCode={scan} close={() => setScanning(false)} />}
  </>;
}
export function DoseBoxChoices({ data, selected, choose, today, verified, verify, busy }: {
  data?: PlanBoxes; selected: string | null; choose: (id: string) => void; today: string;
  verified: boolean; verify: (checked: boolean) => void; busy: boolean;
}) {
  const box = data?.boxes.find(b => b.id === selected);
  const missingPreferred = data?.preferred_stock_item_id && !data.boxes.some(b => b.id === data.preferred_stock_item_id);
  return <>
    {missingPreferred && <Message warning>计划的优先药盒已用尽、报废或不可用。未自动换盒，请重新选择实际药盒，或明确选择不扣库存。</Message>}
    {!data && <Message>正在读取可用药盒…</Message>}
    <fieldset className="box-list" disabled={busy || !data}><legend>可用药盒（已开封优先，到期早的在前；请核对有效期）</legend>
      {data?.boxes.map(b => <label className={`box-option ${b.expiry_date && b.expiry_date < today ? 'expired' : ''}`} key={b.id}>
        <input type="radio" name="box" checked={selected === b.id} onChange={() => choose(b.id)} /><span><strong>{b.warehouse_name} · {b.opened ? '已开封' : '未开封'}</strong>
          <span>{expiryLabel(b, today)}</span><span>批号 {b.batch_number || '未填写'} · 剩余 {b.units_remaining} {b.base_unit}</span>
          <span>{b.id === data.preferred_stock_item_id ? '计划优先药盒' : ''}{b.id === data.last_stock_item_id ? ' · 上次使用' : ''}</span>
          <small>药盒 {b.trace_code || b.id.slice(-8)}</small></span></label>)}
      <label className="box-option"><input type="radio" name="box" checked={selected === ''} onChange={() => choose('')} /><span><strong>不扣库存</strong><span>药不在药箱里，只记录已吃</span></span></label>
    </fieldset>
    {box ? <Message warning={!box.expiry_date || box.expiry_date < today}><strong>本次药盒：{box.name}</strong><p>{boxLabel(box, today)}</p><p>请与手上的药盒核对，不要仅凭同名或上次选择确认。</p></Message>
      : selected === '' ? <Message warning>本次不扣库存，系统无法核对实际药盒有效期，请务必查看实物包装。</Message> : null}
    <label className="check"><input type="checkbox" checked={verified} disabled={busy || !canConfirmDoseBox(data, selected, true)}
      onChange={e => verify(e.target.checked)} />我已核对实物药名、规格、有效期和批号，确认选对药盒及本次用量</label>
  </>;
}
