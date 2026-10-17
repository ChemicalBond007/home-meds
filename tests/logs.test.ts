import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';
import type { LogPage, LogSource } from '../src/shared/logs';
import { aiSettingsSchema } from '../src/worker/ai-config';

async function call(path: string, method = 'GET', body?: object, token = 'admin', headers: Record<string, string> = {}) {
  return app.request(`https://home.test/api${path}`, {
    method, headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', ...headers },
    body: body ? JSON.stringify(body) : undefined,
  }, env);
}
async function page(source: LogSource = 'audit', params: Record<string, string> = {}) {
  const r = await call(`/admin/logs?${new URLSearchParams({ source, ...params })}`);
  expect(r.status).toBe(200);
  expect(r.headers.get('Cache-Control')).toBe('no-store');
  return await r.json() as LogPage;
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭甲','join'),('other','家庭乙','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','用户甲','user','g'),('a','管理甲','admin','g'),('x','用户乙','user','other')"),
  ]);
  for (const [token, user] of [['admin', 'a'], ['member', 'u']]) {
    await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash(token), user, Date.now() + 86400000).run();
  }
});
afterEach(() => vi.restoreAllMocks());

describe('operation audit', () => {
  it('records the authenticated login actor, not an existing cookie or a claimed account', async () => {
    expect((await call('/auth/login', 'POST', { username: '用户乙' })).status).toBe(200);
    expect((await call('/auth/login', 'POST', { username: '管理甲', secret: 'private-login-proof' }, 'none')).status).toBe(401);
    const rows = await env.DB.prepare("SELECT * FROM audit_logs WHERE action='auth.login' ORDER BY id").all();
    expect(rows.results).toMatchObject([
      { actor_id: 'x', actor_name: '用户乙', group_id: 'other', subject_name: '用户乙', outcome: 'success', http_status: 200 },
      { actor_id: null, actor_name: null, subject_name: '管理甲', outcome: 'failure', http_status: 401 },
    ]);
    expect(JSON.stringify(rows.results)).not.toContain('private-login-proof');
  });
  it('records registrations, logout, and the target of account reset without returned secrets', async () => {
    const registered = await call('/auth/register', 'POST', { username: '新家人', join_code: 'join' }, 'none');
    expect(registered.status).toBe(200);
    expect(await env.DB.prepare("SELECT actor_name,group_id FROM audit_logs WHERE action='auth.register'").first()).toEqual({ actor_name: '新家人', group_id: 'g' });
    expect((await call('/auth/logout', 'POST', {}, 'member')).status).toBe(200);
    expect(await env.DB.prepare("SELECT actor_id,outcome FROM audit_logs WHERE action='auth.logout'").first()).toEqual({ actor_id: 'u', outcome: 'success' });
    await env.DB.prepare("INSERT INTO users(id,username,role) VALUES('b','管理乙','admin')").run();
    const reset = await call('/admin/users/b/reset', 'POST', {});
    const result = await reset.json() as { temporary_password: string };
    expect(reset.status).toBe(200);
    const audit = await env.DB.prepare("SELECT * FROM audit_logs WHERE action='admin.reset'").first();
    expect(audit).toMatchObject({ actor_id: 'a', target_id: 'b', outcome: 'success' });
    expect(JSON.stringify(audit)).not.toContain(result.temporary_password);
  });
  it('uses validated groups, snapshots names, and does not label global changes with the selected family', async () => {
    expect((await call('/plans', 'POST', {
      user_id: 'u', medicine_name: '虚构药', amount: 1, unit: '片', times: ['08:00'], frequency: 'daily', start_date: '2026-01-01',
    }, 'member', { 'X-Group-Id': 'other' })).status).toBe(201);
    expect(await env.DB.prepare("SELECT group_id FROM audit_logs WHERE action='plan.create'").first('group_id')).toBe('g');
    const created = await call('/warehouses', 'POST', { name: '新药箱' }, 'admin', { 'X-Group-Id': 'other' });
    expect(created.status).toBe(201);
    const { id } = await created.json() as { id: string };
    await env.DB.prepare("UPDATE groups SET name='改名后' WHERE id='other'").run();
    expect(await env.DB.prepare("SELECT group_id,group_name,target_id FROM audit_logs WHERE action='warehouse.create'").first())
      .toEqual({ group_id: 'other', group_name: '家庭乙', target_id: id });
    const body = { settings: aiSettingsSchema.parse({}), llm_key: 'private-llm-key', tavily_key: 'private-search-key' };
    expect((await call('/ai/settings?secret=private-url-param', 'PUT', body, 'admin', { 'X-Group-Id': 'other' })).status).toBe(200);
    const row = await env.DB.prepare("SELECT * FROM audit_logs WHERE action='ai.settings.update'").first();
    expect(row).toMatchObject({ group_id: null, route: '/api/ai/settings', outcome: 'success' });
    for (const secret of ['private-llm-key', 'private-search-key', 'private-url-param', 'admin']) {
      // The actual cookie token must not appear as a stored value.
      expect(Object.values(row!).includes(secret)).toBe(false);
    }
    expect((await call('/groups', 'POST', { name: '新家庭' })).status).toBe(201);
    expect(await env.DB.prepare("SELECT group_name FROM audit_logs WHERE action='group.create'").first('group_name')).toBe('新家庭');
  });
  it('audits denied actions and unknown routes without untrusted headers, bodies or raw paths', async () => {
    expect((await call('/members/x', 'PATCH', { is_elderly: true, group_id: 'other', secret: 'private-body' }, 'member', { 'X-Group-Id': 'other' })).status).toBe(403);
    expect((await call('/private-path?token=private-query', 'POST', { secret: 'private-body' })).status).toBe(404);
    const rows = (await env.DB.prepare('SELECT * FROM audit_logs ORDER BY id').all()).results;
    expect(rows[0]).toMatchObject({ actor_id: 'u', group_id: null, outcome: 'failure', http_status: 403 });
    expect(rows[1]).toMatchObject({ action: 'request.other', route: '/api/*', http_status: 404 });
    for (const value of ['private-path', 'private-query', 'private-body']) expect(JSON.stringify(rows)).not.toContain(value);
    const before = rows.length;
    await call('/groups');
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM audit_logs').first('n')).toBe(before);
  });
  it('audits factor setup and early request rejection without authentication material', async () => {
    const setup = await call('/auth/totp/setup', 'POST', {});
    expect(setup.status).toBe(200);
    const { secret, uri } = await setup.json() as { secret: string; uri: string };
    expect((await call('/auth/recovery/regenerate', 'POST', { code: '000000' })).status).toBe(400);
    expect((await call('/groups', 'POST', { name: '拒绝请求' }, 'admin', { Origin: 'https://other.test' })).status).toBe(403);
    const large = await app.request('https://home.test/api/ai/settings', {
      method: 'PUT', headers: { Origin: 'https://home.test', 'Content-Type': 'application/json' },
      body: 'private-oversized-body'.repeat(14000),
    }, env);
    expect(large.status).toBe(413);
    const rows = (await env.DB.prepare('SELECT * FROM audit_logs ORDER BY id').all()).results;
    expect(rows).toMatchObject([
      { action: 'auth.totp.setup', outcome: 'success' },
      { action: 'auth.recovery.regenerate', outcome: 'failure', http_status: 400 },
      { action: 'group.create', outcome: 'failure', http_status: 403 },
      { action: 'ai.settings.update', outcome: 'failure', http_status: 413 },
    ]);
    for (const value of [secret, uri, '000000', 'private-oversized-body']) expect(JSON.stringify(rows)).not.toContain(value);
  });
  it('does not turn committed writes into failed responses when logging is unavailable', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    await env.DB.prepare('DROP TABLE audit_logs').run();
    expect((await call('/warehouses', 'POST', { name: '已提交药箱' }, 'admin', { 'X-Group-Id': 'g' })).status).toBe(201);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM warehouses WHERE name='已提交药箱'").first('n')).toBe(1);
    expect(error).toHaveBeenCalledWith(JSON.stringify({ event: 'audit_write_failed', action: 'warehouse.create' }));
  });
});

