// Keys for the Android app are sealed to an RSA key held in the phone's
// Android Keystore. RSA-OAEP uses SHA-1 for both digests because Keystore's
// OAEP MGF1 digest is SHA-1 on older Android versions.
const std64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const unstd64 = (s: string) => Uint8Array.from(atob(s), c => c.charCodeAt(0));
export async function importDeviceKey(spki: string) {
  const key = await crypto.subtle.importKey('spki', unstd64(spki.replace(/\s+/g, '')), { name: 'RSA-OAEP', hash: 'SHA-1' }, false, ['encrypt']);
  if ((key.algorithm as RsaHashedKeyAlgorithm).modulusLength < 2048) throw new Error('device key too short');
  return key;
}
export interface SealedPayload { alg: 'RSA-OAEP-SHA1+A256GCM'; wrapped_key: string; iv: string; ciphertext: string }
export async function sealForDevice(deviceKey: CryptoKey, payload: unknown): Promise<SealedPayload> {
  const aes = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aes, new TextEncoder().encode(JSON.stringify(payload))));
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', aes));
  const wrapped = new Uint8Array(await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, deviceKey, raw));
  return { alg: 'RSA-OAEP-SHA1+A256GCM', wrapped_key: std64(wrapped), iv: std64(iv), ciphertext: std64(ciphertext) };
}
