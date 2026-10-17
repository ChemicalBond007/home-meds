import { createMiddleware } from 'hono/factory';
import type { AppEnv, Ctx } from './core';

type Scope = 'account' | 'group' | 'target-group' | 'global';
type Policy = { method: string; route: string; action: string; scope: Scope };
const policies: Policy[] = [
  ['POST', '/auth/register', 'auth.register', 'account'],
  ['POST', '/auth/login', 'auth.login', 'account'],
  ['POST', '/auth/logout', 'auth.logout', 'account'],
  ['POST', '/auth/totp/setup', 'auth.totp.setup', 'account'],
  ['POST', '/auth/totp/bind', 'auth.totp.bind', 'account'],
  ['POST', '/auth/passkey/options', 'auth.passkey.options', 'account'],
  ['POST', '/auth/passkey/login', 'auth.passkey.login', 'account'],
  ['POST', '/auth/passkey/register/options', 'auth.passkey.register.options', 'account'],
  ['POST', '/auth/passkey/register', 'auth.passkey.register', 'account'],
  ['POST', '/auth/recovery/passkey/options', 'auth.recovery.options', 'account'],
  ['POST', '/auth/recovery/regenerate', 'auth.recovery.regenerate', 'account'],
  ['POST', '/admin/users/:id/reset', 'admin.reset', 'global'],
  ['POST', '/groups', 'group.create', 'target-group'],
  ['PUT', '/groups/:id', 'group.update', 'target-group'],
  ['POST', '/groups/:id/rotate-code', 'group.rotate', 'target-group'],
  ['PATCH', '/members/:id', 'member.update', 'global'],
  ['POST', '/warehouses', 'warehouse.create', 'group'],
  ['PUT', '/warehouses/:id', 'warehouse.update', 'group'],
  ['DELETE', '/warehouses/:id', 'warehouse.delete', 'group'],
  ['POST', '/products', 'product.create', 'group'],
  ['PUT', '/products/:id', 'product.update', 'group'],
  ['POST', '/stock', 'stock.create', 'group'],
  ['PUT', '/stock/:id', 'stock.update', 'group'],
  ['POST', '/stock/:id/move', 'stock.change', 'group'],
  ['POST', '/plans', 'plan.create', 'group'],
  ['PUT', '/plans/:id', 'plan.update', 'group'],
  ['DELETE', '/plans/:id', 'plan.pause', 'group'],
  ['POST', '/doses', 'dose.create', 'group'],
  ['POST', '/doses/:id/undo', 'dose.undo', 'group'],
  ['GET', '/ai/settings', 'ai.settings.read', 'global'],
  ['PUT', '/ai/settings', 'ai.settings.update', 'global'],
  ['POST', '/ai/probe', 'ai.probe', 'global'],
  ['POST', '/ai/models', 'ai.models', 'global'],
  ['POST', '/ai/search-probe', 'ai.search.probe', 'global'],
  ['POST', '/ai/lookup', 'ai.lookup', 'group'],
  ['POST', '/ai/chat', 'ai.chat', 'group'],
  ['POST', '/push/subscribe', 'push.subscribe', 'account'],
  ['POST', '/push/unsubscribe', 'push.unsubscribe', 'account'],
  ['GET', '/admin/logs', 'logs.view', 'global'],
].map(([method, route, action, scope]) => ({ method, route: `/api${route}`, action, scope: scope as Scope }));
const routes = policies.map(policy => ({ policy, pattern: new RegExp(`^${policy.route.replace(':id', '([^/]+)')}$`) }));
const safeId = (id: string | undefined) => id && /^[\w-]{1,100}$/.test(id) ? id : null;

// Only allowlisted metadata is persisted. Never read request/response bodies,
// cookies, headers, query strings, provider errors or authentication proofs.
export async function writeAudit(c: Ctx, outcome?: 'success' | 'failure', status = c.res.status) {
  const matched = routes.find(r => r.policy.method === c.req.method && r.pattern.test(c.req.path));
  if (!matched && ['GET', 'HEAD', 'OPTIONS'].includes(c.req.method)) return;
  const policy = matched?.policy || { action: 'request.other', route: '/api/*', scope: 'global' };
  try {
    const user = c.get('auditActor') || c.get('user');
    const target = safeId(c.get('auditTarget') || matched?.pattern.exec(c.req.path)?.[1]);
    const selected = c.get('group');
    const groupId = policy.scope === 'group' ? selected?.id
      : policy.scope === 'account' ? user?.group_id
      : policy.scope === 'target-group' && status < 400 ? target : null;
    const family = groupId ? await c.env.DB.prepare('SELECT id,name FROM groups WHERE id=?').bind(groupId)
      .first<{ id: string; name: string }>() : null;
    await c.env.DB.prepare(`INSERT INTO audit_logs(actor_id,actor_name,group_id,group_name,action,method,route,outcome,http_status,target_id,subject_name)
      VALUES(?,?,?,?,?,?,?,?,?,?,?)`).bind(user?.id || null, user?.username || null, family?.id || null, family?.name || null,
        policy.action, c.req.method, policy.route, outcome || (status < 400 && !c.get('auditFailed') ? 'success' : 'failure'),
        status, target, c.get('auditSubject') || null).run();
  } catch {
    // A logging outage must not turn an already-committed action into a retry.
    console.error(JSON.stringify({ event: 'audit_write_failed', action: policy.action }));
  }
}
export const audit = createMiddleware<AppEnv>(async (c, next) => {
  await next();
  // SSE handlers log their final application outcome, not the initial HTTP 200.
  if (!c.get('auditStreaming')) await writeAudit(c);
});
