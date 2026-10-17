import { Hono } from 'hono';
import { z } from 'zod';
import { type AppEnv, admin, group, json, fail } from './core';
import { randomToken } from './crypto';
import { timeSchema } from '../shared/schemas';
const groupSchema = z.object({
  name: z.string().trim().min(1).max(100),
  timezone: z.string().max(80).default('Asia/Shanghai').refine(s => {
    try { new Intl.DateTimeFormat('en', { timeZone: s }).format(); return true; } catch { return false; }
  }, '无效时区'),
  meal_times: z.object({ morning: timeSchema, noon: timeSchema, evening: timeSchema, bedtime: timeSchema })
    .default({ morning: '08:00', noon: '12:00', evening: '18:00', bedtime: '21:30' }),
});
export const management = new Hono<AppEnv>();
management.get('/groups', async c => {
  const u = c.get('user');
  const result = u.role === 'admin'
    ? await c.env.DB.prepare('SELECT * FROM groups ORDER BY name').all()
    : await c.env.DB.prepare('SELECT id,name,timezone,meal_times FROM groups WHERE id=?').bind(u.group_id).all();
  return c.json(result.results);
});
management.post('/groups', async c => {
  admin(c);
  const d = await json(c, groupSchema);
  const id = crypto.randomUUID(), code = randomToken(9);
  await c.env.DB.batch([
    c.env.DB.prepare('INSERT INTO groups(id,name,join_code,timezone,meal_times) VALUES(?,?,?,?,?)')
      .bind(id, d.name, code, d.timezone, JSON.stringify(d.meal_times)),
    c.env.DB.prepare('INSERT INTO warehouses(id,group_id,name) VALUES(?,?,?)').bind(crypto.randomUUID(), id, '家中药箱'),
  ]);
  c.set('auditTarget', id);
  return c.json({ id, join_code: code }, 201);
});
management.put('/groups/:id', async c => {
  admin(c);
  const d = await json(c, groupSchema);
  const r = await c.env.DB.prepare('UPDATE groups SET name=?,timezone=?,meal_times=? WHERE id=?')
    .bind(d.name, d.timezone, JSON.stringify(d.meal_times), c.req.param('id')).run();
  if (!r.meta.changes) fail(404, '群组不存在');
  return c.json({ ok: true });
});
management.post('/groups/:id/rotate-code', async c => {
  admin(c);
  const code = randomToken(9);
  const r = await c.env.DB.prepare('UPDATE groups SET join_code=? WHERE id=?').bind(code, c.req.param('id')).run();
  if (!r.meta.changes) fail(404, '群组不存在');
  return c.json({ join_code: code });
});
management.get('/members', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare('SELECT id,username,role,group_id,is_elderly FROM users WHERE group_id=? ORDER BY username').bind(g.id).all()).results);
});
management.get('/admin/users', async c => {
  admin(c);
  return c.json((await c.env.DB.prepare('SELECT id,username,role,group_id,is_elderly FROM users ORDER BY username').all()).results);
});
management.patch('/members/:id', async c => {
  admin(c);
  const d = await json(c, z.object({ is_elderly: z.boolean(), group_id: z.string().nullable() }));
  const u = await c.env.DB.prepare('SELECT id FROM users WHERE id=?').bind(c.req.param('id')).first();
  if (!u) fail(404, '成员不存在');
  if (d.group_id && !await c.env.DB.prepare('SELECT id FROM groups WHERE id=?').bind(d.group_id).first()) fail(400, '群组不存在');
  const current = await c.env.DB.prepare('SELECT group_id FROM users WHERE id=?').bind(c.req.param('id')).first<{ group_id: string | null }>();
  if (current?.group_id !== d.group_id) {
    const plans = await c.env.DB.prepare('SELECT id FROM medication_plans WHERE user_id=? LIMIT 1').bind(c.req.param('id')).first();
    if (plans) fail(409, '成员已有服药计划，暂不支持跨群组移动');
  }
  await c.env.DB.prepare('UPDATE users SET is_elderly=?,group_id=? WHERE id=?').bind(Number(d.is_elderly), d.group_id, c.req.param('id')).run();
  return c.json({ ok: true });
});
management.get('/warehouses', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare('SELECT * FROM warehouses WHERE group_id=? ORDER BY name').bind(g.id).all()).results);
});
management.post('/warehouses', async c => {
  admin(c);
  const g = await group(c);
  const d = await json(c, z.object({ name: z.string().trim().min(1).max(100) }));
  const id = crypto.randomUUID();
  await c.env.DB.prepare('INSERT INTO warehouses(id,group_id,name) VALUES(?,?,?)').bind(id, g.id, d.name).run();
  c.set('auditTarget', id);
  return c.json({ id }, 201);
});
management.put('/warehouses/:id', async c => {
  admin(c);
  const g = await group(c);
  const d = await json(c, z.object({ name: z.string().trim().min(1).max(100) }));
  const r = await c.env.DB.prepare('UPDATE warehouses SET name=? WHERE id=? AND group_id=?').bind(d.name, c.req.param('id'), g.id).run();
  if (!r.meta.changes) fail(404, '药箱不存在');
  return c.json({ ok: true });
});
management.delete('/warehouses/:id', async c => {
  admin(c);
  const g = await group(c);
  const r = await c.env.DB.prepare(`DELETE FROM warehouses WHERE id=? AND group_id=?
    AND NOT EXISTS(SELECT 1 FROM stock_items WHERE warehouse_id=warehouses.id)
    AND NOT EXISTS(SELECT 1 FROM stock_movements WHERE from_warehouse_id=warehouses.id OR to_warehouse_id=warehouses.id)`)
    .bind(c.req.param('id'), g.id).run();
  if (!r.meta.changes) fail(409, '药箱不存在或已有库存记录，请改名而非删除');
  return c.json({ ok: true });
});
