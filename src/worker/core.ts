import type { Context } from 'hono';
import { HTTPException } from 'hono/http-exception';
import type { User, FamilyGroup } from '../shared/types';
import { z } from 'zod';

export type Bindings = Omit<Env, 'VAPID_PUBLIC_KEY' | 'VAPID_SUBJECT'> & { VAPID_PUBLIC_KEY: string; VAPID_SUBJECT: string };
export type AppEnv = {
  Bindings: Bindings;
  Variables: {
    user: User; session: { token_hash: string; binding_only: number }; group: FamilyGroup;
    auditActor?: User; auditSubject?: string; auditTarget?: string; auditStreaming?: boolean; auditFailed?: boolean;
  };
};
export type Ctx = Context<AppEnv>;
export function fail(status: 400 | 401 | 403 | 404 | 409 | 429 | 503, message: string): never {
  throw new HTTPException(status, { message });
}
export async function json<T extends z.ZodType>(c: Ctx, schema: T): Promise<z.output<T>> {
  const parsed = schema.safeParse(await c.req.json().catch(() => null));
  if (!parsed.success) fail(400, parsed.error.issues.map(i => `${i.path.join('.')}: ${i.message}`).join('；'));
  return parsed.data;
}
export function admin(c: Ctx) {
  if (c.get('user').role !== 'admin') fail(403, '仅管理员可操作');
}
export async function group(c: Ctx): Promise<FamilyGroup> {
  const u = c.get('user');
  const id = u.role === 'admin' ? c.req.header('X-Group-Id') || u.group_id : u.group_id;
  if (!id) fail(400, '请先选择群组');
  const g = await c.env.DB.prepare('SELECT * FROM groups WHERE id = ?').bind(id).first<FamilyGroup>();
  if (!g) fail(404, '群组不存在');
  c.set('group', g);
  return g;
}
export async function limit(db: D1Database, key: string, maximum: number, seconds: number) {
  const now = Math.floor(Date.now() / 1000);
  const row = await db.prepare(`INSERT INTO rate_limits(key,count,expires_at) VALUES(?,1,?)
    ON CONFLICT(key) DO UPDATE SET count = CASE WHEN expires_at <= ? THEN 1 ELSE count+1 END,
      expires_at = CASE WHEN expires_at <= ? THEN excluded.expires_at ELSE expires_at END
    RETURNING count`).bind(key, now + seconds, now, now).first<{ count: number }>();
  if (!row || row.count > maximum) fail(429, '操作太频繁，请稍后再试');
}
