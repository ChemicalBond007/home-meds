interface WorkerSecrets {
  APP_ENC_KEY: string;
  VAPID_PRIVATE_KEY: string;
}
interface Env extends WorkerSecrets {}
declare namespace Cloudflare {
  interface Env extends WorkerSecrets {}
}
