const enc = new TextEncoder();
export const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
export const unb64 = (s: string): Uint8Array<ArrayBuffer> => Uint8Array.from(atob(s.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0));
export const randomToken = (length = 32) => b64(crypto.getRandomValues(new Uint8Array(length)));
export const hash = async (s: string) => b64(new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s))));
async function key(master: string, purpose: 'totp' | 'settings') {
  if (!master || master.length < 32) throw new Error('APP_ENC_KEY must contain at least 32 characters');
  const material = await crypto.subtle.importKey('raw', enc.encode(master), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({
    name: 'HKDF', hash: 'SHA-256', salt: enc.encode('home-meds:v1'), info: enc.encode(purpose),
  }, material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
export async function encrypt(master: string, purpose: 'totp' | 'settings', value: string) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await key(master, purpose), enc.encode(value));
  return `${b64(iv)}.${b64(new Uint8Array(encrypted))}`;
}
export async function decrypt(master: string, purpose: 'totp' | 'settings', value: string) {
  const [iv, data] = value.split('.');
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(iv) }, await key(master, purpose), unb64(data));
  return new TextDecoder().decode(plain);
}
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export function base32(bytes: Uint8Array) {
  let bits = 0, value = 0, out = '';
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) { out += alphabet[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits) out += alphabet[(value << (5 - bits)) & 31];
  return out;
}
function fromBase32(s: string) {
  let bits = 0, value = 0;
  const out: number[] = [];
  for (const c of s.replace(/=+$/, '').toUpperCase()) {
    const n = alphabet.indexOf(c);
    if (n < 0) throw new Error('Invalid base32');
    value = (value << 5) | n; bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  return new Uint8Array(out);
}
export async function totpCode(secret: string, step: number) {
  const buf = new ArrayBuffer(8);
  new DataView(buf).setBigUint64(0, BigInt(step));
  const k = await crypto.subtle.importKey('raw', fromBase32(secret), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign', 'verify']);
  const bytes = new Uint8Array(await crypto.subtle.sign('HMAC', k, buf));
  const offset = bytes[19] & 15;
  const n = ((bytes[offset] & 127) << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3];
  return (n % 1000000).toString().padStart(6, '0');
}
export async function checkTotp(secret: string, code: string, lastStep: number, now = Date.now()) {
  if (!/^\d{6}$/.test(code)) return null;
  const step = Math.floor(now / 30000);
  for (const s of [step, step - 1, step + 1]) {
    if (s > lastStep) {
      const expected = await totpCode(secret, s);
      const k = await crypto.subtle.importKey('raw', enc.encode(expected), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
      const sig = await crypto.subtle.sign('HMAC', k, enc.encode('verify'));
      const candidate = await crypto.subtle.importKey('raw', enc.encode(code), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
      if (await crypto.subtle.verify('HMAC', candidate, sig, enc.encode('verify'))) return s;
    }
  }
  return null;
}
