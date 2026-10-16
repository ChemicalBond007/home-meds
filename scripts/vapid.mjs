import { webcrypto } from 'node:crypto';
import { writeFile, mkdir } from 'node:fs/promises';
const pair = await webcrypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const jwk = await webcrypto.subtle.exportKey('jwk', pair.privateKey);
const raw = await webcrypto.subtle.exportKey('raw', pair.publicKey);
await mkdir('.wrangler', { recursive: true });
await writeFile('.wrangler/vapid-private.secret', jwk.d + '\n', { mode: 0o600, flag: 'wx' });
console.log(`VAPID_PUBLIC_KEY=${Buffer.from(raw).toString('base64url')}\n私钥已保存到忽略目录 .wrangler/vapid-private.secret。请通过安全 stdin 上传为 VAPID_PRIVATE_KEY，勿提交该文件。`);