describe('administrator log queries', () => {
  it('rejects anonymous, regular and restricted admin sessions for every source', async () => {
    for (const source of ['audit', 'lookup', 'usage', 'dose', 'stock']) {
      expect((await call(`/admin/logs?source=${source}`, 'GET', undefined, 'none')).status).toBe(401);
      expect((await call(`/admin/logs?source=${source}`, 'GET', undefined, 'member', { 'X-Group-Id': 'other' })).status).toBe(403);
    }
    await env.DB.prepare("UPDATE sessions SET binding_only=1 WHERE user_id='a'").run();
    expect((await call('/admin/logs')).status).toBe(403);
  });
  async function history() {
    for (const [id, user, group] of [['1', 'u', 'g'], ['2', 'x', 'other']]) {
      await env.DB.batch([
        env.DB.prepare('INSERT INTO ai_lookup_logs(id,user_id,group_id,query,success,created_at) VALUES(?,?,?,?,?,?)')
          .bind(`lookup${id}`, user, group, `查询${id}`, id === '1' ? 1 : 0, '2026-01-01 23:59:59'),
        env.DB.prepare('INSERT INTO ai_usage(scope,local_date,kind,count) VALUES(?,?,?,?)').bind(`group:${group}`, '2026-01-01', 'lookup', 4),
        env.DB.prepare('INSERT INTO ai_usage(scope,local_date,kind,count) VALUES(?,?,?,?)').bind(`user:${user}`, '2026-01-01', 'chat', 2),
        env.DB.prepare('INSERT INTO products(id,group_id,name,created_by) VALUES(?,?,?,?)').bind(`med${id}`, group, `药${id}`, user),
        env.DB.prepare('INSERT INTO warehouses(id,group_id,name) VALUES(?,?,?)').bind(`w${id}`, group, `药箱${id}`),
        env.DB.prepare('INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining) VALUES(?,?,?,?,10,10)')
          .bind(`box${id}`, group, `med${id}`, `w${id}`),
        env.DB.prepare('INSERT INTO stock_movements(id,stock_item_id,type,amount,to_warehouse_id,actor_id) VALUES(?,?,?,10,?,?)')
          .bind(`stock${id}`, `box${id}`, 'in', `w${id}`, user),
        env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
          VALUES(?,?,?,'虚构药',1,'片','["08:00"]','daily','2020-01-01',?,?)`).bind(`p${id}`, group, user, user, user),
        env.DB.prepare(`INSERT INTO dose_logs(id,plan_id,user_id,group_id,local_date,scheduled_time,status,amount,unit,actor_id)
          VALUES(?,?,?,?,'2026-01-01','08:00','skipped',0,'片',?)`).bind(`dose${id}`, `p${id}`, user, group, user),
      ]);
    }
  }
  it('exposes cross-family legacy records only to admins and applies actor/family/outcome filters', async () => {
    await history();
    const global = await call('/admin/logs?source=lookup', 'GET', undefined, 'admin', { 'X-Group-Id': 'g' });
    expect((await global.json() as LogPage).items).toHaveLength(2);
    for (const source of ['lookup', 'dose', 'stock'] as const) {
      expect((await page(source)).items.map(r => r.actor_id).sort()).toEqual(['u', 'x']);
      const items = (await page(source, { user_id: 'x', group_id: 'other' })).items;
      expect(items).toHaveLength(1);
      expect(items[0]).toMatchObject({ actor_name: '用户乙', group_name: '家庭乙' });
    }
    expect((await page('lookup', { outcome: 'failure' })).items).toMatchObject([{ detail: '查询2', outcome: 'failure' }]);
    expect((await page('usage')).items).toHaveLength(4);
    expect((await page('usage', { user_id: 'x' })).items).toMatchObject([{ actor_id: 'x', action: 'usage.chat', detail: '2', outcome: null }]);
    expect((await page('usage', { group_id: 'other' })).items).toHaveLength(2);
    expect((await page('lookup', { from: '2026-01-01', to: '2026-01-01' })).items).toHaveLength(2);
    expect((await page('lookup', { from: '2026-01-02' })).items).toHaveLength(0);
    expect((await page('audit', { action: 'logs.view' })).items.length).toBeGreaterThan(0);
  });
  it('paginates tied timestamps without duplicates, even when newer entries arrive', async () => {
    for (let i = 0; i < 6; i++) await env.DB.prepare(`INSERT INTO audit_logs(created_at,actor_id,action,method,route,outcome,http_status)
      VALUES('2026-01-01 12:00:00','u','auth.login','POST','/api/auth/login','success',200)`).run();
    const first = await page('audit', { action: 'auth.login', limit: '2' });
    expect(first.next_cursor).not.toBeNull();
    await env.DB.prepare(`INSERT INTO audit_logs(actor_id,action,method,route,outcome,http_status)
      VALUES('u','auth.login','POST','/api/auth/login','success',200)`).run();
    const seen = [...first.items.map(r => r.id)];
    let cursor = first.next_cursor;
    while (cursor) {
      const next = await page('audit', { action: 'auth.login', limit: '2', cursor });
      seen.push(...next.items.map(r => r.id)); cursor = next.next_cursor;
    }
    expect(seen).toHaveLength(6);
    expect(new Set(seen).size).toBe(6);
  });
  it('validates query bounds and cursors and binds filter values instead of SQL interpolation', async () => {
    for (const query of ['source=invalid', 'limit=101', 'limit=0', 'limit=1.5', 'from=2026-02-30', 'from=2026-02-01&to=2026-01-01', 'cursor=invalid']) {
      expect((await call(`/admin/logs?${query}`)).status).toBe(400);
    }
    const cursor = btoa(JSON.stringify({ source: 'stock', at: '2026-01-01 00:00:00', id: 'x' }));
    expect((await call(`/admin/logs?cursor=${encodeURIComponent(cursor)}`)).status).toBe(400);
    expect((await page('audit', { user_id: "' OR 1=1 --" })).items).toHaveLength(0);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM users').first('n')).toBe(3);
  });
});
