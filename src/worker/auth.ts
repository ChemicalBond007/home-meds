import { Hono } from 'hono';
import { getCookie, setCookie, deleteCookie } from 'hono/cookie';
import { z } from 'zod';
import {
  generateRegistrationOptions, verifyRegistrationResponse,
  generateAuthenticationOptions, verifyAuthenticationResponse,
} from '@simplewebauthn/server';
import type { RegistrationResponseJSON, AuthenticationResponseJSON } from '@simplewebauthn/server';
import { type AppEnv, type Ctx, fail, json, limit, admin } from './core';
import { hash, randomToken, encrypt, decrypt, base32, b64, unb64, checkTotp } from './crypto';
import { consumeSecret, issueSecrets } from './one-time';
import type { User } from '../shared/types';

const username = z.string().trim().min(2).max(40).regex(/^[\p{L}\p{N}_-]+$/u, '仅支持文字、数字、下划线和短横线');
const credentials = z.object({ username, secret: z.string().max(300).optional(), code: z.string().max(10).optional() });
const assertion = z.object({ id: z.string(), response: z.custom<AuthenticationResponseJSON>(x => typeof (x as { id?: unknown } | null)?.id === 'string') });
const cookieName = 'hm_session';
export const auth = new Hono<AppEnv>();

export async function session(c: Ctx, userId: string, bindingOnly = false) {
  const token = randomToken();
  const expires = Date.now() + (bindingOnly ? 30 * 60000 : 365 * 86400000);
  await c.env.DB.prepare('INSERT INTO sessions(token_hash,user_id,binding_only,expires_at) VALUES(?,?,?,?)')
    .bind(await hash(token), userId, Number(bindingOnly), expires).run();
  const actor = await c.env.DB.prepare('SELECT id,username,role,group_id,is_elderly FROM users WHERE id=?').bind(userId).first<User>();
  if (actor) c.set('auditActor', actor);
  setCookie(c, cookieName, token, {
    httpOnly: true, secure: new URL(c.req.url).protocol === 'https:', sameSite: 'Strict', path: '/',
    maxAge: bindingOnly ? 1800 : 365 * 86400,
  });
}
type Totp = { user_id: string; encrypted_secret: string; last_step: number };
const verifiedTotp = (c: Ctx, userId: string) => c.env.DB.prepare('SELECT user_id,encrypted_secret,last_step FROM totp_secrets WHERE user_id=? AND verified=1')
  .bind(userId).first<Totp>();
async function consumeTotp(c: Ctx, t: Totp, code: string) {
  const step = await checkTotp(await decrypt(c.env.APP_ENC_KEY, 'totp', t.encrypted_secret), code, t.last_step);
  if (step === null) fail(401, '验证码无效或已使用');
  const changed = await c.env.DB.prepare('UPDATE totp_secrets SET last_step=? WHERE user_id=? AND last_step<?')
    .bind(step, t.user_id, step).run();
  if (!changed.meta.changes) fail(401, '验证码已使用');
}
const passkeyIds = async (c: Ctx, userId: string) => (await c.env.DB.prepare('SELECT id FROM webauthn_credentials WHERE user_id=?')
  .bind(userId).all<{ id: string }>()).results.map(x => x.id);
