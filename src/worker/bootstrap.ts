import { Hono, type MiddlewareHandler } from 'hono';
import { type AppEnv, type Ctx, admin, fail, group } from './core';
import type { User, FamilyGroup, Product, StockItem, Plan, DoseLog } from '../shared/types';
import type { AdminBootstrap, AppBootstrap, Warehouse } from '../shared/bootstrap';
import { localClock, todayTodos } from '../shared/schedule';
import { settingsState } from './ai-config';
import { adminPasskeys } from './passkeys';

export const bootstrap = new Hono<AppEnv>();
const userColumns = 'id,username,role,group_id,is_elderly';
const planQuery = 'SELECT p.*,u.username FROM medication_plans p JOIN users u ON u.id=p.user_id';

async function context(c: Ctx, fresh: boolean) {
  const user = fresh ? await c.env.DB.prepare(`SELECT ${userColumns} FROM users WHERE id=?`).bind(c.get('user').id).first<User>() : c.get('user');
  if (!user) fail(401, '登录已失效');
  const groups = (await (user.role === 'admin' ? c.env.DB.prepare('SELECT * FROM groups ORDER BY name')
    : c.env.DB.prepare('SELECT id,name,timezone,meal_times FROM groups WHERE id=?').bind(user.group_id)).all<FamilyGroup>()).results;
  const wanted = user.role === 'admin' ? c.req.header('X-Group-Id') : user.group_id;
  const family = groups.find(g => g.id === wanted) || groups.find(g => g.id === user.group_id) || groups[0];
  return { user, groups, family };
}

export async function adminBootstrap(c: Ctx, fresh = false): Promise<AdminBootstrap> {
  admin(c);
  const limited = !!c.get('session').binding_only;
  if (limited) return { api_version: 1, user: c.get('user'), binding_only: true, groups: [], group_id: null, users: [], members: [], warehouses: [] };
  const { user, groups, family } = await context(c, fresh);
  if (user.role !== 'admin') fail(403, '仅管理员可访问管理台');
  const rows = await c.env.DB.batch([
    c.env.DB.prepare(`SELECT ${userColumns} FROM users ORDER BY username`),
    c.env.DB.prepare('SELECT * FROM warehouses WHERE group_id=? ORDER BY name').bind(family?.id ?? null),
    c.env.DB.prepare(`SELECT
      (SELECT COUNT(*) FROM one_time_secrets WHERE user_id=? AND kind='recovery' AND used_at IS NULL AND expires_at>?) AS remaining,
      EXISTS(SELECT 1 FROM totp_secrets WHERE user_id=? AND verified=1) AS totp,
      (SELECT COUNT(*) FROM webauthn_credentials WHERE user_id=?) AS passkeys`).bind(user.id, Date.now(), user.id, user.id),
  ]);
  const users = rows[0].results as User[];
  const security = rows[2].results[0] as { remaining: number; totp: number; passkeys: number };
  return { api_version: 1, user, binding_only: false, groups, group_id: family?.id ?? null,
    users, members: users.filter(u => u.group_id === family?.id), warehouses: rows[1].results as Warehouse[],
    security: { remaining: security.remaining, totp: !!security.totp, passkeys: security.passkeys },
    passkey_admins: await adminPasskeys(c), ai: await settingsState(c.env) };
}

