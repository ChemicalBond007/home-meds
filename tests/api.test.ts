import { beforeEach, describe, it, expect } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash, totpCode } from '../src/worker/crypto';
import { localClock } from '../src/shared/schedule';
import { issueSecrets, consumeSecret } from '../src/worker/one-time';

const date = () => localClock(new Date(), 'Asia/Shanghai').date;
async function call(path: string, method = 'GET', body?: object, token = 'test-session', extra: Record<string, string> = {}) {
  return app.request(`https://home.test/api${path}`, {
    method, headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', ...extra },
    body: body ? JSON.stringify(body) : undefined,
  }, env);
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','另一家','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','家人','user','g'),('a','管理员','admin','g'),('x','他人','user','other')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('test-session'), 'u', Date.now() + 86400000),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('test-admin'), 'a', Date.now() + 86400000),
    env.DB.prepare("INSERT INTO products(id,group_id,name,base_unit,units_per_package,created_by) VALUES('med','g','药品','片',10,'u')"),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','抽屉')"),
    env.DB.prepare("INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining) VALUES('box','g','med','w',10,10)"),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,product_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('p','g','u','med','药品',1,'片','["08:00","20:00"]','daily','2020-01-01','u','u')`),
  ]);
});
const dose = (overrides: object = {}) => ({
  plan_id: 'p', local_date: date(), scheduled_time: '08:00', status: 'taken', amount: 2,
  taken_at: new Date().toISOString(), stock_item_id: 'box', ...overrides,
});
describe('permissions and credentials', () => {
  it('requires login and same-origin mutations', async () => {
    expect((await call('/stock', 'GET', undefined, 'invalid')).status).toBe(401);
    expect((await call('/doses', 'POST', dose(), 'test-session', { Origin: 'https://evil.test' })).status).toBe(403);
    expect((await call('/members/u', 'PATCH', { is_elderly: true, group_id: 'g' })).status).toBe(403);
  });
  it('does not trust a regular user group header', async () => {
    const r = await call('/members', 'GET', undefined, 'test-session', { 'X-Group-Id': 'other' });
    const body = await r.json() as { id: string }[];
    expect(body.map(x => x.id)).not.toContain('x');
  });
  it('limits bootstrap sessions to factor binding', async () => {
    await env.DB.prepare('UPDATE sessions SET binding_only=1 WHERE user_id=?').bind('a').run();
    expect((await call('/stock', 'GET', undefined, 'test-admin')).status).toBe(403);
    expect((await call('/auth/me', 'GET', undefined, 'test-admin')).status).toBe(200);
  });
  it('keeps temporary passwords until binding but consumes recovery codes once', async () => {
    const t = await issueSecrets(env.DB, 'a', 'temporary');
    await env.DB.batch(t.statements);
    expect((await consumeSecret(env.DB, 'a', t.values[0]))?.kind).toBe('temporary');
    expect((await consumeSecret(env.DB, 'a', t.values[0]))?.kind).toBe('temporary');
    const r = await issueSecrets(env.DB, 'a', 'recovery');
    await env.DB.batch(r.statements);
    expect(await consumeSecret(env.DB, 'a', r.values[0])).not.toBeNull();
    expect(await consumeSecret(env.DB, 'a', r.values[0])).toBeNull();
  });
  it('enrolls TOTP from a restricted session, rotates secrets, and prevents replay', async () => {
    await env.DB.prepare('UPDATE sessions SET binding_only=1 WHERE user_id=?').bind('a').run();
    const temporary = await issueSecrets(env.DB, 'a', 'temporary');
    await env.DB.batch(temporary.statements);
    const setup = await call('/auth/totp/setup', 'POST', {}, 'test-admin');
    expect(setup.status).toBe(200);
    const { secret } = await setup.json() as { secret: string };
    const code = await totpCode(secret, Math.floor(Date.now() / 30000));
    const bind = await call('/auth/totp/bind', 'POST', { code }, 'test-admin');
    expect(bind.status).toBe(200);
    const result = await bind.json() as { recovery_codes: string[] };
    expect(result.recovery_codes).toHaveLength(10);
    expect(await consumeSecret(env.DB, 'a', temporary.values[0])).toBeNull();
    expect((await call('/auth/me', 'GET', undefined, 'test-admin')).status).toBe(401);
    expect((await call('/auth/login', 'POST', { username: '管理员', code }, 'unused')).status).toBe(401);
    const recovered = await call('/auth/login', 'POST', { username: '管理员', secret: result.recovery_codes[0] }, 'unused');
    expect(recovered.status).toBe(200);
    expect((await call('/auth/login', 'POST', { username: '管理员', secret: result.recovery_codes[0] }, 'unused')).status).toBe(401);
  });
  it('validates group member and base units on plan creation', async () => {
    const draft = { user_id: 'u', medicine_name: '药', product_id: 'med', amount: 1, unit: '毫升', times: ['08:00'], frequency: 'daily', start_date: date() };
    expect((await call('/plans', 'POST', draft)).status).toBe(400);
    expect((await call('/plans', 'POST', { ...draft, unit: '片', user_id: 'x' })).status).toBe(400);
  });
});
describe('atomic inventory and doses', () => {
  it('deducts once, records actor, and restores only once', async () => {
    const r = await call('/doses', 'POST', dose());
    expect(r.status).toBe(201);
    const { id } = await r.json() as { id: string };
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(8);
    expect((await call('/doses', 'POST', dose())).status).toBe(409);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM stock_movements WHERE type='dose'").first('n')).toBe(1);
    expect((await call(`/doses/${id}/undo`, 'POST', {})).status).toBe(200);
    expect((await call(`/doses/${id}/undo`, 'POST', {})).status).toBe(409);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(10);
    expect((await call('/doses', 'POST', dose())).status).toBe(201);
  });
  it('rolls back insufficient-stock attempts without history', async () => {
    expect((await call('/doses', 'POST', dose({ amount: 11 }))).status).toBe(409);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM dose_logs').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM stock_movements').first('n')).toBe(0);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(10);
  });
  it('handles concurrent scheduled doses without negative stock', async () => {
    const results = await Promise.all([
      call('/doses', 'POST', dose({ amount: 7 })),
      call('/doses', 'POST', dose({ amount: 7, scheduled_time: '20:00' })),
    ]);
    expect(results.map(r => r.status).sort()).toEqual([201, 409]);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(3);
  });
  it('skips and takes without inventory, and rejects historical dates', async () => {
    expect((await call('/doses', 'POST', dose({ status: 'skipped' }))).status).toBe(201);
    expect((await call('/doses', 'POST', dose({ scheduled_time: '20:00', stock_item_id: null }))).status).toBe(201);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(10);
    expect((await call('/doses', 'POST', dose({ local_date: '2000-01-01' }))).status).toBe(400);
  });
  it('requires a second confirmation for expired boxes', async () => {
    await env.DB.prepare("UPDATE stock_items SET expiry_date='2000-01-01'").run();
    expect((await call('/doses', 'POST', dose())).status).toBe(400);
    expect((await call('/doses', 'POST', dose({ confirm_expired: true }))).status).toBe(201);
  });
  it('rejects duplicate trace-code stock-in', async () => {
    const body = { product_id: 'med', warehouse_id: 'w', code: '81234567890123456789' };
    expect((await call('/stock', 'POST', body)).status).toBe(201);
    expect((await call('/stock', 'POST', body)).status).toBe(409);
  });
  it('records stock-out atomically and rejects insufficient stock', async () => {
    expect((await call('/stock/box/move', 'POST', { type: 'out', amount: 11 })).status).toBe(409);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM stock_movements').first('n')).toBe(0);
    expect((await call('/stock/box/move', 'POST', { type: 'out', amount: 10 })).status).toBe(200);
    expect(await env.DB.prepare("SELECT status FROM stock_items WHERE id='box'").first('status')).toBe('empty');
  });
});