async function passkeyChallenge(c: Ctx, userId: string, ids: string[], sessionHash: string | null) {
  const options = await generateAuthenticationOptions({
    rpID: new URL(c.req.url).hostname, userVerification: 'required', allowCredentials: ids.map(id => ({ id })),
  });
  const id = randomToken();
  await c.env.DB.prepare("INSERT INTO auth_challenges(id,user_id,kind,challenge,session_hash,expires_at) VALUES(?,?,'authenticate',?,?,?)")
    .bind(id, userId, options.challenge, sessionHash, Date.now() + 300000).run();
  return { id, options };
}
async function checkPasskey(c: Ctx, userId: string, challenge: string, response: AuthenticationResponseJSON) {
  const cred = await c.env.DB.prepare('SELECT * FROM webauthn_credentials WHERE id=? AND user_id=?').bind(response.id, userId)
    .first<{ id: string; public_key: string; counter: number }>();
  if (!cred) fail(401, '通行密钥无效');
  const result = await verifyAuthenticationResponse({
    response, expectedChallenge: challenge, expectedOrigin: new URL(c.req.url).origin,
    expectedRPID: new URL(c.req.url).hostname, requireUserVerification: true,
    credential: { id: cred.id, publicKey: unb64(cred.public_key), counter: cred.counter },
  }).catch(() => null);
  if (!result?.verified) fail(401, '通行密钥无效');
  return c.env.DB.prepare('UPDATE webauthn_credentials SET counter=? WHERE id=?').bind(result.authenticationInfo.newCounter, cred.id);
}
auth.use('*', async (c, next) => {
  if (!['GET', 'HEAD', 'OPTIONS'].includes(c.req.method)) {
    const origin = c.req.header('Origin');
    if (origin !== new URL(c.req.url).origin) fail(403, '请求来源无效，请刷新页面');
  }
  const token = getCookie(c, cookieName);
  if (token) {
    const s = await c.env.DB.prepare(`SELECT s.token_hash,s.binding_only,s.expires_at,u.*
      FROM sessions s JOIN users u ON u.id=s.user_id WHERE token_hash=? AND expires_at>?`)
      .bind(await hash(token), Date.now()).first<User & { token_hash: string; binding_only: number; expires_at: number }>();
    if (s) {
      c.set('user', { id: s.id, username: s.username, role: s.role, group_id: s.group_id, is_elderly: s.is_elderly });
      c.set('session', s);
      if (!s.binding_only && s.expires_at < Date.now() + 364 * 86400000) {
        await c.env.DB.prepare('UPDATE sessions SET expires_at=? WHERE token_hash=?').bind(Date.now() + 365 * 86400000, s.token_hash).run();
        setCookie(c, cookieName, token, { httpOnly: true, secure: new URL(c.req.url).protocol === 'https:', sameSite: 'Strict', path: '/', maxAge: 365 * 86400 });
      }
    }
  }
  const path = c.req.path.replace('/api', '');
  const publicPaths = ['/auth/register', '/auth/login', '/auth/passkey/options', '/auth/passkey/login', '/health'];
  if (!publicPaths.includes(path) && !c.get('user')) fail(401, '请先登录');
  if (c.get('session')?.binding_only && ![
    '/auth/me', '/auth/logout', '/auth/totp/setup', '/auth/totp/bind', '/auth/passkey/register/options', '/auth/passkey/register',
  ].includes(path)) fail(403, '请先绑定验证器或通行密钥');
  await next();
});
auth.post('/auth/register', async c => {
  const data = await json(c, z.object({ username, join_code: z.string().min(1).max(100), is_elderly: z.boolean().default(false) }));
  c.set('auditSubject', data.username);
  await limit(c.env.DB, `register:${c.req.header('CF-Connecting-IP') || 'local'}`, 10, 3600);
  const g = await c.env.DB.prepare('SELECT id FROM groups WHERE join_code=?').bind(data.join_code.trim()).first<{ id: string }>();
  if (!g) fail(400, '分组码无效');
  const id = crypto.randomUUID();
  try {
    await c.env.DB.prepare('INSERT INTO users(id,username,group_id,is_elderly) VALUES(?,?,?,?)').bind(id, data.username, g.id, Number(data.is_elderly)).run();
  } catch { fail(409, '用户名已被使用'); }
  await session(c, id);
  return c.json({ ok: true });
});
auth.post('/auth/login', async c => {
  const data = await json(c, credentials);
  c.set('auditSubject', data.username);
  await limit(c.env.DB, `login:${c.req.header('CF-Connecting-IP') || 'local'}`, 30, 600);
  await limit(c.env.DB, `login-user:${data.username.toLowerCase()}`, 15, 600);
  const u = await c.env.DB.prepare('SELECT * FROM users WHERE username=?').bind(data.username).first<User>();
  if (!u) fail(401, '账号或凭证错误');
  if (u.role === 'user') {
    await session(c, u.id);
    return c.json({ ok: true });
  }
  if (data.secret) {
    const used = await consumeSecret(c.env.DB, u.id, data.secret);
    if (!used) fail(401, '账号或凭证错误');
    if (used.kind === 'recovery') {
      await c.env.DB.batch([
        c.env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(u.id),
        c.env.DB.prepare('DELETE FROM totp_secrets WHERE user_id=?').bind(u.id),
        c.env.DB.prepare('DELETE FROM webauthn_credentials WHERE user_id=?').bind(u.id),
        c.env.DB.prepare('DELETE FROM auth_challenges WHERE user_id=?').bind(u.id),
      ]);
    }
    await session(c, u.id, true);
    return c.json({ ok: true, binding_only: true });
  }
  const t = await verifiedTotp(c, u.id);
  if (!t || !data.code) fail(401, '请使用验证器、通行密钥或一次性凭证');
  await consumeTotp(c, t, data.code);
  await session(c, u.id);
  return c.json({ ok: true });
});
auth.get('/auth/me', c => c.json({ user: c.get('user'), binding_only: !!c.get('session').binding_only }));
auth.post('/auth/logout', async c => {
  await c.env.DB.prepare('DELETE FROM sessions WHERE token_hash=?').bind(c.get('session').token_hash).run();
  deleteCookie(c, cookieName, { path: '/' });
  return c.json({ ok: true });
});

