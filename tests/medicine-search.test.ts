import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { saveApiKey } from '../src/worker/ai-config';
import { medicineCandidates } from '../src/worker/medicine-search';
import { mockFetch } from './fetch';

const stream = (delta: unknown) => new Response(`data: ${JSON.stringify({ choices: [{ delta }] })}\n\ndata: [DONE]\n\n`, { headers: { 'Content-Type': 'text/event-stream' } });
const invoke = (name: string, args: object) => stream({ tool_calls: [{ index: 0, id: 'call-test', type: 'function',
  function: { name, arguments: JSON.stringify(args) } }] });
async function chat() {
  return app.request('https://home.test/api/ai/chat', { method: 'POST',
    headers: { Origin: 'https://home.test', Cookie: 'hm_session=member', 'Content-Type': 'application/json', 'X-Group-Id': 'other' },
    body: JSON.stringify({ user_id: 'u', messages: [{ role: 'user', content: '帮我记录原来那盒慢放的甲药，每天八点一片，我会核对药盒。' }] }),
  }, env);
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','另一家','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','家人','user','g')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('member'), 'u', Date.now() + 86400000),
    env.DB.prepare(`INSERT INTO products(id,group_id,name,generic_name,manufacturer,specification,indications,base_unit,units_per_package,created_by)
      VALUES('m10','g','正式药品甲','测试通用名','测试甲厂','缓释片 10mg','演示用长期处方资料，不作医疗用途','片',10,'u'),
        ('m20','g','正式药品乙',NULL,NULL,'胶囊',NULL,'粒',20,'u'),
        ('private','other','他群私有药',NULL,NULL,NULL,NULL,'片',10,'u')`),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','药箱')"),
    env.DB.prepare("INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining) VALUES('box','g','m10','w',10,8)"),
  ]);
});
afterEach(() => vi.restoreAllMocks());
describe('read-only medicine catalogue tools', () => {
  it('searches generic names, manufacturers, formulations, specifications and descriptions within the real group', async () => {
    for (const query of ['正式药品甲', '通用名', '甲厂', '缓释', '10MG', '长期处方', '无匹配 甲厂']) {
      const result = await medicineCandidates(env.DB, 'g', query);
      expect(result.medicines.map(p => p.id)).toEqual(['m10']);
      expect(result.medicines[0].stock_units).toBe(8);
    }
    expect((await medicineCandidates(env.DB, 'g', '他群私有药')).medicines).toHaveLength(0);
    expect((await medicineCandidates(env.DB, 'g', "' OR 1=1 --")).medicines).toHaveLength(0);
  });
  it('browses bounded, paginated metadata for semantic matching without leaking other families', async () => {
    for (let i = 0; i < 25; i++) await env.DB.prepare('INSERT INTO products(id,group_id,name,indications,created_by) VALUES(?,?,?,?,?)')
      .bind(`z${String(i).padStart(2, '0')}`, 'g', `虚构药${i}`, '演示'.repeat(1000), 'u').run();
    const first = await medicineCandidates(env.DB, 'g');
    expect(first.medicines).toHaveLength(20);
    const second = await medicineCandidates(env.DB, 'g', '', first.next_cursor!);
    expect(second.medicines).toHaveLength(7);
    expect(second.next_cursor).toBeNull();
    const all = [...first.medicines, ...second.medicines];
    expect(new Set(all.map(p => p.id)).size).toBe(27);
    expect(all.map(p => p.id)).not.toContain('private');
    expect(all.every(p => (p.indications?.length || 0) <= 600)).toBe(true);
  });
  it('roundtrips a colloquial lookup through real database candidates and canonicalizes the proposal name', async () => {
    await saveApiKey(env, 'llm', 'mock-key');
    const draft = { user_id: 'u', product_id: 'm10', medicine_name: '口语简称', amount: 1, unit: '片',
      frequency: 'daily', times: ['08:00'], start_date: '2026-01-01' };
    const mock = mockFetch(
      async request => {
        const body = await request.json() as { tools: { function: { name: string } }[]; messages: { content: string }[] };
        expect(body.tools.map(t => t.function.name)).toContain('list_medicines');
        expect(body.messages[0].content).toContain('不能把关键词无匹配说成数据库没有此药');
        return invoke('find_medicines', { query: '口语俗称', group_id: 'other' });
      },
      async request => {
        const body = await request.json() as { messages: { role: string; content: string }[] };
        expect(JSON.parse(body.messages.at(-1)!.content).medicines).toEqual([]);
        return invoke('list_medicines', { group_id: 'other', sql: 'DELETE FROM products' });
      },
      async request => {
        const body = await request.json() as { messages: { content: string }[] };
        const found = JSON.parse(body.messages.at(-1)!.content);
        expect(found.medicines.map((p: { id: string }) => p.id)).toEqual(['m10', 'm20']);
        expect(found.medicines[0]).toMatchObject({ generic_name: '测试通用名', manufacturer: '测试甲厂', specification: '缓释片 10mg', stock_units: 8 });
        return invoke('propose_plan', { draft });
      },
      stream({ content: '请核对药盒、规格和用量后确认草稿。' }),
    );
    const response = await chat(), body = await response.text();
    expect(body).toContain('event: draft');
    expect(body).toContain('"medicine_name":"正式药品甲"');
    expect(body).not.toContain('"medicine_name":"口语简称"');
    expect(mock.requests).toHaveLength(4);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM medication_plans').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM products').first('n')).toBe(3);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(8);
  });
  it.each(['m10', 'private'])('rejects an invented or unread product id %s even if it appears in user/model text', async productId => {
    await saveApiKey(env, 'llm', 'mock-key');
    mockFetch(invoke('propose_plan', { draft: { user_id: 'u', product_id: productId, medicine_name: '猜测药名',
      amount: 1, unit: '片', frequency: 'daily', times: ['08:00'], start_date: '2026-01-01' } }),
      async request => {
        const body = await request.json() as { messages: { content: string }[] };
        expect(body.messages.at(-1)!.content).toContain('不得凭空填写药品编号');
        return stream({ content: '请先核对本群药品资料。' });
      });
    expect(await (await chat()).text()).not.toContain('event: draft\n');
  });
});
