import { Hono } from 'hono';
import { admin, json, type AppEnv, type Bindings } from './core';
import { downloadSettingsSchema, type DownloadSettings } from '../shared/download';

const settingsKey = 'app:download';
export async function getDownloadSettings(env: Bindings): Promise<DownloadSettings> {
  const row = await env.DB.prepare('SELECT value FROM app_settings WHERE key=?').bind(settingsKey).first<{ value: string }>();
  if (row) try {
    const parsed = downloadSettingsSchema.safeParse(JSON.parse(row.value));
    if (parsed.success) return parsed.data;
  } catch { /* A damaged or unsafe stored link disables downloads, not the admin console. */ }
  return { android_apk_url: '' };
}
export const download = new Hono<AppEnv>();
download.get('/download', async c => c.json(await getDownloadSettings(c.env)));
download.get('/admin/download', async c => {
  admin(c);
  return c.json(await getDownloadSettings(c.env));
});
download.put('/admin/download', async c => {
  admin(c);
  const settings = await json(c, downloadSettingsSchema);
  await c.env.DB.prepare(`INSERT INTO app_settings(key,value) VALUES(?,?)
    ON CONFLICT(key) DO UPDATE SET value=excluded.value`).bind(settingsKey, JSON.stringify(settings)).run();
  return c.json(settings);
});
