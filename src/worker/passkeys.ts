import { admin, fail, type Ctx } from './core';
import type { AdminPasskeys } from '../shared/bootstrap';

export async function adminPasskeys(c: Ctx): Promise<AdminPasskeys[]> {
  admin(c);
  if (c.get('session').binding_only) fail(403, '请先绑定验证器或通行密钥');
  const rows = await c.env.DB.batch([
    c.env.DB.prepare(`SELECT u.id,u.username,EXISTS(
      SELECT 1 FROM totp_secrets t WHERE t.user_id=u.id AND t.verified=1) AS totp
      FROM users u WHERE u.role='admin' ORDER BY u.username,u.id`),
    c.env.DB.prepare(`SELECT c.id,c.user_id,c.transports FROM webauthn_credentials c
      JOIN users u ON u.id=c.user_id WHERE u.role='admin' ORDER BY c.user_id,c.id`),
  ]);
  const credentials = rows[1].results as { id: string; user_id: string; transports: string }[];
  return (rows[0].results as { id: string; username: string; totp: number }[]).map(u => ({
    id: u.id, username: u.username, totp: !!u.totp,
    passkeys: credentials.filter(k => k.user_id === u.id).map(k => {
      let transports: unknown;
      try { transports = JSON.parse(k.transports); } catch { transports = []; }
      return { id: k.id, transports: Array.isArray(transports) ? transports.filter((t): t is string => typeof t === 'string') : [] };
    }),
  }));
}
