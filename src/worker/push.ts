import { Hono } from 'hono';
import { z } from 'zod';
import { buildPushPayload } from '@block65/webcrypto-web-push';
import { type AppEnv, type Bindings, json, fail } from './core';
import { publicUrl } from './ai-http';
import { localClock, dayNumber, minutes, scheduledOn } from '../shared/schedule';
import type { Plan } from '../shared/types';
interface Subscription { id: string; user_id: string; endpoint: string; p256dh: string; auth: string; group_id: string | null; is_elderly: number }
export const push = new Hono<AppEnv>();
push.get('/push/config', c => c.json({ public_key: c.env.VAPID_PUBLIC_KEY, configured: !!(c.env.VAPID_PUBLIC_KEY && c.env.VAPID_PRIVATE_KEY) }));
push.post('/push/subscribe', async c => {
  if (!c.env.VAPID_PUBLIC_KEY || !c.env.VAPID_PRIVATE_KEY) fail(503, '管理员尚未配置推送密钥');
  const d = await json(c, z.object({
    endpoint: z.url().max(4096), keys: z.object({ p256dh: z.string().regex(/^[A-Za-z0-9_-]{87}={0,2}$/), auth: z.string().regex(/^[A-Za-z0-9_-]{22}={0,2}$/) }),
  }));
  try { if (publicUrl(d.endpoint).protocol !== 'https:') fail(400, '推送地址必须是 HTTPS'); } catch { fail(400, '推送地址无效'); }
  // An endpoint can change owner when a family shares a device; only its current signed-in user receives reminders.
  await c.env.DB.prepare(`INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth) VALUES(?,?,?,?,?)
    ON CONFLICT(endpoint) DO UPDATE SET user_id=excluded.user_id,p256dh=excluded.p256dh,auth=excluded.auth`)
    .bind(crypto.randomUUID(), c.get('user').id, d.endpoint, d.keys.p256dh, d.keys.auth).run();
  return c.json({ ok: true });
});
push.post('/push/unsubscribe', async c => {
  const d = await json(c, z.object({ endpoint: z.string().max(4096) }));
  await c.env.DB.prepare('DELETE FROM push_subscriptions WHERE user_id=? AND endpoint=?').bind(c.get('user').id, d.endpoint).run();
  return c.json({ ok: true });
});
async function send(env: Bindings, sub: Subscription, message: { title: string; body: string; url: string; tag: string }) {
  const payload = await buildPushPayload({ data: message, options: { ttl: 3600, urgency: 'high' } }, {
    endpoint: sub.endpoint, expirationTime: null, keys: { p256dh: sub.p256dh, auth: sub.auth },
  }, { subject: env.VAPID_SUBJECT, publicKey: env.VAPID_PUBLIC_KEY, privateKey: env.VAPID_PRIVATE_KEY });
  // The endpoint comes from the browser, so a 3xx is never followed; it fails the ok check below.
  const response = await fetch(sub.endpoint, { ...payload, redirect: 'manual', signal: AbortSignal.timeout(10000) });
  await response.body?.cancel();
  if (response.status === 404 || response.status === 410) {
    await env.DB.prepare('DELETE FROM push_subscriptions WHERE id=?').bind(sub.id).run();
    return;
  }
  if (!response.ok) throw new Error(`Push ${response.status}`);
}
export async function runReminders(env: Bindings, now = new Date()) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE expires_at<?').bind(now.getTime()),
    env.DB.prepare('DELETE FROM auth_challenges WHERE expires_at<?').bind(now.getTime()),
    env.DB.prepare('DELETE FROM rate_limits WHERE expires_at<?').bind(Math.floor(now.getTime() / 1000)),
    env.DB.prepare('DELETE FROM notification_log WHERE local_date<?').bind(new Date(now.getTime() - 35 * 86400000).toISOString().slice(0, 10)),
  ]);
  if (!env.VAPID_PRIVATE_KEY || !env.VAPID_PUBLIC_KEY) return;
  const plans = await env.DB.prepare(`SELECT p.*,g.timezone,u.username FROM medication_plans p
    JOIN groups g ON g.id=p.group_id JOIN users u ON u.id=p.user_id
    WHERE p.enabled=1 AND p.frequency<>'as_needed'`).all<Plan & { timezone: string; username: string }>();
  const subscriptions = await env.DB.prepare(`SELECT s.*,u.group_id,u.is_elderly FROM push_subscriptions s JOIN users u ON u.id=s.user_id`).all<Subscription>();
  for (const p of plans.results) {
    const clock = localClock(now, p.timezone);
    const yesterday = new Date((dayNumber(clock.date) - 1) * 86400000).toISOString().slice(0, 10);
    for (const date of [yesterday, clock.date]) {
      if (!scheduledOn(p, date)) continue;
      const logs = await env.DB.prepare(`SELECT scheduled_time FROM dose_logs WHERE plan_id=? AND local_date=? AND status<>'undone'`)
        .bind(p.id, date).all<{ scheduled_time: string }>();
      for (const time of JSON.parse(p.times) as string[]) {
        const elapsed = (dayNumber(clock.date) - dayNumber(date)) * 1440 + minutes(clock.time) - minutes(time);
        if (elapsed < 0 || elapsed >= 1440 || logs.results.some(l => l.scheduled_time === time)) continue;
        const recipients = subscriptions.results.filter(s => s.user_id === p.user_id ||
          (p.notify_family && elapsed >= 30 && s.group_id === p.group_id && !s.is_elderly));
        for (const sub of recipients) {
          const kind = sub.user_id === p.user_id ? 'self' : 'family';
          const claimedAt = now.getTime();
          const args = [p.id, date, time, kind, sub.id];
          const claim = await env.DB.prepare(`INSERT INTO notification_log(plan_id,local_date,scheduled_time,kind,subscription_id,claimed_at)
            VALUES(?,?,?,?,?,?) ON CONFLICT(plan_id,local_date,scheduled_time,kind,subscription_id)
            DO UPDATE SET claimed_at=excluded.claimed_at,status='sending'
            WHERE status='failed' OR (status='sending' AND claimed_at<?) RETURNING plan_id`)
            .bind(...args, claimedAt, claimedAt - 180000).first();
          if (!claim) continue;
          try {
            const resolved = await env.DB.prepare(`SELECT id FROM dose_logs WHERE plan_id=? AND local_date=? AND scheduled_time=? AND status<>'undone'`)
              .bind(p.id, date, time).first();
            if (!resolved) await send(env, sub, {
              title: kind === 'self' ? '该确认今天的药了' : `${p.username}还没有记录服药`,
              body: kind === 'self' ? `${time} ${p.medicine_name}，请打开应用核对时间、用量和药盒。` : `${time} 的服药尚未确认，请先联系家人核实，不要重复服药。`,
              url: `/?plan=${encodeURIComponent(p.id)}&date=${date}&time=${encodeURIComponent(time)}&group=${encodeURIComponent(p.group_id)}`,
              tag: `${p.id}:${date}:${time}:${kind}`,
            });
            await env.DB.prepare(`UPDATE notification_log SET status='sent' WHERE plan_id=? AND local_date=? AND scheduled_time=? AND kind=? AND subscription_id=? AND claimed_at=?`)
              .bind(...args, claimedAt).run();
          } catch {
            await env.DB.prepare(`UPDATE notification_log SET status='failed' WHERE plan_id=? AND local_date=? AND scheduled_time=? AND kind=? AND subscription_id=? AND claimed_at=?`)
              .bind(...args, claimedAt).run();
            console.error(JSON.stringify({ event: 'push_failed', kind }));
          }
        }
      }
    }
  }
}
