import { beforeEach, describe, expect, it } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { localClock } from '../src/shared/schedule';
import type { StockItem } from '../src/shared/types';

async function call(path: string, method = 'GET', body?: object, token = 'admin', headers: Record<string, string> = {}) {
  return app.request(`https://home.test/api${path}`, { method,
    headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', ...headers },
    body: body ? JSON.stringify(body) : undefined,
  }, env);
}
const product = { name: '原药名', generic_name: '通用名', manufacturer: '厂商', specification: '10mg', indications: '抄录资料', precautions: '核对说明书',
  base_unit: '片', units_per_package: 10, status: 'confirmed', sources: [], codes: ['old-code'] };
const metadata = (extra: object = {}) => ({ expiry_date: '2028-01-01', batch_number: ' BATCH-NEW ', serial_number: 'SERIAL',
  trace_code: '81234567890123456789', units_per_package: 12, opened: true, expected_version: 0, ...extra });
const box = () => env.DB.prepare("SELECT * FROM stock_items WHERE id='box'").first<StockItem>();
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','他家','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('a','管理','admin','g'),('u','家人','user','g')"),
    env.DB.prepare("INSERT INTO products(id,group_id,name,created_by,units_per_package) VALUES('med','g','原药名','a',10),('med2','g','另一药','a',10),('foreign','other','他家药','a',10)"),
    env.DB.prepare("INSERT INTO product_codes(group_id,code,product_id) VALUES('g','old-code','med'),('g','taken-code','med2'),('g','9999999','med2')"),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','药箱'),('foreign-w','other','他家药箱')"),
    env.DB.prepare(`INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining,expiry_date,batch_number)
      VALUES('box','g','med','w',10,10,'2027-01-01','OLD'),('foreign-box','other','foreign','foreign-w',10,10,NULL,NULL)`),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,product_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('p','g','u','med','原药名',1,'片','["08:00"]','daily','2020-01-01','a','a')`),
  ]);
  for (const [token, user] of [['admin', 'a'], ['member', 'u']]) {
    await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash(token), user, Date.now() + 86400000).run();
  }
});
describe('administrator product and box editing', () => {
  it('restricts editing and code access by role, session and actual family', async () => {
    expect((await call('/stock/box', 'PUT', metadata(), 'none')).status).toBe(401);
    expect((await call('/stock/box', 'PUT', metadata(), 'member')).status).toBe(403);
    expect((await call('/products/med', 'PUT', product, 'member')).status).toBe(403);
    expect((await call('/products/med/codes', 'GET', undefined, 'member')).status).toBe(403);
    expect((await call('/stock/foreign-box', 'PUT', metadata())).status).toBe(404);
    expect((await call('/products/foreign/codes')).status).toBe(404);
    expect((await call('/stock/box', 'PUT', metadata(), 'admin', { Origin: 'https://evil.test' })).status).toBe(403);
    await env.DB.prepare("UPDATE sessions SET binding_only=1 WHERE user_id='a'").run();
    expect((await call('/stock/box', 'PUT', metadata())).status).toBe(403);
  });
  it('updates all box metadata and barcode resolution without changing inventory or dose history', async () => {
    expect((await call('/stock/box', 'PUT', metadata())).status).toBe(200);
    expect(await box()).toMatchObject({ expiry_date: '2028-01-01', batch_number: 'BATCH-NEW', serial_number: 'SERIAL',
      trace_code: '81234567890123456789', units_per_package: 12, units_remaining: 10, product_id: 'med', opened: 1, metadata_version: 1 });
    const resolved = await (await call('/codes/resolve?code=81234567890123456789')).json() as { product: { id: string } };
    expect(resolved.product.id).toBe('med');
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM stock_movements').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM dose_logs').first('n')).toBe(0);
    expect(await env.DB.prepare("SELECT action,outcome,target_id FROM audit_logs WHERE action='stock.update'").first())
      .toEqual({ action: 'stock.update', outcome: 'success', target_id: 'box' });
    expect((await call('/stock/box', 'PUT', metadata({ expiry_date: null, batch_number: null, serial_number: null, trace_code: null, expected_version: 1 }))).status).toBe(200);
    expect(await box()).toMatchObject({ expiry_date: null, batch_number: null, serial_number: null, trace_code: null, metadata_version: 2 });
  });
  it('rejects duplicate traces or a prefix owned by another product atomically', async () => {
    await env.DB.prepare("INSERT INTO stock_items(id,group_id,product_id,warehouse_id,trace_code,units_per_package,units_remaining) VALUES('box2','g','med','w','81234567890123456789',10,10)").run();
    expect((await call('/stock/box', 'PUT', metadata())).status).toBe(409);
    expect((await call('/stock/box', 'PUT', metadata({ trace_code: '99999991234567890123' }))).status).toBe(409);
    await expect(env.DB.prepare("UPDATE stock_items SET trace_code='99999991234567890123',batch_number='NO' WHERE id='box'").run())
      .rejects.toThrow('CODE_CONFLICT');
    expect(await box()).toMatchObject({ expiry_date: '2027-01-01', batch_number: 'OLD', trace_code: null, metadata_version: 0 });
  });
  it('rejects stale metadata forms and forms made stale by inventory or dose changes', async () => {
    expect((await call('/stock/box', 'PUT', metadata())).status).toBe(200);
    expect((await call('/stock/box', 'PUT', metadata({ batch_number: 'STALE' }))).status).toBe(409);
    expect((await call('/stock/box/move', 'POST', { type: 'out', amount: 1 })).status).toBe(200);
    expect((await call('/stock/box', 'PUT', metadata({ expected_version: 1 }))).status).toBe(409);
    const now = new Date(), date = localClock(now, 'Asia/Shanghai').date;
    const recorded = await call('/doses', 'POST', { plan_id: 'p', local_date: date, scheduled_time: '08:00', status: 'taken',
      amount: 2, taken_at: now.toISOString(), stock_item_id: 'box' }, 'member');
    expect(recorded.status).toBe(201);
    expect((await call('/stock/box', 'PUT', metadata({ expected_version: 2 }))).status).toBe(409);
    expect(await box()).toMatchObject({ units_remaining: 7, metadata_version: 3, batch_number: 'BATCH-NEW' });
    expect((await call('/stock/box', 'PUT', metadata({ expected_version: 3, units_per_package: 8 }))).status).toBe(409);
    expect((await call('/stock/box', 'PUT', metadata({ expected_version: 3, units_per_package: 9 }))).status).toBe(200);
    const dose = await recorded.json() as { id: string };
    expect((await call(`/doses/${dose.id}/undo`, 'POST', {}, 'member')).status).toBe(200);
    expect(await box()).toMatchObject({ units_remaining: 9, units_per_package: 9, metadata_version: 5 });
  });
  it.each(['empty', 'discarded'])('can correct metadata on %s boxes without reviving inventory', async status => {
    expect((await call('/stock/box/move', 'POST', status === 'empty' ? { type: 'out', amount: 10 } : { type: 'discard' })).status).toBe(200);
    expect((await call('/stock/box', 'PUT', metadata({ expected_version: 1, units_per_package: 1 }))).status).toBe(200);
    expect(await box()).toMatchObject({ status, units_remaining: 0, units_per_package: 1, batch_number: 'BATCH-NEW', metadata_version: 2 });
  });
  it('validates dates, code formats, precision and capacity only at submission', async () => {
    for (const extra of [{ expiry_date: '2026-02-30' }, { trace_code: '123' }, { units_per_package: 0 }, { units_per_package: 0.0001 },
      { batch_number: 'x'.repeat(101) }, { expected_version: -1 }]) {
      expect((await call('/stock/box', 'PUT', metadata(extra))).status).toBe(400);
    }
    expect((await call('/stock/box', 'PUT', metadata({ units_per_package: 9 }))).status).toBe(409);
    expect(await box()).toMatchObject({ metadata_version: 0, units_remaining: 10 });
  });
  it('edits shared fields, sources and barcode keys without rewriting box snapshots', async () => {
    const body = { ...product, name: '改后药名', units_per_package: 20, sources: [{ title: '核对的说明书', url: 'https://example.com/info' }], codes: ['new-code'] };
    expect((await call('/products/med', 'PUT', body)).status).toBe(200);
    expect(await env.DB.prepare("SELECT name,units_per_package,sources,updated_by FROM products WHERE id='med'").first())
      .toEqual({ name: '改后药名', units_per_package: 20, sources: JSON.stringify(body.sources), updated_by: 'a' });
    expect(await (await call('/products/med/codes')).json()).toEqual(['new-code']);
    expect(await box()).toMatchObject({ expiry_date: '2027-01-01', batch_number: 'OLD', units_per_package: 10, units_remaining: 10 });
    expect((await call('/products/med', 'PUT', { ...body, base_unit: '毫升' })).status).toBe(409);
    expect((await call('/products/med', 'PUT', { ...body, sources: [{ title: '错误', url: 'javascript:alert(1)' }] })).status).toBe(400);
  });
  it('rolls back all product and barcode edits when a code belongs to another medicine', async () => {
    expect((await call('/products/med', 'PUT', { ...product, name: '不应保存', codes: ['taken-code'] })).status).toBe(409);
    expect(await env.DB.prepare("SELECT name FROM products WHERE id='med'").first('name')).toBe('原药名');
    expect(await (await call('/products/med/codes')).json()).toEqual(['old-code']);
  });
});
