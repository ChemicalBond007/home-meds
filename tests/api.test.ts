import { beforeEach, describe, it, expect } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { b64, encrypt, hash, randomToken, totpCode, unb64 } from '../src/worker/crypto';
import { localClock } from '../src/shared/schedule';
import { issueSecrets, consumeSecret } from '../src/worker/one-time';

const date = () => localClock(new Date(), 'Asia/Shanghai').date;
async function call(path: string, method = 'GET', body?: object, token = 'test-session', extra: Record<string, string> = {}) {
  return app.request(`https://home.test/api${path}`, {
    method, headers: { Origin: 'https://home.test', Cookie: `hm_session=${token}`, 'Content-Type': 'application/json', ...extra },
    body: body ? JSON.stringify(body) : undefined,
  }, env);
}
beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO groups(id,name,join_code) VALUES('g','家庭','join'),('other','另一家','other')"),
    env.DB.prepare("INSERT INTO users(id,username,role,group_id) VALUES('u','家人','user','g'),('a','管理员','admin','g'),('x','他人','user','other')"),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('test-session'), 'u', Date.now() + 86400000),
    env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('test-admin'), 'a', Date.now() + 86400000),
    env.DB.prepare("INSERT INTO products(id,group_id,name,base_unit,units_per_package,created_by) VALUES('med','g','药品','片',10,'u')"),
    env.DB.prepare("INSERT INTO warehouses(id,group_id,name) VALUES('w','g','抽屉')"),
    env.DB.prepare("INSERT INTO stock_items(id,group_id,product_id,warehouse_id,units_per_package,units_remaining) VALUES('box','g','med','w',10,10)"),
    env.DB.prepare(`INSERT INTO medication_plans(id,group_id,user_id,product_id,medicine_name,amount,unit,times,frequency,start_date,created_by,updated_by)
      VALUES('p','g','u','med','药品',1,'片','["08:00","20:00"]','daily','2020-01-01','u','u')`),
  ]);
});
const dose = (overrides: object = {}) => ({
  plan_id: 'p', local_date: date(), scheduled_time: '08:00', status: 'taken', amount: 2,
  taken_at: new Date().toISOString(), stock_item_id: 'box', ...overrides,
});
// Software authenticator for home.test: ES256 key stored as a COSE EC2 key, assertions flagged user-present and user-verified.
async function passkey(userId: string) {
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']) as CryptoKeyPair;
  const { x, y } = await crypto.subtle.exportKey('jwk', keys.publicKey) as JsonWebKey;
  const cose = new Uint8Array([0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20, ...unb64(x!), 0x22, 0x58, 0x20, ...unb64(y!)]);
  const id = randomToken(16);
  await env.DB.prepare('INSERT INTO webauthn_credentials(id,user_id,public_key,counter) VALUES(?,?,?,0)').bind(id, userId, b64(cose)).run();
  const sha = async (bytes: BufferSource) => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const derInt = (half: Uint8Array) => {
    let i = 0;
    while (i < half.length - 1 && half[i] === 0) i++;
    const v = [...half.slice(i)];
    return v[0] & 0x80 ? [0x02, v.length + 1, 0, ...v] : [0x02, v.length, ...v];
  };
  let counter = 0;
  return {
    id,
    async sign(challenge: string) {
      const clientData = new TextEncoder().encode(JSON.stringify({ type: 'webauthn.get', challenge, origin: 'https://home.test' }));
      const authData = new Uint8Array([...await sha(new TextEncoder().encode('home.test')), 0x05, 0, 0, 0, ++counter]);
      const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, keys.privateKey, new Uint8Array([...authData, ...await sha(clientData)])));
      const der = [...derInt(raw.slice(0, 32)), ...derInt(raw.slice(32))];
      return { id, rawId: id, type: 'public-key', clientExtensionResults: {}, response: {
        clientDataJSON: b64(clientData), authenticatorData: b64(authData), signature: b64(new Uint8Array([0x30, der.length, ...der])),
      } };
    },
  };
}
describe('permissions and credentials', () => {
  it('requires login and same-origin mutations', async () => {
    expect((await call('/stock', 'GET', undefined, 'invalid')).status).toBe(401);
    expect((await call('/doses', 'POST', dose(), 'test-session', { Origin: 'https://evil.test' })).status).toBe(403);
    expect((await call('/members/u', 'PATCH', { is_elderly: true, group_id: 'g' })).status).toBe(403);
  });
  it('does not trust a regular user group header', async () => {
    const r = await call('/members', 'GET', undefined, 'test-session', { 'X-Group-Id': 'other' });
    const body = await r.json() as { id: string }[];
    expect(body.map(x => x.id)).not.toContain('x');
  });
  it('limits bootstrap sessions to factor binding', async () => {
    await env.DB.prepare('UPDATE sessions SET binding_only=1 WHERE user_id=?').bind('a').run();
    expect((await call('/stock', 'GET', undefined, 'test-admin')).status).toBe(403);
    expect((await call('/auth/me', 'GET', undefined, 'test-admin')).status).toBe(200);
  });
  it('keeps temporary passwords until binding but consumes recovery codes once', async () => {
    const t = await issueSecrets(env.DB, 'a', 'temporary');
    await env.DB.batch(t.statements);
    expect((await consumeSecret(env.DB, 'a', t.values[0]))?.kind).toBe('temporary');
    expect((await consumeSecret(env.DB, 'a', t.values[0]))?.kind).toBe('temporary');
    const r = await issueSecrets(env.DB, 'a', 'recovery');
    await env.DB.batch(r.statements);
    expect(await consumeSecret(env.DB, 'a', r.values[0])).not.toBeNull();
    expect(await consumeSecret(env.DB, 'a', r.values[0])).toBeNull();
  });
  it('enrolls TOTP from a restricted session, rotates secrets, and prevents replay', async () => {
    await env.DB.prepare('UPDATE sessions SET binding_only=1 WHERE user_id=?').bind('a').run();
    const temporary = await issueSecrets(env.DB, 'a', 'temporary');
    await env.DB.batch(temporary.statements);
    const setup = await call('/auth/totp/setup', 'POST', {}, 'test-admin');
    expect(setup.status).toBe(200);
    const { secret } = await setup.json() as { secret: string };
    const code = await totpCode(secret, Math.floor(Date.now() / 30000));
    const bind = await call('/auth/totp/bind', 'POST', { code }, 'test-admin');
    expect(bind.status).toBe(200);
    const result = await bind.json() as { recovery_codes: string[] };
    expect(result.recovery_codes).toHaveLength(10);
    expect(await consumeSecret(env.DB, 'a', temporary.values[0])).toBeNull();
    expect((await call('/auth/me', 'GET', undefined, 'test-admin')).status).toBe(401);
    expect((await call('/auth/login', 'POST', { username: '管理员', code }, 'unused')).status).toBe(401);
    const recovered = await call('/auth/login', 'POST', { username: '管理员', secret: result.recovery_codes[0] }, 'unused');
    expect(recovered.status).toBe(200);
    expect((await call('/auth/login', 'POST', { username: '管理员', secret: result.recovery_codes[0] }, 'unused')).status).toBe(401);
  });
  it('validates group member and base units on plan creation', async () => {
    const draft = { user_id: 'u', medicine_name: '药', product_id: 'med', amount: 1, unit: '毫升', times: ['08:00'], frequency: 'daily', start_date: date() };
    expect((await call('/plans', 'POST', draft)).status).toBe(400);
    expect((await call('/plans', 'POST', { ...draft, unit: '片', user_id: 'x' })).status).toBe(400);
  });
});
describe('recovery code regeneration', () => {
  const secret = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';
  const totp = (offset = 0) => totpCode(secret, Math.floor(Date.now() / 30000) + offset);
  const regenerate = (body: object, token = 'test-admin') => call('/auth/recovery/regenerate', 'POST', body, token);
  async function enrollTotp() {
    const old = await issueSecrets(env.DB, 'a', 'recovery');
    await env.DB.batch([
      env.DB.prepare('INSERT INTO totp_secrets(user_id,encrypted_secret,verified) VALUES(?,?,1)').bind('a', await encrypt(env.APP_ENC_KEY, 'totp', secret)),
      ...old.statements,
    ]);
    return old.values;
  }
  it('requires a fresh TOTP code, replaces every old code and keeps sessions', async () => {
    const old = await enrollTotp();
    expect(await (await call('/auth/recovery', 'GET', undefined, 'test-admin')).json()).toEqual({ remaining: 10, totp: true, passkeys: 0 });
    const code = await totp();
    const r = await regenerate({ code });
    expect(r.status).toBe(200);
    const { recovery_codes: fresh } = await r.json() as { recovery_codes: string[] };
    expect(new Set(fresh).size).toBe(10);
    expect((await regenerate({ code })).status).toBe(401);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM one_time_secrets WHERE user_id='a'").first('n')).toBe(10);
    expect((await call('/auth/me', 'GET', undefined, 'test-admin')).status).toBe(200);
    expect((await call('/auth/login', 'POST', { username: '管理员', code: await totp(1) }, 'unused')).status).toBe(200);
    expect((await call('/auth/login', 'POST', { username: '管理员', secret: old[0] }, 'unused')).status).toBe(401);
    expect((await call('/auth/login', 'POST', { username: '管理员', secret: fresh[0] }, 'unused')).status).toBe(200);
  });
  it('rejects members, restricted sessions, missing factors and wrong codes without touching codes', async () => {
    expect((await call('/auth/recovery', 'GET')).status).toBe(403);
    expect((await regenerate({ code: await totp() }, 'test-session')).status).toBe(403);
    expect((await regenerate({ code: await totp() })).status).toBe(400);
    expect((await call('/auth/recovery/passkey/options', 'POST', {}, 'test-admin')).status).toBe(400);
    const old = await enrollTotp();
    const valid = await Promise.all([-1, 0, 1].map(offset => totp(offset)));
    expect((await regenerate({ code: ['000000', '111111', '222222'].find(x => !valid.includes(x))! })).status).toBe(401);
    await env.DB.prepare('UPDATE sessions SET binding_only=1 WHERE user_id=?').bind('a').run();
    expect((await regenerate({ code: await totp() })).status).toBe(403);
    expect(await consumeSecret(env.DB, 'a', old[0])).not.toBeNull();
  });
  it('accepts a passkey only through a challenge bound to the current session', async () => {
    type Challenge = { id: string; options: { challenge: string; allowCredentials: { id: string }[] } };
    const key = await passkey('a');
    const old = await issueSecrets(env.DB, 'a', 'recovery');
    await env.DB.batch([
      ...old.statements,
      env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash('second-admin'), 'a', Date.now() + 86400000),
    ]);
    const loginChallenge = async () => await (await call('/auth/passkey/options', 'POST', { username: '管理员' }, 'unused')).json() as Challenge;
    const stepUp = await (await call('/auth/recovery/passkey/options', 'POST', {}, 'test-admin')).json() as Challenge;
    expect(stepUp.options.allowCredentials.map(x => x.id)).toEqual([key.id]);
    expect((await call('/auth/passkey/login', 'POST', { id: stepUp.id, response: await key.sign(stepUp.options.challenge) }, 'unused')).status).toBe(401);
    const login = await loginChallenge();
    expect((await regenerate({ id: login.id, response: await key.sign(login.options.challenge) })).status).toBe(401);
    expect((await regenerate({ id: stepUp.id, response: await key.sign(stepUp.options.challenge) }, 'second-admin')).status).toBe(401);
    const response = await key.sign(stepUp.options.challenge);
    const r = await regenerate({ id: stepUp.id, response });
    expect(r.status).toBe(200);
    expect((await r.json() as { recovery_codes: string[] }).recovery_codes).toHaveLength(10);
    expect((await regenerate({ id: stepUp.id, response })).status).toBe(401);
    expect(await consumeSecret(env.DB, 'a', old.values[0])).toBeNull();
    const next = await loginChallenge();
    expect((await call('/auth/passkey/login', 'POST', { id: next.id, response: await key.sign(next.options.challenge) }, 'unused')).status).toBe(200);
  });
});
describe('atomic inventory and doses', () => {
  it('deducts once, records actor, and restores only once', async () => {
    const r = await call('/doses', 'POST', dose());
    expect(r.status).toBe(201);
    const { id } = await r.json() as { id: string };
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(8);
    expect((await call('/doses', 'POST', dose())).status).toBe(409);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM stock_movements WHERE type='dose'").first('n')).toBe(1);
    expect((await call(`/doses/${id}/undo`, 'POST', {})).status).toBe(200);
    expect((await call(`/doses/${id}/undo`, 'POST', {})).status).toBe(409);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(10);
    expect((await call('/doses', 'POST', dose())).status).toBe(201);
  });
  it('rolls back insufficient-stock attempts without history', async () => {
    expect((await call('/doses', 'POST', dose({ amount: 11 }))).status).toBe(409);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM dose_logs').first('n')).toBe(0);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM stock_movements').first('n')).toBe(0);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(10);
  });
  it('handles concurrent scheduled doses without negative stock', async () => {
    const results = await Promise.all([
      call('/doses', 'POST', dose({ amount: 7 })),
      call('/doses', 'POST', dose({ amount: 7, scheduled_time: '20:00' })),
    ]);
    expect(results.map(r => r.status).sort()).toEqual([201, 409]);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(3);
  });
  it('skips and takes without inventory, and rejects historical dates', async () => {
    expect((await call('/doses', 'POST', dose({ status: 'skipped' }))).status).toBe(201);
    expect((await call('/doses', 'POST', dose({ scheduled_time: '20:00', stock_item_id: null }))).status).toBe(201);
    expect(await env.DB.prepare("SELECT units_remaining FROM stock_items WHERE id='box'").first('units_remaining')).toBe(10);
    expect((await call('/doses', 'POST', dose({ local_date: '2000-01-01' }))).status).toBe(400);
  });
  it('requires a second confirmation for expired boxes', async () => {
    await env.DB.prepare("UPDATE stock_items SET expiry_date='2000-01-01'").run();
    expect((await call('/doses', 'POST', dose())).status).toBe(400);
    expect((await call('/doses', 'POST', dose({ confirm_expired: true }))).status).toBe(201);
  });
  it('rejects duplicate trace-code stock-in', async () => {
    const body = { product_id: 'med', warehouse_id: 'w', code: '81234567890123456789' };
    expect((await call('/stock', 'POST', body)).status).toBe(201);
    expect((await call('/stock', 'POST', body)).status).toBe(409);
  });
  it('records stock-out atomically and rejects insufficient stock', async () => {
    expect((await call('/stock/box/move', 'POST', { type: 'out', amount: 11 })).status).toBe(409);
    expect(await env.DB.prepare('SELECT COUNT(*) AS n FROM stock_movements').first('n')).toBe(0);
    expect((await call('/stock/box/move', 'POST', { type: 'out', amount: 10 })).status).toBe(200);
    expect(await env.DB.prepare("SELECT status FROM stock_items WHERE id='box'").first('status')).toBe('empty');
  });
});
