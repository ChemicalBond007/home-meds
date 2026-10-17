import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { saveApiKey } from '../src/worker/ai-config';
import { localClock } from '../src/shared/schedule';
import { proposalSchema, type Proposal } from '../src/shared/chat';
import { appendProposal, parseProposal } from '../src/client/chat-drafts';
import { PendingPlans, ChatPage } from '../src/client/Chat';
import { sse } from '../src/client/api';
import type { User } from '../src/shared/types';
import { mockFetch } from './fetch';

vi.hoisted(() => vi.stubGlobal('localStorage', { getItem: () => '', setItem: () => {} }));
vi.mock('../src/client/Plans', () => ({ PlanEditor: () => null }));
const member: User = { id: 'u', username: '测试家人', role: 'user', group_id: 'g', is_elderly: 0 };
const flat = { medicine_name: '手填测试药', amount: 2, unit: '片', frequency: 'daily', times: ['08:00'] };
const stream = (...deltas: unknown[]) => new Response(deltas.map(delta => `data: ${JSON.stringify({ choices: [{ delta }] })}\n\n`).join('') + 'data: [DONE]\n\n',
  { headers: { 'Content-Type': 'text/event-stream' } });
const toolCalls = (calls: { name: string; args: object }[]) => stream({ tool_calls: calls.map((c, index) => ({
  index, id: `call-${index}`, type: 'function', function: { name: c.name, arguments: JSON.stringify(c.args) },
})) });
const invoke = (name: string, args: object) => toolCalls([{ name, args }]);
const frames = (body: string) => body.trim().split('\n\n').map(block => ({
  event: block.match(/^event: (.+)$/m)?.[1], data: JSON.parse(block.match(/^data: (.+)$/m)![1]),
}));
const cardFrames = (body: string) => frames(body).filter(f => f.event === 'draft').map(f => proposalSchema.parse(f.data));
const chat = (extra: object = {}) => app.request('https://home.test/api/ai/chat', { method: 'POST',
  headers: { Origin: 'https://home.test', Cookie: 'hm_session=member', 'Content-Type': 'application/json' },
  body: JSON.stringify({ user_id: 'u', messages: [{ role: 'user', content: '按我已经说明的安排记录计划，并加入待确认列表。' }], ...extra }),
}, env);
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','另一家','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','测试家人','user','g'),('v','同群另一人','user','g'),('x','他群人','user','other')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('member'), 'u', Date.now() + 86400000),
    env.DB.prepare("INSERT INTO products(id,group_id,name,created_by,units_per_package) VALUES('med','g','正式测试药','u',10),('foreign','other','他群私有药','u',10)"),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','药箱')"),
    env.DB.prepare("INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining) VALUES('box','g','med','w',10,5)"),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,product_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('p','g','u','med','正式测试药',1,'片','["08:00"]','daily','2020-01-01','u','u'),
        ('other-person','g','v','med','正式测试药',1,'片','["08:00"]','daily','2020-01-01','u','u')`),
  ]);
  await saveApiKey(env, 'llm', 'mock-key');
});
afterEach(() => vi.restoreAllMocks());
afterAll(() => vi.unstubAllGlobals());
async function unchanged() {
  expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM medication_plans').first('n')).toBe(2);
  expect(await env.DB.prepare("SELECT amount,enabled FROM medication_plans WHERE id='p'").first()).toEqual({ amount: 1, enabled: 1 });
  expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(5);
}
describe('chat draft staging tools', () => {
  it('roundtrips database lookup, a flat tool call, SSE decoding and a visible confirmation card without saving', async () => {
    const model = mockFetch(async request => {
      const body = await request.json() as { tools: { function: { name: string; parameters: { required: string[] } } }[] };
      const params = body.tools.find(t => t.function.name === 'add_plan_draft')!.function.parameters;
      expect(params.required).not.toContain('user_id');
      expect(params.required).not.toContain('start_date');
      return invoke('find_medicines', { query: '正式测试药' });
    }, invoke('add_plan_draft', { ...flat, product_id: 'med', user_id: 'x' }), async request => {
      const body = await request.json() as { messages: { content: string }[] };
      expect(JSON.parse(body.messages.at(-1)!.content)).toMatchObject({ status: 'pending_confirmation', draft_only: true });
      return stream({ content: '已加入待确认列表，请核对后保存。' });
    });
    const body = await (await chat()).text(), cards = cardFrames(body);
    expect(cards).toHaveLength(1);
    expect(cards[0].draft).toMatchObject({ user_id: 'u', medicine_name: '正式测试药', amount: 2, start_date: localClock(new Date(), 'Asia/Shanghai').date });
    expect(model.requests).toHaveLength(3);
    // Exercise the browser SSE reader with CRLF and one-byte packets, including
    // split UTF-8 characters and CR/LF pairs.
    const bytes = new TextEncoder().encode(body.replaceAll('\n', '\r\n'));
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(new ReadableStream({
      start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close(); },
    })));
    let queue: Proposal[] = [];
    await sse('/ai/chat', {}, (event, data) => { if (event === 'draft') queue = appendProposal(queue, parseProposal(data, 'u')); });
    const html = renderToStaticMarkup(<PendingPlans proposals={queue} members={[member]} busy={false} edit={() => {}} />);
    for (const text of ['待确认的计划（1）', '正式测试药', '08:00', '核对草稿，确认保存', '尚未保存']) expect(html).toContain(text);
    await unchanged();
  });
  it('repairs a text-only draft response once in explicit draft mode and then uses the real tool', async () => {
    const mock = mockFetch(async request => {
      expect((await request.json() as { tool_choice: string }).tool_choice).toBe('required');
      return stream({ content: '这是文字版草稿。' });
    }, async request => {
      const body = await request.json() as { tool_choice: string; messages: { content: string }[] };
      expect(body.tool_choice).toBe('required');
      expect(body.messages.at(-1)!.content).toContain('还没有生成确认卡片');
      return invoke('add_plan_draft', flat);
    }, async request => {
      expect((await request.json() as { tool_choice: string }).tool_choice).toBe('auto');
      return stream({ content: '请核对卡片。' });
    });
    expect(cardFrames(await (await chat({ intent: 'draft' })).text())).toHaveLength(1);
    expect(mock.requests).toHaveLength(3);
    await unchanged();
  });
  it('falls back to auto for a provider that rejects required, while retaining draft validation', async () => {
    const mock = mockFetch(new Response('private-provider-body', { status: 400 }), async request => {
      expect((await request.json() as { tool_choice: string }).tool_choice).toBe('auto');
      return invoke('add_plan_draft', flat);
    }, stream({ content: '请核对。' }));
    const body = await (await chat({ intent: 'draft' })).text();
    expect(cardFrames(body)).toHaveLength(1);
    expect(body).not.toContain('private-provider-body');
    expect(mock.requests).toHaveLength(3);
  });
  it('asks for missing information instead of inventing an amount or time', async () => {
    const mock = mockFetch(invoke('request_plan_details', { question: '请说明每次用量和服药时间。' }));
    const body = await (await chat({ intent: 'draft' })).text();
    expect(cardFrames(body)).toHaveLength(0);
    expect(body).toContain('请说明每次用量和服药时间。');
    expect(frames(body).at(-1)!.data).toEqual({ drafts_added: 0, needs_details: true });
    expect(mock.requests).toHaveLength(1);
    await unchanged();
  });
  it('does not claim a card exists when the model ignores the tool, and bounds the repair', async () => {
    const mock = mockFetch(stream({ content: '这只是文字草稿。' }));
    const body = await (await chat({ intent: 'draft' })).text();
    expect(cardFrames(body)).toHaveLength(0);
    expect(frames(body).at(-1)!.data).toEqual({ drafts_added: 0, needs_details: false });
    expect(mock.requests).toHaveLength(2);
    expect(await env.DB.prepare("SELECT outcome FROM audit_logs WHERE action='ai.chat'").first('outcome')).toBe('failure');
    await unchanged();
  });
  it.each([
    { ...flat, amount: 0 }, { ...flat, times: [] }, { ...flat, product_id: 'foreign' }, { ...flat, product_id: 'med' },
  ])('reports rejected fields or unread product IDs instead of silently dropping a draft: %j', async args => {
    mockFetch(invoke('add_plan_draft', args), stream({ content: '请先核对资料。' }));
    const body = await (await chat()).text();
    expect(cardFrames(body)).toHaveLength(0);
    expect(frames(body).find(f => f.event === 'draft_error')!.data.message).toBeTruthy();
    await unchanged();
  });
  it.each(['update', 'pause'])('stages %s for the selected person without changing the saved plan', async operation => {
    mockFetch(invoke(operation === 'pause' ? 'propose_plan_pause' : 'propose_plan_update', { planId: 'p', changes: { amount: 3, times: ['20:00'] } }),
      stream({ content: '请确认卡片。' }));
    const cards = cardFrames(await (await chat()).text());
    expect(cards).toHaveLength(1);
    expect(cards[0]).toMatchObject({ operation, plan_id: 'p' });
    expect(cards[0].draft).toMatchObject(operation === 'pause' ? { enabled: false } : { amount: 3, times: ['20:00'] });
    await unchanged();
  });
  it('does not stage a change to another person’s saved plan', async () => {
    mockFetch(invoke('propose_plan_update', { planId: 'other-person', changes: { amount: 3 } }), stream({ content: '请核对计划对象。' }));
    const body = await (await chat()).text();
    expect(cardFrames(body)).toHaveLength(0);
    expect(body).toContain('计划不存在或无权修改');
    expect((await chat({ user_id: 'x' })).status).toBe(400);
    await unchanged();
  });
  it.each(['update', 'pause'])('preserves the preferred box on an AI %s instead of accepting a guessed replacement', async operation => {
    await env.DB.prepare("UPDATE medication_plans SET preferred_stock_item_id='box' WHERE id='p'").run();
    mockFetch(invoke(operation === 'pause' ? 'propose_plan_pause' : 'propose_plan_update',
      { planId: 'p', changes: { amount: 3, preferred_stock_item_id: 'guessed-box' } }), stream({ content: '请核对。' }));
    const cards = cardFrames(await (await chat()).text());
    expect(cards[0].draft.preferred_stock_item_id).toBe('box');
    expect(await env.DB.prepare("SELECT preferred_stock_item_id FROM medication_plans WHERE id='p'").first('preferred_stock_item_id')).toBe('box');
    await unchanged();
  });
  it('leaves new-box selection to the human even if the model supplies a box ID', async () => {
    mockFetch(invoke('add_plan_draft', { ...flat, preferred_stock_item_id: 'box' }), stream({ content: '请核对。' }));
    expect(cardFrames(await (await chat()).text())[0].draft.preferred_stock_item_id).toBeNull();
    await unchanged();
  });
  it('clears the old box when the AI changes the product', async () => {
    await env.DB.prepare("UPDATE medication_plans SET preferred_stock_item_id='box' WHERE id='p'").run();
    await env.DB.prepare("INSERT INTO products(id,group_id,name,created_by) VALUES('next','g','另一测试药','u')").run();
    mockFetch(invoke('find_medicines', { query: '另一测试药' }),
      invoke('propose_plan_update', { planId: 'p', changes: { product_id: 'next' } }), stream({ content: '请核对。' }));
    expect(cardFrames(await (await chat()).text())[0].draft).toMatchObject({ product_id: 'next', preferred_stock_item_id: null });
    await unchanged();
  });
  it('deduplicates repeated tool calls within a turn and returns the same pending draft id', async () => {
    mockFetch(toolCalls([{ name: 'add_plan_draft', args: flat }, { name: 'add_plan_draft', args: flat }]), async request => {
      const body = await request.json() as { messages: { role: string; content: string }[] };
      const results = body.messages.filter(m => m.role === 'tool').map(m => JSON.parse(m.content));
      expect(results[0].draft_id).toBe(results[1].draft_id);
      return stream({ content: '请确认一张卡片。' });
    });
    expect(cardFrames(await (await chat()).text())).toHaveLength(1);
    await unchanged();
  });
  it('retains the five-tool cap and does not force tools once the budget is exhausted', async () => {
    mockFetch(toolCalls(Array.from({ length: 5 }, () => ({ name: 'list_medicines', args: {} }))), async request => {
      const body = await request.json() as { tools?: unknown; tool_choice?: unknown };
      expect(body.tools).toBeUndefined(); expect(body.tool_choice).toBeUndefined();
      return invoke('add_plan_draft', flat);
    }, stream({ content: '请下一轮继续。' }));
    const body = await (await chat({ intent: 'draft' })).text();
    expect(cardFrames(body)).toHaveLength(0);
    expect(body).toContain('本轮最多5次工具调用');
    await unchanged();
  });
});
describe('pending draft validation and presentation', () => {
  const payload = { draft_id: 'd1', operation: 'create', draft: { ...flat, user_id: 'u', start_date: '2026-10-17' } };
  it('does not re-add a repeated event or resurrect a saved card', () => {
    const p = parseProposal(payload, 'u'), current = [{ ...p, saved: true }];
    expect(appendProposal(current, p)).toBe(current);
    const html = renderToStaticMarkup(<PendingPlans proposals={current} members={[member]} busy={false} edit={() => {}} />);
    expect(html).toContain('待确认的计划（0）');
    expect(html).toContain('已确认保存');
  });
  it('keeps older draft events without an id compatible', () => {
    const legacy = { operation: 'create', draft: payload.draft };
    const first = parseProposal(legacy, 'u'), second = parseProposal(legacy, 'u');
    expect(first.draft_id).not.toBe(second.draft_id);
    expect(appendProposal([first], second)).toHaveLength(2);
  });
  it.each([{ ...payload, draft: { ...payload.draft, amount: 0 } }, { ...payload, operation: 'update' }, { ...payload, operation: 'unknown' }])(
    'rejects incomplete or invalid events explicitly: %j', value => {
      expect(() => parseProposal(value, 'u')).toThrow('未加入待确认列表');
    });
  it('rejects a valid card for another selected person', () => {
    expect(() => parseProposal(payload, 'v')).toThrow('不属于当前计划对象');
  });
  it('reports interrupted streams rather than treating them as completed', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('event: delta\ndata: "仅文字"\n\n'));
    await expect(sse('/ai/chat', {}, () => {})).rejects.toThrow('连接已中断');
  });
  it('offers an explicit generate-draft action without any automatic save button', () => {
    vi.stubGlobal('window', {});
    const html = renderToStaticMarkup(<ChatPage user={member} members={[member]} products={[]} changed={() => {}}
      family={{ id: 'g', name: '家庭', timezone: 'Asia/Shanghai', meal_times: '{"morning":"08:00"}' }} />);
    expect(html).toContain('生成待确认草稿');
    expect(html).toContain('文字回复不等于已生成卡片');
    expect(html).not.toContain('已核对，确认保存');
  });
});
