import { beforeEach, describe, expect, it } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import { localClock } from '../src/shared/schedule';
import type { Plan } from '../src/shared/types';
import type { PlanBoxes } from '../src/client/plan-boxes';

const date = () => localClock(new Date(), 'Asia/Shanghai').date;
const draft = (overrides: object = {}) => ({
  user_id: 'u', product_id: 'm', medicine_name: '虚构同名药', amount: 1, unit: '片', times: ['08:00'],
  frequency: 'daily', start_date: '2020-01-01', ...overrides,
});
const call = (path: string, method = 'GET', body?: object) => app.request(`https://home.test/api${path}`, {
  method, headers: { Origin: 'https://home.test', Cookie: 'hm_session=plan-test', 'Content-Type': 'application/json' },
  body: body ? JSON.stringify(body) : undefined,
}, env);
const dose = (box: string, overrides: object = {}) => ({
  plan_id: 'p', local_date: date(), scheduled_time: '08:00', status: 'taken', amount: 2,
  taken_at: new Date().toISOString(), stock_item_id: box, ...overrides,
});
const boxes = async () => await (await call('/plans/p/boxes')).json() as PlanBoxes;
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('x','其他家庭','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','家人','user','g'),('x','他人','user','x')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('plan-test'), 'u', Date.now() + 86400000),
    env.DB.prepare(`INSERT INTO products(id,group_id,name,base_unit,units_per_package,created_by) VALUES
      ('m','g','虚构同名药','片',10,'u'),('m2','g','虚构同名药','片',10,'u'),('mx','x','虚构同名药','片',10,'x')`),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','抽屉'),('wx','x','另一药箱')"),
    env.DB.prepare(`INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining,expiry_date,batch_number,status) VALUES
      ('early','g','m','w',10,10,'2090-01-01','EARLY','active'),
      ('late','g','m','w',10,10,'2099-01-01','LATE','active'),
      ('unknown','g','m','w',10,10,NULL,NULL,'active'),
      ('expired','g','m','w',10,10,'2000-01-01','OLD','active'),
      ('empty','g','m','w',10,0,'2099-01-01',NULL,'empty'),
      ('discarded','g','m','w',10,5,'2099-01-01',NULL,'discarded'),
      ('wrong-product','g','m2','w',10,10,'2099-01-01',NULL,'active'),
      ('foreign','x','mx','wx',10,10,'2099-01-01',NULL,'active')`),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,product_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('p','g','u','m','虚构同名药',1,'片','["08:00"]','daily','2020-01-01','u','u')`),
  ]);
});
describe('preferred plan boxes', () => {
  it('migrates old plans without choosing a box and persists a new preference without stock changes', async () => {
    expect((await boxes()).preferred_stock_item_id).toBeNull();
    const r = await call('/plans', 'POST', draft({ preferred_stock_item_id: 'late' }));
    expect(r.status).toBe(201);
    const { id } = await r.json() as { id: string };
    const plans = await (await call('/plans')).json() as Plan[];
    expect(plans.find(p => p.id === id)?.preferred_stock_item_id).toBe('late');
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='late'").first('units_remaining')).toBe(10);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM stock_movements').first('n')).toBe(0);
  });
  it('updates a preference, preserves omitted legacy fields, and clears it when the product changes', async () => {
    expect((await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'early' }))).status).toBe(200);
    expect((await call('/plans/p', 'PUT', draft({ notes: '改时间以外的字段' }))).status).toBe(200);
    expect((await boxes()).preferred_stock_item_id).toBe('early');
    expect((await call('/plans/p', 'PUT', draft({ product_id: 'm2' }))).status).toBe(200);
    expect((await boxes()).preferred_stock_item_id).toBeNull();
    expect((await boxes()).boxes.map(b => b.id)).toEqual(['wrong-product']);
  });
  it('supports explicitly clearing the preferred box', async () => {
    await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'early' }));
    expect((await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: null }))).status).toBe(200);
    expect((await boxes()).preferred_stock_item_id).toBeNull();
  });
  it.each(['foreign', 'wrong-product', 'missing', 'empty', 'discarded'])('rejects an invalid new preferred box: %s', async preferred_stock_item_id => {
    expect((await call('/plans', 'POST', draft({ preferred_stock_item_id }))).status).toBe(400);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM medication_plans').first('n')).toBe(1);
  });
  it('requires a product for a preference', async () => {
    expect((await call('/plans', 'POST', draft({ product_id: null, preferred_stock_item_id: 'early' }))).status).toBe(400);
  });
  it('does not expose or update another family’s box preferences', async () => {
    await env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,product_id,preferred_stock_item_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('xp','x','x','mx','foreign','他群药',1,'片','["08:00"]','daily','2020-01-01','x','x')`).run();
    expect((await call('/plans/xp/boxes')).status).toBe(404);
    expect((await call('/plans/xp', 'PUT', draft({ preferred_stock_item_id: 'early' }))).status).toBe(404);
  });
  it('returns individual batches and expiry dates, excluding unavailable and unrelated boxes', async () => {
    await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'late' }));
    const data = await boxes();
    expect(data.preferred_stock_item_id).toBe('late');
    expect(data.boxes.map(b => [b.id, b.expiry_date])).toEqual([
      ['expired', '2000-01-01'], ['early', '2090-01-01'], ['late', '2099-01-01'], ['unknown', null],
    ]);
    expect(data.boxes.find(b => b.id === 'late')?.batch_number).toBe('LATE');
  });
  it('retains an exhausted preference for a visible warning instead of silently substituting another box', async () => {
    await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'early' }));
    await env.DB.prepare("UPDATE stock_items SET units_remaining=0,status='empty' WHERE id='early'").run();
    expect((await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'early', enabled: false }))).status).toBe(200);
    const data = await boxes();
    expect(data.preferred_stock_item_id).toBe('early');
    expect(data.boxes.map(b => b.id)).not.toContain('early');
  });
  it('records the actual selected box separately, including after undo', async () => {
    await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'early' }));
    const r = await call('/doses', 'POST', dose('late', { expected_stock_version: 0 }));
    expect(r.status).toBe(201);
    expect(await boxes()).toMatchObject({ preferred_stock_item_id: 'early', last_stock_item_id: 'late' });
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='early'").first('units_remaining')).toBe(10);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='late'").first('units_remaining')).toBe(8);
    const { id } = await r.json() as { id: string };
    expect((await call(`/doses/${id}/undo`, 'POST', {})).status).toBe(200);
    expect((await boxes()).preferred_stock_item_id).toBe('early');
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='late'").first('units_remaining')).toBe(10);
  });
});
describe('expiry confirmation freshness', () => {
  it('allows only one concurrent confirmation of the same observed box version', async () => {
    await env.DB.prepare(`UPDATE medication_plans SET times='["08:00","20:00"]' WHERE id='p'`).run();
    const results = await Promise.all([
      call('/doses', 'POST', dose('late', { expected_stock_version: 0 })),
      call('/doses', 'POST', dose('late', { expected_stock_version: 0, scheduled_time: '20:00' })),
    ]);
    expect(results.map(r => r.status).sort()).toEqual([201, 409]);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='late'").first('units_remaining')).toBe(8);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM dose_logs').first('n')).toBe(1);
  });
  it.each(["expiry_date='2098-02-01'", "batch_number='CHANGED'", 'units_remaining=9'])('rejects a stale confirmation atomically after %s', async change => {
    const version = (await boxes()).boxes.find(b => b.id === 'late')!.metadata_version;
    await env.DB.prepare(`UPDATE stock_items SET ${change} WHERE id='late'`).run();
    const r = await call('/doses', 'POST', dose('late', { expected_stock_version: version }));
    expect(r.status).toBe(409);
    expect(await r.json()).toMatchObject({ error: expect.stringContaining('重新核对') });
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM dose_logs').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM stock_movements').first('n')).toBe(0);
    expect((await call('/doses', 'POST', dose('late', { expected_stock_version: version + 1 }))).status).toBe(201);
  });
  it('still requires a separate expired-box confirmation even for the preferred box', async () => {
    await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'expired' }));
    expect((await call('/doses', 'POST', dose('expired', { expected_stock_version: 0 }))).status).toBe(400);
    expect((await call('/doses', 'POST', dose('expired', { expected_stock_version: 0, confirm_expired: true }))).status).toBe(201);
  });
  it('does not deduct the preference when explicitly recording without inventory', async () => {
    await call('/plans/p', 'PUT', draft({ preferred_stock_item_id: 'early' }));
    expect((await call('/doses', 'POST', dose('early', { stock_item_id: null }))).status).toBe(201);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM stock_movements').first('n')).toBe(0);
    expect((await boxes()).preferred_stock_item_id).toBe('early');
  });
});
