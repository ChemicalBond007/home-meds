import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { bodyLimit } from 'hono/body-limit';
import { secureHeaders } from 'hono/secure-headers';
import { auth } from './auth';
import { management } from './management';
import { inventory } from './inventory';
import { plans } from './plans';
import { ai } from './ai';
import { push, runReminders } from './push';
import { audit } from './audit';
import { logs } from './logs';
import type { AppEnv, Bindings } from './core';

export const app = new Hono<AppEnv>();
app.use('/api/*', secureHeaders());
app.use('/api/*', async (c, next) => {
  c.header('Cache-Control', 'no-store');
  await next();
});
app.use('/api/*', audit);
app.use('/api/*', bodyLimit({ maxSize: 256 * 1024 }));
app.route('/api', auth);
app.get('/api/health', c => c.json({ ok: true }));
app.route('/api', management);
app.route('/api', inventory);
app.route('/api', plans);
app.route('/api', ai);
app.route('/api', push);
app.route('/api', logs);
app.notFound(c => c.json({ error: '接口不存在' }, 404));
app.onError((error, c) => {
  if (error instanceof HTTPException) return c.json({ error: error.message }, error.status);
  console.error(JSON.stringify({ event: 'request_failed', path: c.req.path, type: error.name }));
  return c.json({ error: '操作未完成，请稍后重试' }, 500);
});
export default {
  fetch: app.fetch,
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(runReminders(env, new Date(controller.scheduledTime)));
  },
} satisfies ExportedHandler<Bindings>;
