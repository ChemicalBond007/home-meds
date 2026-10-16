import { Hono } from 'hono';
import { z } from 'zod';
import { type AppEnv, admin, group, json, fail } from './core';
import { amountSchema, dateSchema, productSchema } from '../shared/schemas';
import { parseCode } from '../shared/barcode';
import type { Product, StockItem } from '../shared/types';

export const inventory = new Hono<AppEnv>();
inventory.get('/products', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare('SELECT * FROM products WHERE group_id=? ORDER BY name').bind(g.id).all()).results);
});
inventory.get('/codes/resolve', async c => {
  const g = await group(c), parsed = parseCode(c.req.query('code') || '');
  const product = parsed.product_key ? await c.env.DB.prepare(`SELECT p.* FROM product_codes pc JOIN products p ON p.id=pc.product_id
    WHERE pc.group_id=? AND pc.code=?`).bind(g.id, parsed.product_key).first<Product>() : null;
  const box = parsed.trace_code ? await c.env.DB.prepare('SELECT * FROM stock_items WHERE group_id=? AND trace_code=?').bind(g.id, parsed.trace_code).first() : null;
  const cache = !product && parsed.product_key ? await c.env.DB.prepare('SELECT data FROM catalog_cache WHERE code=?').bind(parsed.product_key).first<{ data: string }>() : null;
  return c.json({ parsed, product, box, cache: cache ? JSON.parse(cache.data) : null });
});
inventory.post('/products', async c => {
  const g = await group(c), d = await json(c, productSchema), id = crypto.randomUUID();
  const code = d.code ? parseCode(d.code).product_key || d.code : null;
  const fields = [id, g.id, d.name, d.generic_name, d.manufacturer, d.specification, d.indications, d.precautions,
    d.base_unit, d.units_per_package, d.status, JSON.stringify(d.sources), c.get('user').id];
  const statements = [
    c.env.DB.prepare(`INSERT INTO products(id,group_id,name,generic_name,manufacturer,specification,indications,precautions,
      base_unit,units_per_package,status,sources,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`).bind(...fields),
  ];
  if (code) statements.push(c.env.DB.prepare('INSERT INTO product_codes(group_id,code,product_id) VALUES(?,?,?)').bind(g.id, code, id));
  if (code && d.status === 'confirmed') statements.push(c.env.DB.prepare(`INSERT INTO catalog_cache(code,data) VALUES(?,?)
    ON CONFLICT(code) DO UPDATE SET data=excluded.data,updated_at=datetime('now')`).bind(code, JSON.stringify(d)));
  try { await c.env.DB.batch(statements); } catch { fail(409, '此码已关联药品，请刷新后重试'); }
  return c.json({ id }, 201);
});
inventory.put('/products/:id', async c => {
  admin(c);
  const g = await group(c), d = await json(c, productSchema), id = c.req.param('id');
  const old = await c.env.DB.prepare('SELECT * FROM products WHERE id=? AND group_id=?').bind(id, g.id).first<Product>();
  if (!old) fail(404, '药品不存在');
  if (old.base_unit !== d.base_unit) {
    const used = await c.env.DB.prepare(`SELECT id FROM stock_items WHERE product_id=? UNION ALL SELECT id FROM medication_plans WHERE product_id=? LIMIT 1`).bind(id, id).first();
    if (used) fail(409, '已有库存或计划，不能修改基本单位，请另建药品');
  }
  await c.env.DB.prepare(`UPDATE products SET name=?,generic_name=?,manufacturer=?,specification=?,indications=?,precautions=?,
    base_unit=?,units_per_package=?,status=?,sources=?,updated_by=? WHERE id=? AND group_id=?`)
    .bind(d.name, d.generic_name, d.manufacturer, d.specification, d.indications, d.precautions, d.base_unit, d.units_per_package, d.status, JSON.stringify(d.sources), c.get('user').id, id, g.id).run();
  return c.json({ ok: true });
});
const stockQuery = `SELECT s.*,p.name,p.base_unit,w.name AS warehouse_name FROM stock_items s
  JOIN products p ON p.id=s.product_id JOIN warehouses w ON w.id=s.warehouse_id`;
