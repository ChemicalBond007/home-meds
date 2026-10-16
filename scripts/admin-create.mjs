import { randomBytes, createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile, unlink } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
const args = process.argv.slice(2);
const username = args.find(a => !a.startsWith('--'));
if (!username || !/^[\p{L}\p{N}_-]{2,40}$/u.test(username)) {
  console.error('Usage: npm run admin:create -- USERNAME [--remote]');
  process.exit(1);
}
const password = randomBytes(18).toString('base64url');
const hash = createHash('sha256').update(password).digest('base64url');
const userId = randomUUID();
const quote = s => `'${String(s).replaceAll("'", "''")}'`;
const sql = `INSERT INTO users(id,username,role) VALUES(${quote(userId)},${quote(username)},'admin');
INSERT INTO one_time_secrets(id,user_id,kind,secret_hash,expires_at)
VALUES(${quote(randomUUID())},${quote(userId)},'temporary',${quote(hash)},${Date.now() + 7 * 86400000});`;
await mkdir('.wrangler', { recursive: true });
const file = path.resolve('.wrangler', `admin-${randomUUID()}.local.sql`);
try {
  await writeFile(file, sql, { mode: 0o600 });
  const cli = path.resolve('node_modules/wrangler/bin/wrangler.js');
  const r = spawnSync(process.execPath, [cli, 'd1', 'execute', 'home-meds', args.includes('--remote') ? '--remote' : '--local', '--file', file], { stdio: 'inherit' });
  if (r.status !== 0) process.exitCode = r.status || 1;
  else console.log(`管理员: ${username}\n临时密码（7天有效，仅可绑定身份验证）: ${password}\n请安全转交，不要放进聊天、日志或版本库。`);
} finally { await unlink(file); }