export async function appBootstrap(c: Ctx, fresh = false): Promise<AppBootstrap> {
  const empty: AppBootstrap = { api_version: 1, user: c.get('user'), binding_only: !!c.get('session').binding_only,
    groups: [], group_id: null, members: [], products: [], stock: [], plans: [], warehouses: [], today: null };
  if (empty.binding_only) return empty;
  const { user, groups, family } = await context(c, fresh);
  if (!family) return { ...empty, user, groups };
  const clock = localClock(new Date(), family.timezone);
  const rows = await c.env.DB.batch<User | Product | StockItem | Plan | DoseLog | Warehouse>([
    c.env.DB.prepare(`SELECT ${userColumns} FROM users WHERE group_id=? ORDER BY username`).bind(family.id),
    c.env.DB.prepare('SELECT * FROM products WHERE group_id=? ORDER BY name').bind(family.id),
    c.env.DB.prepare(`SELECT s.*,p.name,p.base_unit,w.name AS warehouse_name FROM stock_items s
      JOIN products p ON p.id=s.product_id JOIN warehouses w ON w.id=s.warehouse_id
      WHERE s.group_id=? ORDER BY s.status,s.expiry_date IS NULL,s.expiry_date`).bind(family.id),
    c.env.DB.prepare(`${planQuery} WHERE p.group_id=? ORDER BY p.enabled DESC,u.username,p.medicine_name`).bind(family.id),
    c.env.DB.prepare('SELECT * FROM warehouses WHERE group_id=? ORDER BY name').bind(family.id),
    c.env.DB.prepare(`SELECT d.*,u.username AS actor_name FROM dose_logs d JOIN users u ON u.id=d.actor_id
      WHERE d.group_id=? AND d.local_date=? AND d.status<>'undone'`).bind(family.id, clock.date),
  ]);
  const plans = rows[3].results as Plan[];
  return { ...empty, user, groups, group_id: family.id, members: rows[0].results as User[],
    products: rows[1].results as Product[], stock: rows[2].results as StockItem[], plans,
    warehouses: rows[4].results as Warehouse[],
    today: { ...clock, timezone: family.timezone, items: todayTodos(plans, rows[5].results as DoseLog[], family.timezone) } };
}

bootstrap.get('/admin/bootstrap', async c => c.json(await adminBootstrap(c)));
const maxAppBytes = 2 * 1024 * 1024;
bootstrap.get('/app/bootstrap', async c => {
  const body = JSON.stringify(await appBootstrap(c));
  if (new TextEncoder().encode(body).length > maxAppBytes)
    return c.json({ error: '家庭数据较大，请使用兼容分项读取' }, 413);
  return c.body(body, 200, { 'Content-Type': 'application/json; charset=UTF-8' });
});
bootstrap.get('/app/sync', async c => {
  const g = await group(c);
  if (c.req.header('X-Group-Id') && c.req.header('X-Group-Id') !== g.id) fail(403, '当前家庭已变更，请重新打开应用');
  const plans = await c.env.DB.prepare(`${planQuery} WHERE p.group_id=? ORDER BY p.enabled DESC,u.username,p.medicine_name`).bind(g.id).all<Plan>();
  return c.json({ api_version: 1, user: c.get('user'), group_id: g.id, timezone: g.timezone, plans: plans.results });
});

// Opt-in, additive response data. Existing clients still receive the original response.
export const mutationSnapshot: MiddlewareHandler<AppEnv> = async (c, next) => {
  const mode = c.req.header('X-HomeMeds-State');
  const mutation = !['GET', 'HEAD', 'OPTIONS'].includes(c.req.method)
    && /^\/api\/(?:products|stock|plans|doses|groups|members|warehouses)(?:\/|$)/.test(c.req.path);
  if (!mutation || !['app', 'admin'].includes(mode || '')) return next();
  if (mode === 'admin') admin(c);
  await next();
  if (c.res.status < 200 || c.res.status >= 300) return;
  const body = await c.res.json<Record<string, unknown>>();
  const respond = (value: object) => {
    const headers = new Headers(c.res.headers);
    headers.delete('Content-Length');
    c.res = new Response(JSON.stringify(value), { status: c.res.status, headers });
  };
  try {
    const snapshot = mode === 'admin' ? await adminBootstrap(c, true) : await appBootstrap(c, true);
    const value = { ...body, [mode === 'admin' ? 'admin_state' : 'app_state']: snapshot };
    if (mode === 'app' && new TextEncoder().encode(JSON.stringify(value)).length > maxAppBytes)
      respond({ ...body, state_error: '已保存，家庭数据较大，请分项刷新，不要重复提交' });
    else respond(value);
  } catch {
    // The write already committed. Never turn a refresh failure into a failed write.
    respond({ ...body, state_error: '已保存，但状态刷新失败，请刷新后查看，不要重复提交' });
  }
};
