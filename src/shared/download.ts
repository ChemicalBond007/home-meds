import { z } from 'zod';
import { publicUrl } from './ai-http';

export const downloadSettingsSchema = z.object({
  android_apk_url: z.string().trim().max(4096, '下载链接不能超过 4096 个字符').refine(value => {
    if (!value) return true;
    try { return publicUrl(value).protocol === 'https:'; } catch { return false; }
  }, '请输入公开的 HTTPS 下载链接，不支持内网地址或带账号密码的链接'),
});
export type DownloadSettings = z.infer<typeof downloadSettingsSchema>;
export const isDownloadPath = (path: string) => /^\/downloads?\/?$/.test(path);
