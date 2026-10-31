import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { Modal } from '../src/client/components';
import { ErrorBoundary } from '../src/client/ErrorBoundary';
import { asNeededTodo } from '../src/client/Plans';
import { parseProposal } from '../src/client/chat-drafts';
import { uuid } from '../src/shared/uuid';
import { runChat } from '../src/shared/ai-agent';
import { aiSettingsSchema } from '../src/shared/ai';
import { planSchema } from '../src/shared/schemas';
import type { Plan } from '../src/shared/types';

vi.mock('../src/client/api', () => ({ api: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
function legacyCrypto() {
  vi.stubGlobal('crypto', { getRandomValues: crypto.getRandomValues.bind(crypto) });
}
const draft = planSchema.parse({ user_id: 'u', medicine_name: '测试药', amount: 1, unit: '片',
  frequency: 'as_needed', times: [], start_date: '2026-10-31' });

describe('old Android WebView compatibility', () => {
  it('uses the native UUID API when present', () => {
    const randomUUID = vi.fn(() => '00000000-0000-4000-8000-000000000000');
    vi.stubGlobal('crypto', { randomUUID, getRandomValues: vi.fn() });
    expect(uuid()).toBe('00000000-0000-4000-8000-000000000000');
    expect(randomUUID).toHaveBeenCalledOnce();
    expect(crypto.getRandomValues).not.toHaveBeenCalled();
  });
  it.each([0, 255])('sets UUID version and variant bits for random byte %i', fill => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => bytes.fill(fill));
    vi.stubGlobal('crypto', { getRandomValues });
    expect(uuid()).toBe(fill ? 'ffffffff-ffff-4fff-bfff-ffffffffffff' : '00000000-0000-4000-8000-000000000000');
    expect(getRandomValues).toHaveBeenCalledOnce();
    expect(getRandomValues.mock.calls[0][0]).toHaveLength(16);
  });
  it('renders distinct, labelled modals without either UUID API', () => {
    vi.stubGlobal('crypto', {});
    const html = renderToStaticMarkup(<>
      <Modal title="家人详情" close={() => {}}>今日服药</Modal>
      <Modal title="第二个弹窗" close={() => {}}>核对资料</Modal>
    </>);
    const ids = [...html.matchAll(/aria-labelledby="([^"]+)"/g)].map(m => m[1]);
    expect(ids).toHaveLength(2);
    expect(new Set(ids).size).toBe(2);
    for (const id of ids) expect(html).toContain(`<h2 id="${id}">`);
    expect(html).toContain('家人详情');
    expect(html).toContain('aria-label="关闭弹窗"');
  });
  it('prepares as-needed confirmations without writing a dose', () => {
    legacyCrypto();
    const plan: Plan = { ...draft, id: 'p', group_id: 'g', times: '[]', weekdays: '[]', enabled: 1,
      notify_family: 0, created_by: 'u', updated_by: 'u', last_stock_item_id: null, preferred_stock_item_id: null };
    const first = asNeededTodo(plan, 'Asia/Shanghai'), second = asNeededTodo(plan, 'Asia/Shanghai');
    expect(first).toMatchObject({ plan, state: 'pending' });
    expect(first.key).toMatch(uuidV4);
    expect(first.scheduled_time.slice('as-needed:'.length)).toMatch(uuidV4);
    expect(first.local_date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(first.scheduled_time).not.toBe(second.scheduled_time);
  });
  it('accepts old draft events and keeps provided draft IDs', () => {
    legacyCrypto();
    const payload = { operation: 'create', draft };
    const first = parseProposal(payload, 'u'), second = parseProposal(payload, 'u');
    expect(first.draft_id).toMatch(uuidV4);
    expect(first.draft_id).not.toBe(second.draft_id);
    expect(parseProposal({ ...payload, draft_id: 'existing' }, 'u').draft_id).toBe('existing');
  });
  it('stages phone-side AI drafts without randomUUID and deduplicates repeated tools', async () => {
    legacyCrypto();
    let requests = 0;
    const events: [string, unknown][] = [];
    const success = await runChat({
      settings: aiSettingsSchema.parse({}), key: 'placeholder', intent: 'draft',
      messages: [{ role: 'user', content: '生成草稿' }],
      executeTool: async () => ({ draft: { operation: 'create', draft } }),
      emit: async (event, data) => { events.push([event, data]); },
      http: async () => {
        const delta = requests++ === 0 ? { tool_calls: [0, 1].map(index => ({
          index, id: `call-${index}`, type: 'function', function: { name: 'add_plan_draft', arguments: '{}' },
        })) } : { content: '请核对后保存' };
        return new Response(`data: ${JSON.stringify({ choices: [{ delta }] })}\n\ndata: [DONE]\n\n`);
      },
    });
    expect(success).toBe(true);
    const drafts = events.filter(([event]) => event === 'draft');
    expect(drafts).toHaveLength(1);
    expect(parseProposal(drafts[0][1], 'u').draft_id).toMatch(uuidV4);
    expect(events.some(([event]) => event === 'draft_error')).toBe(false);
    expect(events.at(-1)).toEqual(['done', { drafts_added: 1, needs_details: false }]);
  });
});

describe('render error recovery', () => {
  it('keeps normal content and offers a reload action after a failure', () => {
    const boundary = new ErrorBoundary({ children: <p>家药箱</p> });
    expect(renderToStaticMarkup(boundary.render())).toBe('<p>家药箱</p>');
    boundary.state = ErrorBoundary.getDerivedStateFromError();
    const recovery = boundary.render();
    const html = renderToStaticMarkup(recovery);
    expect(html).toContain('role="alert"');
    expect(html).toContain('页面暂时无法显示');
    expect(html).toContain('重新加载');
    expect(html).not.toContain('<p>家药箱</p>');
  });
});