async function completeBinding(c: Ctx, factorStatements: D1PreparedStatement[]) {
  const u = c.get('user');
  const limited = !!c.get('session').binding_only;
  const codes = limited ? await issueSecrets(c.env.DB, u.id, 'recovery') : null;
  const statements = [...factorStatements];
  if (limited) {
    statements.push(
      c.env.DB.prepare('DELETE FROM one_time_secrets WHERE user_id=?').bind(u.id),
      ...(codes?.statements || []),
      c.env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(u.id),
    );
  }
  await c.env.DB.batch(statements);
  if (limited) await session(c, u.id);
  return c.json({ ok: true, recovery_codes: codes?.values });
}
auth.post('/auth/totp/setup', async c => {
  admin(c);
  const existing = await c.env.DB.prepare('SELECT verified FROM totp_secrets WHERE user_id=?').bind(c.get('user').id).first<{ verified: number }>();
  if (existing?.verified) fail(409, '已有验证器，请使用通行密钥绑定或由其他管理员重置');
  const secret = base32(crypto.getRandomValues(new Uint8Array(20)));
  await c.env.DB.prepare(`INSERT INTO totp_secrets(user_id,encrypted_secret) VALUES(?,?)
    ON CONFLICT(user_id) DO UPDATE SET encrypted_secret=excluded.encrypted_secret WHERE verified=0`)
    .bind(c.get('user').id, await encrypt(c.env.APP_ENC_KEY, 'totp', secret)).run();
  return c.json({ secret, uri: `otpauth://totp/${encodeURIComponent(`家药箱:${c.get('user').username}`)}?secret=${secret}&issuer=HomeMeds` });
});
auth.post('/auth/totp/bind', async c => {
  admin(c);
  const { code } = await json(c, z.object({ code: z.string().regex(/^\d{6}$/) }));
  await limit(c.env.DB, `bind:${c.get('user').id}`, 10, 600);
  const t = await c.env.DB.prepare('SELECT * FROM totp_secrets WHERE user_id=? AND verified=0').bind(c.get('user').id)
    .first<{ encrypted_secret: string }>();
  if (!t) fail(400, '请先开始绑定');
  const step = await checkTotp(await decrypt(c.env.APP_ENC_KEY, 'totp', t.encrypted_secret), code, -1);
  if (step === null) fail(400, '验证码错误');
  return completeBinding(c, [c.env.DB.prepare('UPDATE totp_secrets SET verified=1,last_step=? WHERE user_id=?').bind(step, c.get('user').id)]);
});
auth.post('/auth/passkey/register/options', async c => {
  admin(c);
  const u = c.get('user');
  const options = await generateRegistrationOptions({
    rpName: '家药箱', rpID: new URL(c.req.url).hostname,
    userName: u.username, userID: new TextEncoder().encode(u.id),
    attestationType: 'none', excludeCredentials: (await passkeyIds(c, u.id)).map(id => ({ id })),
    supportedAlgorithmIDs: [-7, -257],
    authenticatorSelection: { residentKey: 'preferred', userVerification: 'required' },
  });
  const id = randomToken();
  await c.env.DB.prepare(`INSERT INTO auth_challenges(id,user_id,kind,challenge,session_hash,expires_at) VALUES(?,?,'register',?,?,?)`)
    .bind(id, u.id, options.challenge, c.get('session').token_hash, Date.now() + 300000).run();
  return c.json({ id, options });
});
auth.post('/auth/passkey/register', async c => {
  admin(c);
  const { id, response } = await json(c, z.object({ id: z.string(), response: z.custom<RegistrationResponseJSON>(x => !!x && typeof x === 'object') }));
  const challenge = await c.env.DB.prepare(`DELETE FROM auth_challenges WHERE id=? AND user_id=? AND session_hash=?
    AND kind='register' AND expires_at>? RETURNING challenge`).bind(id, c.get('user').id, c.get('session').token_hash, Date.now()).first<{ challenge: string }>();
  if (!challenge) fail(400, '验证已过期，请重试');
  const result = await verifyRegistrationResponse({
    response, expectedChallenge: challenge.challenge, expectedOrigin: new URL(c.req.url).origin,
    expectedRPID: new URL(c.req.url).hostname, requireUserVerification: true,
  }).catch(() => null);
  if (!result?.verified || !result.registrationInfo) fail(400, '通行密钥验证失败');
  const cred = result.registrationInfo.credential;
  return completeBinding(c, [c.env.DB.prepare(`INSERT INTO webauthn_credentials(id,user_id,public_key,counter,transports) VALUES(?,?,?,?,?)`)
    .bind(cred.id, c.get('user').id, b64(cred.publicKey), cred.counter, JSON.stringify(cred.transports || []))]);
});
auth.post('/auth/passkey/options', async c => {
  const data = await json(c, z.object({ username }));
  c.set('auditSubject', data.username);
  await limit(c.env.DB, `passkey:${c.req.header('CF-Connecting-IP') || 'local'}`, 30, 600);
  const u = await c.env.DB.prepare("SELECT * FROM users WHERE username=? AND role='admin'").bind(data.username).first<User>();
  if (!u) fail(401, '无法使用通行密钥');
  return c.json(await passkeyChallenge(c, u.id, await passkeyIds(c, u.id), null));
});
auth.post('/auth/passkey/login', async c => {
  const { id, response } = await json(c, assertion);
  // Challenges bound to a session are step-up proofs for that session and must never mint a new one.
  const ch = await c.env.DB.prepare(`DELETE FROM auth_challenges WHERE id=? AND kind='authenticate' AND session_hash IS NULL
    AND expires_at>? RETURNING user_id,challenge`).bind(id, Date.now()).first<{ user_id: string; challenge: string }>();
  if (!ch) fail(401, '验证已过期');
  await (await checkPasskey(c, ch.user_id, ch.challenge, response)).run();
  await session(c, ch.user_id);
  return c.json({ ok: true });
});
auth.get('/auth/recovery', async c => {
  admin(c);
  const id = c.get('user').id;
  const r = (await c.env.DB.prepare(`SELECT
      (SELECT COUNT(*) FROM one_time_secrets WHERE user_id=? AND kind='recovery' AND used_at IS NULL AND expires_at>?) AS remaining,
      EXISTS(SELECT 1 FROM totp_secrets WHERE user_id=? AND verified=1) AS totp,
      (SELECT COUNT(*) FROM webauthn_credentials WHERE user_id=?) AS passkeys`)
    .bind(id, Date.now(), id, id).first<{ remaining: number; totp: number; passkeys: number }>())!;
  return c.json({ remaining: r.remaining, totp: !!r.totp, passkeys: r.passkeys });
});
auth.post('/auth/recovery/passkey/options', async c => {
  admin(c);
  const u = c.get('user'), ids = await passkeyIds(c, u.id);
  if (!ids.length) fail(400, '尚未绑定通行密钥');
  return c.json(await passkeyChallenge(c, u.id, ids, c.get('session').token_hash));
});
auth.post('/auth/recovery/regenerate', async c => {
  admin(c);
  const u = c.get('user');
  const proof = await json(c, z.union([z.object({ code: z.string().regex(/^\d{6}$/) }), assertion]));
  await limit(c.env.DB, `reauth:${u.id}`, 10, 600);
  const statements: D1PreparedStatement[] = [];
  if ('code' in proof) {
    const t = await verifiedTotp(c, u.id);
    if (!t) fail(400, '尚未绑定验证器，请使用通行密钥');
    await consumeTotp(c, t, proof.code);
  } else {
    const ch = await c.env.DB.prepare(`DELETE FROM auth_challenges WHERE id=? AND user_id=? AND session_hash=?
      AND kind='authenticate' AND expires_at>? RETURNING challenge`)
      .bind(proof.id, u.id, c.get('session').token_hash, Date.now()).first<{ challenge: string }>();
    if (!ch) fail(401, '验证已过期，请重试');
    statements.push(await checkPasskey(c, u.id, ch.challenge, proof.response));
  }
  const codes = await issueSecrets(c.env.DB, u.id, 'recovery');
  await c.env.DB.batch([
    ...statements,
    c.env.DB.prepare("DELETE FROM one_time_secrets WHERE user_id=? AND kind='recovery'").bind(u.id),
    ...codes.statements,
  ]);
  return c.json({ recovery_codes: codes.values });
});
auth.post('/admin/users/:id/reset', async c => {
  admin(c);
  const id = c.req.param('id');
  if (id === c.get('user').id) fail(400, '不能重置自己');
  const target = await c.env.DB.prepare("SELECT id FROM users WHERE id=? AND role='admin'").bind(id).first();
  if (!target) fail(404, '管理员不存在');
  const issued = await issueSecrets(c.env.DB, id, 'temporary');
  await c.env.DB.batch([
    ...['sessions', 'one_time_secrets', 'webauthn_credentials', 'totp_secrets', 'auth_challenges'].map(table => c.env.DB.prepare(`DELETE FROM ${table} WHERE user_id=?`).bind(id)),
    ...issued.statements,
  ]);
  return c.json({ temporary_password: issued.values[0], valid_days: 7 });
});
