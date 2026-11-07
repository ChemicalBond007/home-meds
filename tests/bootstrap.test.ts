import { beforeEach, describe, expect, it } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash, encrypt } from '../src/worker/crypto';
import type { AdminBootstrap, AppBootstrap } from '../src/shared/bootstrap';

const paths: string[] = [];
async function call(path: string, token = 'member', method = 'GET', body?: object, extra: Record<string, string> = {}) {
  paths.push(path);
  return app.request(`https://home.test/api${path}`, { method,
    headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  }, env);
}
beforeEach(async () => {
  paths.length = 0;
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','另一家庭','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','家人','user','g'),('a','管理员','admin','g'),('x','他人','user','other')"),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','药箱'),('wo','other','他家药箱')"),
    env.DB.prepare("INSERT INTO products(id,group_id,name,created_by) VALUES('p','g','本家药','u'),('po','other','他家药','x')"),
    env.DB.prepare("INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining) VALUES('b','g','p','w',10,10)"),
    env.DB.prepare("INSERT INTO app_settings(key,value) VALUES('key:llm',?)").bind(await encrypt(env.APP_ENC_KEY, 'settings', 'test-key-must-never-be-returned')),
  ]);
  for (const [token, user, limited] of [['member', 'u', 0], ['admin', 'a', 0], ['limited', 'a', 1]] as const) {
    await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,binding_only,expires_at) VALUES(?,?,?,?)')
      .bind(await hash(token), user, limited, Date.now() + 365 * 86400000).run();
  }
});

describe('admin settings boundary', () => {
  it('requires an administrator even with spoofed client and snapshot headers', async () => {
    expect((await call('/admin/bootstrap', 'none')).status).toBe(401);
    expect((await call('/admin/bootstrap', 'member', 'GET', undefined, { 'X-HomeMeds-Client': 'android' })).status).toBe(403);
    expect((await call('/auth/admin/login', 'none', 'POST', { username: '家人' })).status).toBe(403);
    const denied = await call('/stock', 'member', 'POST',
      { product_id: 'p', warehouse_id: 'w' }, { 'X-HomeMeds-State': 'admin' });
    expect(denied.status).toBe(403);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM stock_items').first('n')).toBe(1);
    // Existing APK member login remains usable, but does not grant access to the admin API.
    expect((await call('/auth/login', 'none', 'POST', { username: '家人' })).status).toBe(200);
  });
  it('bootstraps settings, key-presence flags and security status without medical data or secrets', async () => {
    const response = await call('/admin/bootstrap', 'admin');
    const text = await response.text(), data = JSON.parse(text) as AdminBootstrap;
    expect(response.headers.get('Cache-Control')).toBe('no-store');
    expect(data.user.role).toBe('admin');
    expect(data.ai?.has_llm_key).toBe(true);
    expect(data.security).toEqual({ remaining: 0, totp: false, passkeys: 0 });
    for (const forbidden of ['products', 'stock', 'plans', 'today', 'token_hash', 'test-key-must-never-be-returned', 'encrypted_secret'])
      expect(text).not.toContain(`"${forbidden}"`);
    expect(paths).toEqual(['/admin/bootstrap']);
  });
  it('returns only binding context to restricted sessions and blocks their data APIs', async () => {
    const data = await (await call('/admin/bootstrap', 'limited')).json() as AdminBootstrap;
    expect(data.binding_only).toBe(true);
    expect(data.users).toEqual([]);
    expect(data.groups).toEqual([]);
    expect(data.ai).toBeUndefined();
    expect((await call('/app/sync', 'limited')).status).toBe(403);
    expect((await call('/stock', 'limited')).status).toBe(403);
  });
  it('saves a family setting and returns the refreshed administrator state in the same request', async () => {
    const response = await call('/warehouses/w', 'admin', 'PUT', { name: '新药箱' }, { 'X-HomeMeds-State': 'admin' });
    const result = await response.json() as { ok: boolean; admin_state: AdminBootstrap };
    expect(result.ok).toBe(true);
    expect(result.admin_state.warehouses[0].name).toBe('新药箱');
    expect(paths).toHaveLength(1);
  });
});

