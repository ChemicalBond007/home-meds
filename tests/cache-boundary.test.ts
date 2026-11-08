import { beforeEach, describe, expect, it } from 'vitest';
import { env } from 'cloudflare:workers';
import { app } from '../src/worker';
import { hash } from '../src/worker/crypto';

beforeEach(async () => {
  for (const id of ['source', 'attacker', 'reader']) {
    await env.DB.prepare('INSERT INTO groups(id,name,join_code) VALUES(?,?,?)').bind(id, id, id).run();
    await env.DB.prepare('INSERT INTO users(id,username,role,group_id) VALUES(?,?,?,?)')
      .bind(id, id, id === 'source' ? 'admin' : 'user', id).run();
    await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)')
      .bind(await hash(id), id, Date.now() + 600000).run();
  }
});
const request = (user: string, path: string, body?: object) => app.request(`https://home.test/api${path}`, {
  method: body ? 'POST' : 'GET',
  headers: { Origin: 'https://home.test', Cookie: `hm_session=${user}`, 'Content-Type': 'application/json' },
  body: body ? JSON.stringify(body) : undefined,
}, env);
const product = (code: string, name: string, status = 'confirmed') => ({
  generic_name: null, manufacturer: null, specification: null, indications: null, precautions: null,
  name, code, base_unit: '片', units_per_package: 10, sources: [], status,
});
const resolve = async (code: string) => (await (await request('reader', `/codes/resolve?code=${code}`)).json()) as {
  product: unknown; box: unknown; cache: Record<string, unknown> | null;
};
describe('shared catalog security boundary regressions', () => {
  it('shares administrator confirmed metadata but blocks ordinary member cache poisoning', async () => {
    const code = '6901234567892';
    expect((await request('source', '/products', product(code, 'original'))).status).toBe(201);
    expect((await resolve(code)).cache?.name).toBe('original');
    expect((await request('attacker', '/admin/bootstrap')).status).toBe(403);
    expect((await request('attacker', '/products', product(code, 'replacement'))).status).toBe(201);
    expect((await resolve(code)).cache?.name).toBe('original');
    expect((await resolve(code)).product).toBeNull();
    expect(await (await request('reader', '/products')).json()).toEqual([]);
    expect((await request('source', '/products', product('6901234567893', 'pending', 'pending'))).status).toBe(201);
    expect((await resolve('6901234567893')).cache).toBeNull();
  });
  it('never stores or returns box trace codes or private fields, including legacy cache rows', async () => {
    const original = '81234567890123456789', otherBox = '81234560000000000001';
    expect((await request('source', '/products', product(original, 'public'))).status).toBe(201);
    const stored = await env.DB.prepare("SELECT data FROM catalog_cache WHERE code='8123456'").first<string>('data');
    expect(JSON.parse(stored!)).toEqual({ name: 'public', generic_name: null, manufacturer: null, specification: null,
      indications: null, precautions: null, base_unit: '片', units_per_package: 10, sources: [] });
    await env.DB.prepare("UPDATE catalog_cache SET data=? WHERE code='8123456'")
      .bind(JSON.stringify({ ...JSON.parse(stored!), code: original, status: 'confirmed', batch_number: 'private', expiry_date: '2027-01-01' })).run();
    const result = await resolve(otherBox);
    expect(result.product).toBeNull(); expect(result.box).toBeNull();
    expect(result.cache).toEqual(JSON.parse(stored!));
    expect(JSON.stringify(result)).not.toContain(original);
  });
});
