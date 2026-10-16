import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [
    cloudflareTest(async () => ({
      wrangler: { configPath: './wrangler.jsonc' },
      miniflare: {
        bindings: {
          TEST_MIGRATIONS: await readD1Migrations('./migrations'),
          APP_ENC_KEY: 'only-for-isolated-test-runtime-key',
          VAPID_PRIVATE_KEY: '',
        },
      },
    })),
  ],
  test: { setupFiles: ['./tests/setup.ts'] },
});