describe('APK aggregate state', () => {
  it('matches the existing family endpoints in one response without trusting member group headers', async () => {
    const response = await call('/app/bootstrap', 'member', 'GET', undefined, { 'X-Group-Id': 'other' });
    const data = await response.json() as AppBootstrap;
    expect(data.group_id).toBe('g');
    expect(data.groups.map(g => g.id)).toEqual(['g']);
    expect(JSON.stringify(data)).not.toContain('join_code');
    const legacy = await Promise.all(['/members', '/products', '/stock', '/plans', '/warehouses', '/today']
      .map(async path => (await call(path)).json()));
    expect([data.members, data.products, data.stock, data.plans, data.warehouses]).toEqual(legacy.slice(0, 5));
    expect(data.today?.items).toEqual((legacy[5] as AppBootstrap['today'])?.items);
    expect(data.user).not.toHaveProperty('token_hash');
    expect(data.products.map(p => p.id)).toEqual(['p']);
  });
  it('allows administrator family selection but keeps member reminder synchronization family-locked', async () => {
    const admin = await (await call('/app/bootstrap', 'admin', 'GET', undefined, { 'X-Group-Id': 'other' })).json() as AppBootstrap;
    expect(admin.group_id).toBe('other');
    expect(admin.products.map(p => p.id)).toEqual(['po']);
    expect((await call('/app/sync', 'member', 'GET', undefined, { 'X-Group-Id': 'other' })).status).toBe(403);
    const sync = await (await call('/app/sync')).json() as { group_id: string; plans: unknown[]; timezone: string };
    expect(sync).toMatchObject({ group_id: 'g', timezone: 'Asia/Shanghai', plans: [] });
  });
  it('commits intake once and returns new inventory without another list request; legacy response stays unchanged', async () => {
    const body = { product_id: 'p', warehouse_id: 'w', units_per_package: 5 };
    const response = await call('/stock', 'member', 'POST', body, { 'X-HomeMeds-State': 'app' });
    const data = await response.json() as { ids: string[]; app_state: AppBootstrap };
    expect(response.status).toBe(201);
    expect(data.app_state.stock).toHaveLength(2);
    expect(data.app_state.stock.find(s => s.id === data.ids[0])?.units_remaining).toBe(5);
    expect(paths).toHaveLength(1);
    const old = await (await call('/stock', 'member', 'POST', body)).json();
    expect(old).toHaveProperty('ids');
    expect(old).not.toHaveProperty('app_state');
  });
  it('does not append snapshots to rejected writes or weaken origin and optimistic-lock checks', async () => {
    const failed = await call('/stock', 'member', 'POST',
      { product_id: 'po', warehouse_id: 'w' }, { 'X-HomeMeds-State': 'app' });
    expect(failed.status).toBe(400);
    expect(await failed.json()).not.toHaveProperty('app_state');
    expect((await call('/stock', 'member', 'POST', { product_id: 'p', warehouse_id: 'w' },
      { Origin: 'https://evil.test', 'X-HomeMeds-State': 'app' })).status).toBe(403);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM stock_items').first('n')).toBe(1);
  });
  it('bounds large aggregate responses and preserves a successful write when its snapshot is too large', async () => {
    await env.DB.batch([
      env.DB.prepare(`WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<90)
        INSERT INTO products(id,group_id,name,indications,created_by) SELECT 'big-'||i,'g','药'||i,?,'u' FROM n`).bind('字'.repeat(4000)),
      env.DB.prepare(`WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<200)
        INSERT INTO medication_plans(id,group_id,user_id,medicine_name,amount,unit,times,frequency,start_date,notes,created_by,updated_by)
        SELECT 'plan-'||i,'g','u','药',1,'片','[]','as_needed','2020-01-01',?,'u','u' FROM n`).bind('字'.repeat(1800)),
    ]);
    expect((await call('/app/bootstrap')).status).toBe(413);
    const response = await call('/stock', 'member', 'POST', { product_id: 'p', warehouse_id: 'w' }, { 'X-HomeMeds-State': 'app' });
    const body = await response.json() as { ids: string[]; state_error: string };
    expect(response.status).toBe(201);
    expect(body.ids).toHaveLength(1);
    expect(body).not.toHaveProperty('app_state');
    expect(body.state_error).toContain('已保存');
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM stock_items').first('n')).toBe(2);
  });
});
