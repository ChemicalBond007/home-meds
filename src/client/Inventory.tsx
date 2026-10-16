import { useCallback, useEffect, useState } from 'react';
import { z } from 'zod';
import { api, sse } from './api';
import { Confirm, Empty, Message, Modal, Speak, errorText, useLoad } from './components';
import { Scanner } from './Scanner';
import { productSchema } from '../shared/schemas';
import { localClock, dayNumber } from '../shared/schedule';
import type { Product, StockItem, FamilyGroup, User } from '../shared/types';
import type { ParsedCode } from '../shared/barcode';
export type Warehouse = { id: string; name: string; group_id: string };
export type ProductDraft = z.infer<typeof productSchema>;
const blank: ProductDraft = { name: '', generic_name: null, manufacturer: null, specification: null, indications: null, precautions: null, base_unit: '片', units_per_package: 1, sources: [], status: 'confirmed' };
export function Sources({ sources }: { sources: { title: string; url: string }[] }) {
  return sources.length > 0 ? <div className="sources"><h3>资料来源，请打开核对</h3>{sources.map((s, i) => /^https?:\/\//.test(s.url) ? <a href={s.url} key={i} target="_blank" rel="noopener noreferrer">{s.title || s.url} ↗</a> : null)}</div> : null;
}
export function ProductFields({ value: d, set }: { value: ProductDraft; set: (d: ProductDraft) => void }) {
  const patch = (key: keyof ProductDraft, value: unknown) => set({ ...d, [key]: value });
  return <>
    <label>药品名称<input value={d.name} required maxLength={200} onChange={e => patch('name', e.target.value)} /></label>
    <div className="form-row"><label>基本单位<input value={d.base_unit} required maxLength={20} onChange={e => patch('base_unit', e.target.value)} /></label>
      <label>每盒数量<input type="number" min="0.001" step="0.001" max="100000" value={d.units_per_package} required onChange={e => patch('units_per_package', Number(e.target.value))} /></label></div>
    {(['generic_name', 'manufacturer', 'specification'] as const).map(key => <label key={key}>{({ generic_name: '通用名', manufacturer: '生产厂家', specification: '规格' })[key]}<input value={d[key] || ''} maxLength={key === 'generic_name' ? 200 : 300} onChange={e => patch(key, e.target.value || null)} /></label>)}
    <label>适应症（只抄录资料）<textarea value={d.indications || ''} maxLength={4000} onChange={e => patch('indications', e.target.value || null)} /></label>
    <label>注意事项<textarea value={d.precautions || ''} maxLength={4000} onChange={e => patch('precautions', e.target.value || null)} /></label>
    <label className="check"><input type="checkbox" checked={d.status === 'pending'} onChange={e => patch('status', e.target.checked ? 'pending' : 'confirmed')} />先存下，让家人补充（待补充药品）</label>
    <Sources sources={d.sources} />
  </>;
}
export function Intake({ defaultCode = '', products, warehouses, close, saved }: {
  defaultCode?: string; products: Product[]; warehouses: Warehouse[]; close: () => void; saved: () => void;
}) {
  const [code, setCode] = useState(defaultCode), [productId, setProductId] = useState(''), [draft, setDraft] = useState<ProductDraft>(blank);
  const [warehouse, setWarehouse] = useState(warehouses[0]?.id || ''), [expiry, setExpiry] = useState(''), [batch, setBatch] = useState('');
  const [packages, setPackages] = useState(1), [size, setSize] = useState(1), [query, setQuery] = useState(''), [progress, setProgress] = useState('');
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [scanning, setScanning] = useState(false), [aiBusy, setAiBusy] = useState(false);
  const [aiFilled, setAiFilled] = useState(false), [duplicate, setDuplicate] = useState(false);
  const choose = (id: string) => {
    setProductId(id); const p = products.find(p => p.id === id);
    if (p) setSize(p.units_per_package);
  };
  const resolve = useCallback(async (raw: string) => {
    setCode(raw); setError(''); setProgress('正在查找本群药品和缓存…');
    setDuplicate(false); setProductId(''); setDraft(blank); setAiFilled(false);
    try {
      const r = await api<{ parsed: ParsedCode; product: Product | null; box: unknown; cache: Partial<ProductDraft> | null }>(`/codes/resolve?code=${encodeURIComponent(raw)}`);
      if (r.box) { setDuplicate(true); setError('这盒追溯码已入库，不能再次添加。'); }
      if (r.product) { setProductId(r.product.id); setSize(r.product.units_per_package); }
      else if (r.cache) {
        const data = { ...blank, ...r.cache }; setDraft(data); setSize(data.units_per_package || 1); setAiFilled(true);
      }
      setExpiry(r.parsed.expiry_date || ''); setBatch(r.parsed.batch_number || '');
      setProgress(r.product ? '已找到本群药品，请核对药盒。' : r.cache ? '已找到全局缓存，仅预填，请对照药盒确认。' : '未找到药品，可用 AI 查询或手动填写。');
    } catch (e) { setError(errorText(e)); setProgress(''); }
  }, []);
  useEffect(() => { if (defaultCode) void resolve(defaultCode); }, [defaultCode, resolve]);
  const scan = useCallback((s: string) => { setScanning(false); void resolve(s); }, [resolve]);
  const lookup = async () => {
    setAiBusy(true); setError(''); setProductId(''); setProgress('正在开始查询…');
    try {
      await sse('/ai/lookup', { query: [code, query || draft.name].filter(Boolean).join(' ') }, (event, data) => {
        if (event === 'progress') setProgress(String(data));
        if (event === 'result') {
          const r = data as Partial<ProductDraft>;
          const next = { ...blank, ...r, name: r.name || '', base_unit: r.base_unit || '片', units_per_package: r.units_per_package || 1 };
          setDraft(next); setSize(next.units_per_package); setAiFilled(true); setProgress('AI 已预填，请逐项核对，不会自动保存。');
        }
      });
    } catch (e) { setError(errorText(e)); } finally { setAiBusy(false); }
  };
  const product = products.find(p => p.id === productId);
  return <><Modal title="放进药箱" close={() => { if (!busy && !aiBusy) close(); }}>
    <form onSubmit={e => {
      e.preventDefault(); setBusy(true); setError('');
      (async () => {
        if (duplicate) throw new Error('这盒已入库，请取消或更换药盒码');
        let id = productId;
        if (!id) {
          const parsed = productSchema.parse({ ...draft, code: code || undefined });
          const r = await api<{ id: string }>('/products', 'POST', parsed);
          id = r.id; setProductId(id); setSize(parsed.units_per_package);
        }
        await api('/stock', 'POST', { product_id: id, warehouse_id: warehouse, code: code || undefined, expiry_date: expiry || null,
          batch_number: batch || null, packages, units_per_package: productId ? size : draft.units_per_package });
        saved(); close();
      })().catch(e => setError(errorText(e))).finally(() => setBusy(false));
    }}>
      <div className="actions"><button type="button" className="secondary" disabled={busy || aiBusy} onClick={() => setScanning(true)}>扫一扫药盒</button></div>
      <label>药盒码（可不填）<input value={code} maxLength={4096} onChange={e => { setCode(e.target.value); setDuplicate(false); }} /></label>
      <button type="button" className="plain" disabled={!code || busy || aiBusy} onClick={() => void resolve(code)}>查找这个码</button>
      {progress && <Message>{progress}</Message>}
      <label>选择本群已有药品<select value={productId} onChange={e => choose(e.target.value)}><option value="">新药品，确认后创建</option>{products.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
      {product ? <div className="medicine-preview"><h3>{product.name}</h3><p>{product.specification} · {product.manufacturer}</p><Speak text={`${product.name}，${product.specification || ''}。${product.precautions || ''}`} /></div> : productId ? <Message>药品信息已创建，确认入库即可。</Message> : <>
        <label>补充药名，帮助 AI 查询<input value={query} maxLength={300} onChange={e => setQuery(e.target.value)} placeholder="例如药盒上的药名、厂家" /></label>
        <button type="button" className="secondary full" disabled={aiBusy || busy || !(query || code || draft.name)} onClick={() => void lookup()}>{aiBusy ? '正在查询…' : 'AI 帮我识别（只预填）'}</button>
        {aiFilled && <Message warning>AI 和缓存可能出错，未知字段不会自动补全。请对照药盒及来源逐项核对。</Message>}
        <button type="button" className="plain full" onClick={() => setDraft(prev => ({ ...prev, name: prev.name || '待识别药品', status: 'pending' }))}>先存下，让家人补充</button>
        <ProductFields value={draft} set={setDraft} />
      </>}
      <hr /><h3>这盒药放在哪里</h3>
      <label>药箱<select value={warehouse} onChange={e => setWarehouse(e.target.value)} required><option value="" disabled>请选择药箱</option>{warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label>
      <div className="form-row"><label>有效期<input type="date" value={expiry} onChange={e => setExpiry(e.target.value)} /></label><label>批号<input value={batch} maxLength={100} onChange={e => setBatch(e.target.value)} /></label></div>
      <div className="form-row"><label>盒数<input type="number" min="1" max="100" value={packages} required onChange={e => setPackages(Number(e.target.value))} /></label>
        {productId && <label>每盒数量<input type="number" min="0.001" max="100000" step="0.001" value={size} required onChange={e => setSize(Number(e.target.value))} /></label>}</div>
      <p className="fine">有追溯码时一次只能入库一盒。每盒单独管理剩余数量。</p>
      {error && <Message warning>{error}</Message>}<button type="submit" className="full" disabled={busy || aiBusy || duplicate || !warehouse}>{busy ? '正在保存…' : productId ? '确认这盒信息，放进药箱' : '已核对药品信息，创建并入库'}</button>
    </form>
  </Modal>{scanning && <Scanner onCode={scan} close={() => setScanning(false)} />}</>;
}
export function InventoryPage({ family, user, products, warehouses, version, changed, intake, openIntake }: {
  family: FamilyGroup; user: User; products: Product[]; warehouses: Warehouse[]; version: number; changed: () => void; intake?: { code?: string }; openIntake: (v?: { code?: string }) => void;
}) {
  const { data, error } = useLoad<StockItem[]>('/stock', `${family.id}:${version}`);
  const [tab, setTab] = useState('stock'), [filter, setFilter] = useState(''), [editing, setEditing] = useState<Product>();
  const [operation, setOperation] = useState<{ box: StockItem; type: 'out' | 'discard' | 'move' }>();
  const [amount, setAmount] = useState(1), [to, setTo] = useState(warehouses[0]?.id || ''), [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false), [editDraft, setEditDraft] = useState<ProductDraft>(blank);
  const clock = localClock(new Date(), family.timezone);
  const visible = data?.filter(s => s.status === 'active' && s.name.includes(filter));
  return <>
    <div className="section-heading"><div><p className="eyebrow">FAMILY MEDICINE CABINET</p><h1>我的药箱</h1><p className="muted">每一盒放在哪里，还剩多少，一目了然。</p></div><button onClick={() => openIntake({})}>＋ 放进药箱</button></div>
    <div className="stats"><div><strong>{data?.filter(s => s.status === 'active').length ?? '—'}</strong><span>盒在药箱</span></div><div><strong>{products.length}</strong><span>种药品</span></div><div><strong>{data?.filter(s => s.status === 'active' && s.expiry_date && dayNumber(s.expiry_date) - dayNumber(clock.date) <= 30).length ?? '—'}</strong><span>盒过期或临期</span></div></div>
    <div className="segmented"><button className={tab === 'stock' ? 'active' : ''} onClick={() => setTab('stock')}>在库药盒</button><button className={tab === 'products' ? 'active' : ''} onClick={() => setTab('products')}>药品资料 / 待补充</button><button className={tab === 'history' ? 'active' : ''} onClick={() => setTab('history')}>库存流水</button></div>
    {error && <Message warning>{error}</Message>}
    {tab === 'stock' ? <>
      <label className="search-label">查找药品<input type="search" value={filter} onChange={e => setFilter(e.target.value)} placeholder="输入药品名称" /></label>
      {visible?.length === 0 && <Empty title={filter ? '没找到这种药' : '药箱还是空的'}>点「放进药箱」，添加第一盒药。</Empty>}
      <div className="card-grid">{visible?.map(s => {
        const days = s.expiry_date ? dayNumber(s.expiry_date) - dayNumber(clock.date) : null;
        return <section className="panel stock-card" key={s.id}><div className="between"><span className="badge neutral">{s.warehouse_name}</span><span className="muted">{s.opened ? '已开封' : '未开封'}</span></div>
          <h2>{s.name}</h2><div className="dose-amount">{s.units_remaining}<span> {s.base_unit} 剩余</span></div>
          <p>1 盒 · 每盒 {s.units_per_package} {s.base_unit}</p><div className="stock-meter"><span style={{ width: `${Math.min(100, s.units_remaining / s.units_per_package * 100)}%` }} /></div>
          <p className={days !== null && days <= 30 ? 'expiry-warning' : 'muted'}>{days === null ? '有效期未填写' : days < 0 ? `⚠ 已过期 · ${s.expiry_date}` : days <= 30 ? `⚠ 临近到期 · ${s.expiry_date}` : `有效期 ${s.expiry_date}`}</p>
          <p className="fine">药盒 {s.trace_code || s.id.slice(-8)}</p>
          <div className="actions"><button className="secondary" onClick={() => { setOperation({ box: s, type: 'out' }); setAmount(1); setActionError(''); }}>用掉了</button>
            {!user.is_elderly && <button className="secondary" onClick={() => { setOperation({ box: s, type: 'move' }); setActionError(''); }}>移库</button>}
            <button className="plain danger-text" onClick={() => { setOperation({ box: s, type: 'discard' }); setActionError(''); }}>扔掉了</button></div>
        </section>;
      })}</div>
    </> : tab === 'products' ? <div className="card-grid">{products.map(p => <section className="panel" key={p.id}><span className={`badge ${p.status === 'pending' ? 'attention' : ''}`}>{p.status === 'pending' ? '！待家人补充' : '✓ 已确认'}</span><h2>{p.name}</h2><p>{p.manufacturer || '生产厂家待补充'}</p><p>{p.specification || '规格待补充'}</p><Speak text={`${p.name}。${p.indications || ''}。${p.precautions || ''}`} />
      {user.role === 'admin' && <button className="secondary" onClick={() => {
        setEditing(p); setEditDraft({ ...p, sources: JSON.parse(p.sources) }); setActionError('');
      }}>修改药品资料</button>}</section>)}</div> : <Movements family={family} version={version} />}
    {intake && <Intake defaultCode={intake.code} products={products} warehouses={warehouses} close={() => openIntake(undefined)} saved={changed} />}
    {operation && <Modal title={operation.type === 'out' ? '确认用掉的数量' : operation.type === 'move' ? '把这盒药移到哪里' : '确认扔掉这盒药'} close={() => { if (!busy) setOperation(undefined); }}>
      <h3>{operation.box.name}</h3><p>在 {operation.box.warehouse_name}，剩余 {operation.box.units_remaining} {operation.box.base_unit}</p>
      {operation.type === 'out' && <><p className="muted">这只记录出库。服药计划请回首页勾选，避免重复扣库存。</p><label>用掉数量<input type="number" min="0.001" max={operation.box.units_remaining} step="0.001" value={amount} onChange={e => setAmount(Number(e.target.value))} /></label></>}
      {operation.type === 'move' && <label>目标药箱<select value={to} onChange={e => setTo(e.target.value)}><option value="">请选择</option>{warehouses.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label>}
      {operation.type === 'discard' && <Message warning>这盒剩余药将全部报废，不能在这里撤销。</Message>}
      {actionError && <Message warning>{actionError}</Message>}
      <button disabled={busy} onClick={() => {
        setBusy(true); api(`/stock/${operation.box.id}/move`, 'POST', { type: operation.type, amount, warehouse_id: to || undefined })
          .then(() => { changed(); setOperation(undefined); }).catch(e => setActionError(errorText(e))).finally(() => setBusy(false));
      }}>{busy ? '正在记录…' : '确认'}</button>
    </Modal>}
    {editing && <Modal title="核对药品资料" close={() => { if (!busy) setEditing(undefined); }}><form onSubmit={e => {
      e.preventDefault(); setBusy(true);
      api(`/products/${editing.id}`, 'PUT', editDraft).then(() => { changed(); setEditing(undefined); }).catch(e => setActionError(errorText(e))).finally(() => setBusy(false));
    }}><ProductFields value={editDraft} set={setEditDraft} />{actionError && <Message warning>{actionError}</Message>}<button disabled={busy}>确认修改药品</button></form></Modal>}
  </>;
}
function Movements({ family, version }: { family: FamilyGroup; version: number }) {
  const { data, error } = useLoad<{ id: string; type: string; name: string; username: string; amount: number; created_at: string }[]>('/movements', `${family.id}:${version}`);
  return <>{error && <Message warning>{error}</Message>}<section className="panel"><h2>最近 500 条流水</h2>{data?.map(m => <div className="history-row" key={m.id}><strong>{m.name}</strong><span>{({ in: '入库', out: '用掉', move: '移库', discard: '报废', dose: '服药', undo_dose: '撤销服药' } as Record<string, string>)[m.type]} {m.amount}</span><small>{m.username} · {m.created_at} UTC</small></div>)}</section></>;
}
