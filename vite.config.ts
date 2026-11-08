import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://127.0.0.1:8787' },
    watch: { ignored: ['**/android-v2/**', '**/platform-tools/**', '**/.qa/**', '**/.wrangler/**'] },
  },
  // Older Android 10 WebViews do not understand range queries such as (width<=800px).
  // Preserve compatible mobile breakpoints instead of silently dropping the phone layout.
  build: { sourcemap: false, cssTarget: 'chrome87' },
});
