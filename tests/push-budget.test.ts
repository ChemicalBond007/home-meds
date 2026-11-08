import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { env } from 'cloudflare:workers';
import type { Bindings } from '../src/worker/core';
import { app } from '../src/worker';
import { b64, hash, randomToken } from '../src/worker/crypto';
import { runReminders } from '../src/worker/push';

let runtime: Bindings;
let p256dh: string, auth: string;
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code,timezone) VALUES('g','family','join','UTC')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','member','user','g')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)')
      .bind(await hash('push-session'), 'u', Date.now() + 600000),
  ]);
  const vapid = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  runtime = { ...env, VAPID_PUBLIC_KEY: b64(new Uint8Array(await crypto.subtle.exportKey('raw', vapid.publicKey))),
    VAPID_PRIVATE_KEY: (await crypto.subtle.exportKey('jwk', vapid.privateKey)).d!, VAPID_SUBJECT: 'https://example.com' };
  const receiver = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  p256dh = b64(new Uint8Array(await crypto.subtle.exportKey('raw', receiver.publicKey))); auth = randomToken(16);
});
afterEach(() => vi.restoreAllMocks());
const subscribe = (i: number) => app.request('https://home.test/api/push/subscribe', {
  method: 'POST', headers: { Origin: 'https://home.test', Cookie: 'hm_session=push-session', 'Content-Type': 'application/json' },
  body: JSON.stringify({ endpoint: `https://push.example.com/${i}`, keys: { p256dh, auth } }),
}, runtime);
async function seed(id = 'u', group = 'g', times = ['08:00']) {
  await env.DB.batch([
    env.DB.prepare('INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth) VALUES(?,?,?,?,?)')
      .bind(id, id, `https://push.example.com/${id}`, p256dh, auth),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES(?,?,?,'medicine',1,'片',?,'daily','2020-01-01',?,?)`).bind(id, group, id, JSON.stringify(times), id, id),
  ]);
}
describe('push amplification security regressions', () => {
  it('blocks the original twelve-subscription, twenty-four-time failed-push amplification', async () => {
    for (let i = 0; i < 12; i++) expect((await subscribe(i)).status).toBe(i < 10 ? 200 : 429);
    await env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('p','g','u','medicine',1,'片',?,'daily','2020-01-01','u','u')`)
      .bind(JSON.stringify(Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`))).run();
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(null, { status: 500 }));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    await runReminders(runtime, new Date('2026-01-01T23:50:00Z'));
    expect(fetch).toHaveBeenCalledTimes(30);
    await runReminders(runtime, new Date('2026-01-01T23:55:00Z'));
    expect(fetch.mock.calls.length).toBeLessThanOrEqual(60);
    // The hourly cap and subscription eviction stop the original 288-send fanout.
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM push_subscriptions').first('n')).toBeLessThanOrEqual(5);
    const before = fetch.mock.calls.length;
    await runReminders(runtime, new Date('2026-01-01T23:56:00Z'));
    expect(fetch.mock.calls.length - before).toBeLessThanOrEqual(30);
    expect(await env.DB.prepare("SELECT MAX(attempts) n FROM notification_log WHERE status='failed'").first('n')).toBe(1);
  });
  it('retains only the newest five subscriptions and allows at most ten registrations per hour', async () => {
    for (let i = 0; i < 12; i++) expect((await subscribe(i)).status).toBe(i < 10 ? 200 : 429);
    expect((await env.DB.prepare('SELECT endpoint FROM push_subscriptions ORDER BY rowid').all()).results
      .map(s => s.endpoint)).toEqual([5, 6, 7, 8, 9].map(i => `https://push.example.com/${i}`));
    await env.DB.prepare('UPDATE rate_limits SET expires_at=0').run();
    expect((await subscribe(12)).status).toBe(200);
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM push_subscriptions').first('n')).toBe(5);
  });
  it('retries only three times with 5/10/20 minute backoff, including stale sending claims', async () => {
    await seed();
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(null, { status: 500 }));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const run = (minute: number) => runReminders(runtime, new Date(Date.parse('2026-01-01T08:00:00Z') + minute * 60000));
    for (const [minute, expected] of [[0, 1], [4, 1], [5, 2], [10, 2], [15, 3], [30, 3], [35, 4], [60, 4]]) {
      await run(minute); expect(fetch).toHaveBeenCalledTimes(expected);
    }
    expect(await env.DB.prepare('SELECT attempts FROM notification_log').first('attempts')).toBe(3);
    await env.DB.prepare("UPDATE notification_log SET status='sending',attempts=3,claimed_at=0").run();
    await run(65); expect(fetch).toHaveBeenCalledTimes(4);
  });
  it('resets failures on success and removes a subscription on its tenth consecutive failure', async () => {
    await seed();
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response(null, { status: 201 }));
    await env.DB.prepare('UPDATE push_subscriptions SET failures=9').run();
    await runReminders(runtime, new Date('2026-01-01T08:00:00Z'));
    expect(await env.DB.prepare('SELECT failures FROM push_subscriptions').first('failures')).toBe(0);
    await env.DB.prepare('UPDATE push_subscriptions SET failures=9').run();
    await env.DB.prepare('DELETE FROM notification_log').run();
    fetch.mockImplementation(async () => new Response(null, { status: 500 }));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    await runReminders(runtime, new Date('2026-01-01T08:05:00Z'));
    expect(await env.DB.prepare('SELECT COUNT(*) n FROM push_subscriptions').first('n')).toBe(0);
  });
  it('bounds global sends to 150, family sends to 30, concurrency to six and leaves backlog for later', async () => {
    const times = Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`);
    await seed('u', 'g', times);
    for (let i = 0; i < 6; i++) {
      const group = `g${i}`, id = `u${i}`;
      await env.DB.batch([
        env.DB.prepare('INSERT INTO groups(id,name,join_code,timezone) VALUES(?,?,?,?)').bind(group, group, group, 'UTC'),
        env.DB.prepare('INSERT INTO users(id,username,role,group_id) VALUES(?,?,?,?)').bind(id, id, 'user', group),
      ]);
      await seed(id, group, times);
    }
    // Two subscriptions per family create a 48-send family backlog.
    for (const id of ['u', ...Array.from({ length: 6 }, (_, i) => `u${i}`)]) {
      await env.DB.prepare('INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth) VALUES(?,?,?,?,?)')
        .bind(`${id}-2`, id, `https://push.example.com/${id}-2`, p256dh, auth).run();
    }
    let active = 0, peak = 0;
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, init) => {
      active++; peak = Math.max(peak, active);
      expect(init?.redirect).toBe('manual'); expect(init?.signal).toBeTruthy();
      await new Promise(resolve => setTimeout(resolve, 1)); active--;
      return new Response(null, { status: 201 });
    });
    await runReminders(runtime, new Date('2026-01-01T23:50:00Z'));
    expect(fetch).toHaveBeenCalledTimes(150); expect(peak).toBe(6);
    const counts = await env.DB.prepare(`SELECT p.group_id,COUNT(*) n FROM notification_log l
      JOIN medication_plans p ON p.id=l.plan_id GROUP BY p.group_id`).all<{ group_id: string; n: number }>();
    expect(Math.max(...counts.results.map(r => r.n))).toBe(30);
    await runReminders(runtime, new Date('2026-01-01T23:55:00Z'));
    expect(fetch).toHaveBeenCalledTimes(300);
    await runReminders(runtime, new Date('2026-01-01T23:59:00Z'));
    expect(fetch).toHaveBeenCalledTimes(336);
  }, 30000);
});
