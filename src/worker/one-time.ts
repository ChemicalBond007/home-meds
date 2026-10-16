import { hash, randomToken } from './crypto';

export async function issueSecrets(db: D1Database, userId: string, kind: 'temporary' | 'recovery') {
  const values = Array.from({ length: kind === 'recovery' ? 10 : 1 }, () => randomToken(18));
  const expires = Date.now() + (kind === 'temporary' ? 7 : 3650) * 86400000;
  const statements = await Promise.all(values.map(async value => db.prepare(
    'INSERT INTO one_time_secrets(id,user_id,kind,secret_hash,expires_at) VALUES(?,?,?,?,?)',
  ).bind(crypto.randomUUID(), userId, kind, await hash(value), expires)));
  return { values, statements };
}
export async function consumeSecret(db: D1Database, userId: string, secret: string) {
  return db.prepare(`UPDATE one_time_secrets SET used_at = CASE WHEN kind='recovery' THEN ? ELSE used_at END
    WHERE user_id = ? AND secret_hash = ? AND used_at IS NULL AND expires_at > ?
    RETURNING kind`).bind(Date.now(), userId, await hash(secret), Date.now()).first<{ kind: 'temporary' | 'recovery' }>();
}
