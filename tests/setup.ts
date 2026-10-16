/// <reference types="@cloudflare/vitest-plugin/types" />
import { beforeEach } from 'vitest';
import { env } from 'cloudflare:workers';
import { applyD1Migrations, reset, type D1Migration } from 'cloudflare:test';
declare namespace Cloudflare {
  interface Env { TEST_MIGRATIONS: D1Migration[] }
}
beforeEach(async () => {
  await reset();
  const e = env as Env & { TEST_MIGRATIONS: D1Migration[] };
  await applyD1Migrations(e.DB, e.TEST_MIGRATIONS);
});
