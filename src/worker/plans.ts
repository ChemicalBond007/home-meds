import { Hono } from 'hono';
import { z } from 'zod';
import { type AppEnv, group, json, fail } from './core';
import { planSchema, amountSchema, dateSchema } from '../shared/schemas';
import { localClock, scheduledOn, todayTodos } from '../shared/schedule';
import type { Plan, DoseLog, Product, User, StockItem } from '../shared/types';
export const plans = new Hono<AppEnv>();
const planQuery = 'SELECT p.*,u.username FROM medication_plans p JOIN users u ON u.id=p.user_id';
plans.get('/plans', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare(`${planQuery} WHERE p.group_id=? ORDER BY p.enabled DESC,u.username,p.medicine_name`).bind(g.id).all()).results);
});
plans.get('/today', async c => {
  const g = await group(c), clock = localClock(new Date(), g.timezone);
  const [ps, logs] = await Promise.all([
    c.env.DB.prepare(`${planQuery} WHERE p.group_id=?`).bind(g.id).all<Plan>(),
    c.env.DB.prepare(`SELECT d.*,u.username AS actor_name FROM dose_logs d JOIN users u ON u.id=d.actor_id
      WHERE d.group_id=? AND d.local_date=? AND d.status<>'undone'`).bind(g.id, clock.date).all<DoseLog>(),
  ]);
  return c.json({ date: clock.date, time: clock.time, timezone: g.timezone, items: todayTodos(ps.results, logs.results, g.timezone) });
});
for (const method of ['post', 'put'] as const) {
  plans[method](method === 'post' ? '/plans' : '/plans/:id', async c => {
    const g = await group(c), d = await json(c, planSchema);
    const target = await c.env.DB.prepare('SELECT * FROM users WHERE id=? AND group_id=?').bind(d.user_id, g.id).first<User>();
    if (!target) fail(400, '只能为本群成员设置计划');
    let product: Product | null = null;
    if (d.product_id) {
      product = await c.env.DB.prepare('SELECT * FROM products WHERE id=? AND group_id=?').bind(d.product_id, g.id).first<Product>();
      if (!product) fail(400, '药品不属于本群');
      if (product.base_unit !== d.unit) fail(400, `用量单位必须是${product.base_unit}`);
    }
    const id = method === 'post' ? crypto.randomUUID() : c.req.param('id')!;
    if (method === 'put' && !await c.env.DB.prepare('SELECT id FROM medication_plans WHERE id=? AND group_id=?').bind(id, g.id).first()) fail(404, '计划不存在');
    const values = [d.user_id, d.product_id, product?.name || d.medicine_name, d.amount, d.unit, JSON.stringify(d.times), d.frequency,
      JSON.stringify([...new Set(d.weekdays)]), d.interval_days, d.start_date, d.end_date, d.meal_relation, d.notes,
      Number(d.enabled), Number(d.notify_family ?? !!target.is_elderly), c.get('user').id];
    if (method === 'post') {
      await c.env.DB.prepare(`INSERT INTO medication_plans(user_id,product_id,medicine_name,amount,unit,times,frequency,weekdays,interval_days,
        start_date,end_date,meal_relation,notes,enabled,notify_family,updated_by,id,group_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
        .bind(...values, id, g.id, c.get('user').id).run();
    } else {
      await c.env.DB.prepare(`UPDATE medication_plans SET user_id=?,product_id=?,medicine_name=?,amount=?,unit=?,times=?,frequency=?,weekdays=?,
        interval_days=?,start_date=?,end_date=?,meal_relation=?,notes=?,enabled=?,notify_family=?,updated_by=?,updated_at=datetime('now'),
        last_stock_item_id=CASE WHEN product_id IS ? THEN last_stock_item_id ELSE NULL END WHERE id=? AND group_id=?`)
        .bind(...values, d.product_id, id, g.id).run();
    }
    return c.json({ id }, method === 'post' ? 201 : 200);
  });
}
plans.delete('/plans/:id', async c => {
  const g = await group(c);
  const r = await c.env.DB.prepare("UPDATE medication_plans SET enabled=0,updated_by=?,updated_at=datetime('now') WHERE id=? AND group_id=?")
    .bind(c.get('user').id, c.req.param('id'), g.id).run();
  if (!r.meta.changes) fail(404, '计划不存在');
  return c.json({ ok: true });
});
plans.get('/plans/:id/boxes', async c => {
  const g = await group(c);
  const p = await c.env.DB.prepare('SELECT * FROM medication_plans WHERE id=? AND group_id=?').bind(c.req.param('id'), g.id).first<Plan>();
  if (!p) fail(404, '计划不存在');
  const boxes = await c.env.DB.prepare(`SELECT s.*,w.name AS warehouse_name,pr.name,pr.base_unit FROM stock_items s
    JOIN warehouses w ON w.id=s.warehouse_id JOIN products pr ON pr.id=s.product_id
    WHERE s.group_id=? AND s.product_id=? AND s.status='active' AND s.units_remaining>0
    ORDER BY s.opened DESC,s.expiry_date IS NULL,s.expiry_date,s.created_at`).bind(g.id, p.product_id).all<StockItem>();
  return c.json({ boxes: boxes.results, last_stock_item_id: p.last_stock_item_id });
});
plans.post('/doses', async c => {
  const g = await group(c), now = new Date(), clock = localClock(now, g.timezone);
  const d = await json(c, z.object({
    plan_id: z.string(), local_date: dateSchema, scheduled_time: z.string().max(80),
    status: z.enum(['taken', 'skipped']), amount: amountSchema.optional(), taken_at: z.iso.datetime({ offset: true }).optional(),
    stock_item_id: z.string().nullable().default(null), confirm_expired: z.boolean().default(false),
  }));
  const p = await c.env.DB.prepare('SELECT * FROM medication_plans WHERE id=? AND group_id=?').bind(d.plan_id, g.id).first<Plan>();
  if (!p || !p.enabled) fail(404, '计划不存在或已暂停');
  if (d.local_date !== clock.date) fail(400, '只能记录群组时区的当天服药');
  if (p.frequency === 'as_needed') {
    if (clock.date < p.start_date || (p.end_date && clock.date > p.end_date)) fail(400, '不在计划日期范围内');
    if (!/^as-needed:[\w-]{1,60}$/.test(d.scheduled_time)) fail(400, '按需记录编号无效');
  } else if (!scheduledOn(p, clock.date) || !(JSON.parse(p.times) as string[]).includes(d.scheduled_time)) fail(400, '这不是今天的服药项');
  if (d.status === 'taken') {
    if (!d.amount || !d.taken_at) fail(400, '请确认服药时间和用量');
    const taken = new Date(d.taken_at);
    if (localClock(taken, g.timezone).date !== clock.date || taken.getTime() > now.getTime() + 300000) fail(400, '服药时间必须是今天，不能晚于现在');
  }
  if (d.stock_item_id && d.status === 'taken') {
    const box = await c.env.DB.prepare('SELECT * FROM stock_items WHERE id=? AND group_id=? AND product_id=?')
      .bind(d.stock_item_id, g.id, p.product_id).first<StockItem>();
    if (!box) fail(400, '药盒不属于此药品或群组');
    if (box.expiry_date && box.expiry_date < clock.date && !d.confirm_expired) fail(400, '药已过期，请再次明确确认');
  }
  const id = crypto.randomUUID();
  try {
    // Triggers run inside this D1 batch, so validation, stock, history and the remembered box commit together.
    await c.env.DB.batch([c.env.DB.prepare(`INSERT INTO dose_logs(id,plan_id,user_id,group_id,local_date,scheduled_time,status,taken_at,amount,unit,stock_item_id,actor_id)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).bind(id, p.id, p.user_id, g.id, d.local_date, d.scheduled_time, d.status,
        d.status === 'taken' ? d.taken_at! : null, d.status === 'taken' ? d.amount! : 0, p.unit,
        d.status === 'taken' ? d.stock_item_id : null, c.get('user').id)]);
  } catch { fail(409, '已记录过这次服药，或库存不足。请刷新查看'); }
  return c.json({ id }, 201);
});
plans.post('/doses/:id/undo', async c => {
  const g = await group(c), date = localClock(new Date(), g.timezone).date;
  const result = await c.env.DB.batch([c.env.DB.prepare(`UPDATE dose_logs SET status='undone',undone_by=?,undone_at=?
    WHERE id=? AND group_id=? AND local_date=? AND status IN ('taken','skipped')`)
    .bind(c.get('user').id, new Date().toISOString(), c.req.param('id'), g.id, date)]);
  if (!result[0].meta.changes) fail(409, '只能撤销今天尚未撤销的记录');
  return c.json({ ok: true });
});
plans.get('/doses', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare(`SELECT d.*,p.medicine_name,u.username AS actor_name FROM dose_logs d
    JOIN medication_plans p ON p.id=d.plan_id JOIN users u ON u.id=d.actor_id WHERE d.group_id=? ORDER BY d.created_at DESC LIMIT 500`)
    .bind(g.id).all()).results);
});