inventory.get('/stock', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare(`${stockQuery} WHERE s.group_id=? ORDER BY s.status,s.expiry_date IS NULL,s.expiry_date`).bind(g.id).all()).results);
});
inventory.post('/stock', async c => {
  const g = await group(c);
  const d = await json(c, z.object({
    product_id: z.string(), warehouse_id: z.string(), code: z.string().max(4096).optional(),
    expiry_date: dateSchema.nullable().default(null), batch_number: z.string().max(100).nullable().default(null),
    units_per_package: amountSchema.optional(), packages: z.number().int().min(1).max(100).default(1),
  }));
  const p = await c.env.DB.prepare('SELECT * FROM products WHERE id=? AND group_id=?').bind(d.product_id, g.id).first<Product>();
  const w = await c.env.DB.prepare('SELECT id FROM warehouses WHERE id=? AND group_id=?').bind(d.warehouse_id, g.id).first();
  if (!p || !w) fail(400, '请选择本群药品和药箱');
  const code = parseCode(d.code || '');
  if (code.trace_code && d.packages !== 1) fail(400, '一个追溯码只能对应一盒');
  const size = d.units_per_package || p.units_per_package;
  const ids = Array.from({ length: d.packages }, () => crypto.randomUUID());
  const statements = ids.flatMap(id => [
    c.env.DB.prepare(`INSERT INTO stock_items(id,group_id,product_id,warehouse_id,trace_code,batch_number,serial_number,
      expiry_date,units_per_package,units_remaining) VALUES(?,?,?,?,?,?,?,?,?,?)`)
      .bind(id, g.id, p.id, w.id, code.trace_code || null, d.batch_number || code.batch_number || null, code.serial_number || null,
        d.expiry_date || code.expiry_date || null, size, size),
    c.env.DB.prepare(`INSERT INTO stock_movements(id,stock_item_id,type,amount,to_warehouse_id,actor_id) VALUES(?,?,'in',?,?,?)`)
      .bind(crypto.randomUUID(), id, size, w.id, c.get('user').id),
  ]);
  if (code.product_key) statements.push(c.env.DB.prepare(`INSERT INTO product_codes(group_id,code,product_id) VALUES(?,?,?)
    ON CONFLICT(group_id,code) DO UPDATE SET product_id=excluded.product_id`)
    .bind(g.id, code.product_key, p.id));
  if (code.product_key) {
    const mapped = await c.env.DB.prepare('SELECT product_id FROM product_codes WHERE group_id=? AND code=?').bind(g.id, code.product_key).first<{ product_id: string }>();
    if (mapped && mapped.product_id !== p.id) fail(409, '此条码对应另一种药品');
  }
  try { await c.env.DB.batch(statements); } catch { fail(409, '追溯码已入库，不能重复添加'); }
  return c.json({ ids }, 201);
});
inventory.post('/stock/:id/move', async c => {
  const g = await group(c);
  const d = await json(c, z.object({ type: z.enum(['out', 'discard', 'move']), amount: amountSchema.optional(), warehouse_id: z.string().optional() }));
  const s = await c.env.DB.prepare('SELECT * FROM stock_items WHERE id=? AND group_id=?').bind(c.req.param('id'), g.id).first<StockItem>();
  if (!s || s.status !== 'active') fail(404, '没有可用库存');
  if (d.type === 'out' && !d.amount) fail(400, '请填写用掉的数量');
  let to: string | null = null;
  if (d.type === 'move') {
    to = d.warehouse_id || null;
    if (!to || !await c.env.DB.prepare('SELECT id FROM warehouses WHERE id=? AND group_id=?').bind(to, g.id).first()) fail(400, '请选择目标药箱');
  }
  try {
    await c.env.DB.batch([c.env.DB.prepare(`INSERT INTO stock_movements(id,stock_item_id,type,amount,from_warehouse_id,to_warehouse_id,actor_id)
      VALUES(?,?,?,?,?,?,?)`).bind(crypto.randomUUID(), s.id, d.type, d.type === 'discard' ? s.units_remaining : d.amount || 0, s.warehouse_id, to, c.get('user').id)]);
  } catch { fail(409, '库存已变化或数量不足，请刷新'); }
  return c.json({ ok: true });
});
inventory.get('/movements', async c => {
  const g = await group(c);
  return c.json((await c.env.DB.prepare(`SELECT m.*,p.name,u.username FROM stock_movements m
    JOIN stock_items s ON s.id=m.stock_item_id JOIN products p ON p.id=s.product_id JOIN users u ON u.id=m.actor_id
    WHERE s.group_id=? ORDER BY m.created_at DESC,m.rowid DESC LIMIT 500`).bind(g.id).all()).results);
});
