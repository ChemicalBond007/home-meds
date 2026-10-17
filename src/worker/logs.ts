import { Hono } from 'hono';
import { z } from 'zod';
import { type AppEnv, admin, fail } from './core';
import { dateSchema, idSchema } from '../shared/schemas';
import { logSourceKeys, type LogSource, type UsageLog } from '../shared/logs';

const queries: Record<LogSource, string> = {
  audit: `SELECT CAST(id AS TEXT) AS id,created_at,actor_id,actor_name,group_id,group_name,action,outcome,http_status,
    target_id,subject_name,method||' '||route AS detail FROM audit_logs`,
  lookup: `SELECT l.id,l.created_at,l.user_id AS actor_id,u.username AS actor_name,l.group_id,g.name AS group_name,
    'ai.lookup' AS action,CASE l.success WHEN 1 THEN 'success' ELSE 'failure' END AS outcome,NULL AS http_status,
    NULL AS target_id,NULL AS subject_name,l.query AS detail
    FROM ai_lookup_logs l JOIN users u ON u.id=l.user_id LEFT JOIN groups g ON g.id=l.group_id`,
  usage: `SELECT a.local_date||':'||a.scope||':'||a.kind AS id,a.local_date||' 00:00:00' AS created_at,
    u.id AS actor_id,u.username AS actor_name,g.id AS group_id,g.name AS group_name,'usage.'||a.kind AS action,
    NULL AS outcome,NULL AS http_status,NULL AS target_id,NULL AS subject_name,CAST(a.count AS TEXT) AS detail
    FROM ai_usage a LEFT JOIN users u ON a.scope='user:'||u.id
    LEFT JOIN groups g ON (a.scope='group:'||g.id OR (u.id IS NOT NULL AND u.group_id=g.id))`,
  dose: `SELECT d.id,d.created_at,d.actor_id,u.username AS actor_name,d.group_id,g.name AS group_name,
    'dose.record' AS action,'success' AS outcome,NULL AS http_status,d.plan_id AS target_id,subject.username AS subject_name,
    p.medicine_name||' · '||d.local_date||' '||d.scheduled_time||' · '||
    CASE d.status WHEN 'taken' THEN '已吃 '||d.amount||d.unit WHEN 'skipped' THEN '已跳过'
      ELSE '已撤销（'||COALESCE(undo.username,'未知')||'，'||COALESCE(d.undone_at,'')||'）' END AS detail
    FROM dose_logs d JOIN users u ON u.id=d.actor_id JOIN users subject ON subject.id=d.user_id
    JOIN medication_plans p ON p.id=d.plan_id JOIN groups g ON g.id=d.group_id LEFT JOIN users undo ON undo.id=d.undone_by`,
  stock: `SELECT m.id,m.created_at,m.actor_id,u.username AS actor_name,s.group_id,g.name AS group_name,
    'stock.'||m.type AS action,'success' AS outcome,NULL AS http_status,m.stock_item_id AS target_id,NULL AS subject_name,
    p.name||' · '||m.amount||p.base_unit||' · '||COALESCE(f.name,'—')||' → '||COALESCE(t.name,'—') AS detail
    FROM stock_movements m JOIN stock_items s ON s.id=m.stock_item_id JOIN products p ON p.id=s.product_id
    JOIN users u ON u.id=m.actor_id JOIN groups g ON g.id=s.group_id
    LEFT JOIN warehouses f ON f.id=m.from_warehouse_id LEFT JOIN warehouses t ON t.id=m.to_warehouse_id`,
};
const filters = z.object({
  source: z.enum(logSourceKeys).default('audit'),
  user_id: idSchema.optional(), group_id: idSchema.optional(),
  from: dateSchema.optional(), to: dateSchema.optional(),
  outcome: z.enum(['success', 'failure']).optional(),
  action: z.string().min(1).max(80).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(30),
  cursor: z.string().max(1000).optional(),
}).refine(q => !q.from || !q.to || q.from <= q.to, '开始日期不能晚于结束日期');
const cursorSchema = z.object({
  source: z.enum(logSourceKeys), at: z.string().regex(/^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d{3})?$/), id: z.string().min(1).max(250),
});
export const logs = new Hono<AppEnv>();
logs.get('/admin/logs', async c => {
  admin(c);
  const parsed = filters.safeParse(c.req.query());
  if (!parsed.success) fail(400, '日志筛选条件无效，请检查日期和每页条数');
  const q = parsed.data, where: string[] = [], values: (string | number)[] = [];
  for (const key of ['user_id', 'group_id', 'outcome', 'action'] as const) {
    if (q[key]) { where.push(`${key === 'user_id' ? 'actor_id' : key}=?`); values.push(q[key]); }
  }
  if (q.from) { where.push('created_at>=?'); values.push(`${q.from} 00:00:00`); }
  if (q.to) { where.push('created_at<=?'); values.push(`${q.to} 23:59:59.999`); }
  if (q.cursor) {
    try {
      const cursor = cursorSchema.parse(JSON.parse(atob(q.cursor)));
      if (cursor.source !== q.source) fail(400, '分页游标不匹配');
      where.push('(created_at<? OR (created_at=? AND id<?))'); values.push(cursor.at, cursor.at, cursor.id);
    } catch { fail(400, '分页游标无效，请返回第一页'); }
  }
  const result = await c.env.DB.prepare(`SELECT * FROM (${queries[q.source]}) WHERE ${where.join(' AND ') || '1=1'}
    ORDER BY created_at DESC,id DESC LIMIT ?`).bind(...values, q.limit + 1).all<UsageLog>();
  const items = result.results.slice(0, q.limit), last = items.at(-1);
  return c.json({ items, next_cursor: result.results.length > q.limit && last
    ? btoa(JSON.stringify({ source: q.source, at: last.created_at, id: last.id })) : null });
});
