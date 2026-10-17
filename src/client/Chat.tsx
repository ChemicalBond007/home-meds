import { useEffect, useRef, useState } from 'react';
import { sse } from './api';
import { Message, Empty, errorText } from './components';
import { PlanEditor } from './Plans';
import { appendProposal, parseProposal } from './chat-drafts';
import type { Proposal } from '../shared/chat';
import type { FamilyGroup, Product, StockItem, User } from '../shared/types';
interface ChatMessage { role: 'user' | 'assistant'; content: string }
interface VoiceRecognition {
  lang: string; continuous: boolean;
  onresult: ((e: { results: { transcript: string }[][] }) => void) | null;
  onerror: (() => void) | null; start(): void; stop(): void;
}
export function ChatPage({ user, family, members, products, stock, changed }: {
  user: User; family: FamilyGroup; members: User[]; products: Product[]; stock?: StockItem[]; changed: () => void;
}) {
  const [target, setTarget] = useState(members.find(m => m.id === user.id)?.id || members[0]?.id || '');
  const [messages, setMessages] = useState<ChatMessage[]>([]), [text, setText] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [proposals, setProposals] = useState<Proposal[]>([]);
  const [draftNotice, setDraftNotice] = useState('');
  const [editing, setEditing] = useState<number>(), [listening, setListening] = useState(false);
  const controller = useRef<AbortController | null>(null), recognition = useRef<VoiceRecognition | null>(null);
  const Voice = (window as Window & { SpeechRecognition?: new () => VoiceRecognition; webkitSpeechRecognition?: new () => VoiceRecognition }).SpeechRecognition ||
    (window as Window & { webkitSpeechRecognition?: new () => VoiceRecognition }).webkitSpeechRecognition;
  useEffect(() => () => { controller.current?.abort(); recognition.current?.stop(); }, []);
  const send = async (intent: 'chat' | 'draft' = 'chat') => {
    const content = text.trim() || (intent === 'draft' && messages.length ? '请把上文已经明确的计划加入待确认列表；缺少信息时先问我，不要编造。' : '');
    if (!content || !target || busy) return;
    const history: ChatMessage[] = [...messages.slice(-18), { role: 'user', content }];
    setMessages([...history, { role: 'assistant', content: '' }]); setText(''); setError(''); setDraftNotice(''); setBusy(true);
    const abort = new AbortController(); controller.current = abort;
    let received = 0;
    try {
      await sse('/ai/chat', { user_id: target, messages: history, intent }, (event, value) => {
        if (event === 'delta') setMessages(prev => [...prev.slice(0, -1), { role: 'assistant', content: prev[prev.length - 1].content + String(value) }]);
        if (event === 'draft') {
          const p = parseProposal(value, target);
          received++;
          setProposals(prev => appendProposal(prev, p));
        }
        if (event === 'draft_error') setError(`草稿工具调用未成功：${(value as { message: string }).message}`);
        if (event === 'done' && !received) setDraftNotice(
          value && typeof value === 'object' && 'needs_details' in value && value.needs_details
            ? '尚需补充上面询问的信息，本轮没有新增确认卡片。'
            : '本轮没有新增确认卡片。信息齐全后，可点击「生成待确认草稿」让助手实际加入列表。');
      }, abort.signal);
    } catch (e) { if (!abort.signal.aborted) setError(errorText(e)); } finally { setBusy(false); }
  };
  const clean = (s: string) => s.replace(/<think>[\s\S]*?<\/think>/gi, '').replace(/<think>[\s\S]*$/gi, '').trim();
  return <>
    <div className="section-heading"><div><p className="eyebrow">A LITTLE HELP, ALWAYS</p><h1>对话助手</h1><p className="muted">把计划说出来，再一起确认。</p></div></div>
    <div className="chat-layout"><section className="panel chat-main">
      <label>给谁记录计划<select value={target} disabled={busy} onChange={e => { setTarget(e.target.value); setMessages([]); setProposals([]); setError(''); setDraftNotice(''); }}>{members.map(m => <option value={m.id} key={m.id}>{m.username}</option>)}</select></label>
      <Message>可以用简称或口语描述药品，助手会只读查询本家庭药品资料；不确定时请核对药盒。只负责记录，不给用药建议。时间或用量不清楚时会追问，点击确认卡片才保存。</Message>
      <div className="chat-messages" aria-live="polite" aria-relevant="additions text">
        {!messages.length && <Empty title="今天要记下什么？">例如：帮我记录每天早上八点吃一片某某药。</Empty>}
        {messages.map((m, i) => <div key={i} className={`bubble ${m.role}`}><span className="bubble-label">{m.role === 'user' ? '我' : '记录助手'}</span><p>{clean(m.content) || (busy && i === messages.length - 1 ? '正在整理…' : '请查看待确认列表和本轮状态提示。')}</p></div>)}
      </div>
      {error && <Message warning>{error}</Message>}
      {draftNotice && <Message>{draftNotice}</Message>}
      {user.is_elderly ? <div className="quick-replies">{['每天早上一次', '饭后吃', '用量以医嘱为准，我来补充'].map(t => <button className="secondary" key={t} onClick={() => setText(prev => prev + t)}>{t}</button>)}</div> : null}
      <form onSubmit={e => { e.preventDefault(); void send(); }}>
        <label>告诉助手你的计划<textarea value={text} onChange={e => setText(e.target.value)} maxLength={6000} placeholder="先说药名、用量和时间…" required /></label>
        <div className="actions"><button disabled={busy || !target}>{busy ? '正在回复…' : '发送'}</button>
          <button type="button" className="secondary" disabled={busy || !target || (!text.trim() && !messages.length)} onClick={() => void send('draft')}>生成待确认草稿</button>
          {Voice && <button type="button" className="secondary" disabled={busy} onClick={() => {
            if (listening) { recognition.current?.stop(); setListening(false); return; }
            const r = new Voice(); recognition.current = r; r.lang = 'zh-CN'; r.continuous = false;
            r.onresult = e => { setText(prev => prev + (e.results[0]?.[0]?.transcript || '')); setListening(false); };
            r.onerror = () => { setListening(false); setError('语音输入暂不可用，请键盘输入。'); };
            setListening(true); try { r.start(); } catch { setListening(false); setError('无法启动语音输入'); }
          }}>{listening ? '停止语音输入' : '语音输入'}</button>}</div>
      </form>
      <p className="fine">对话仅保存在本页内存中，每轮最多发送最近 20 条消息。刷新会清空。</p>
    </section><PendingPlans proposals={proposals} members={members} busy={busy} edit={setEditing} /></div>
    {editing !== undefined && <PlanEditor initial={proposals[editing].draft} planId={proposals[editing].operation === 'create' ? undefined : proposals[editing].plan_id} members={members} products={products} stock={stock} family={family}
      close={() => setEditing(undefined)} saved={() => { setProposals(prev => prev.map((p, i) => i === editing ? { ...p, saved: true } : p)); changed(); }} />}
  </>;
}
export function PendingPlans({ proposals, members, busy, edit }: { proposals: Proposal[]; members: User[]; busy: boolean; edit: (index: number) => void }) {
  return <aside className="drafts" aria-live="polite"><h2>待确认的计划（{proposals.filter(p => !p.saved).length}）</h2>
    {!proposals.length && <p className="muted">成功加入的草稿会出现在这里；文字回复不等于已生成卡片，也不会自动保存。</p>}
    {proposals.map((p, i) => <section className="panel draft-card" key={p.draft_id || i}><span className="badge attention">{p.saved ? '✓ 已保存' : '！尚未保存'}</span><h3>{p.operation === 'pause' ? '暂停' : p.operation === 'update' ? '修改' : '添加'}：{p.draft.medicine_name}</h3>
      <p>{members.find(m => m.id === p.draft.user_id)?.username} · 每次 {p.draft.amount} {p.draft.unit}</p><p>{p.draft.times.join(' / ') || '按需服药'} · {p.draft.start_date} 起</p>
      <button disabled={p.saved || busy} onClick={() => edit(i)}>{p.saved ? '已确认保存' : '核对草稿，确认保存'}</button></section>)}
  </aside>;
}
