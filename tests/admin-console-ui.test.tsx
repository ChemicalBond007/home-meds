import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AdminWorkspace } from '../src/client/App';
import { AdminSettings, adminSections } from '../src/client/AdminSettings';
import { Login } from '../src/client/Auth';
import { aiSettingsSchema } from '../src/shared/ai';
import type { AdminBootstrap } from '../src/shared/bootstrap';

vi.mock('../src/client/api', () => ({ api: vi.fn(), selectGroup: vi.fn(), selectedGroup: 'g' }));
const data: AdminBootstrap = { api_version: 1, user: { id: 'a', username: '管理员', role: 'admin', group_id: null, is_elderly: 0 },
  binding_only: false, group_id: null, groups: [], users: [], members: [], warehouses: [],
  security: { remaining: 10, totp: true, passkeys: 1 },
  ai: { settings: aiSettingsSchema.parse({}), has_llm_key: true, has_tavily_key: false } };

describe('settings-only administrator interface', () => {
  it('offers settings sections including downloads without operational tabs or a log page', () => {
    expect(adminSections.map(s => s.id)).toEqual(['account', 'family', 'ai', 'download']);
    const html = renderToStaticMarkup(<AdminWorkspace data={data} busy={false} error="" logout={async () => {}}
      select={() => {}} changed={() => {}} setBusy={() => {}} />);
    expect(html).toContain('管理员设置');
    expect(html).toContain('当前有 10 个未使用的恢复码');
    for (const text of ['>使用日志<', '>今日服药<', '>药品入库<', '>对话助手<']) expect(html).not.toContain(text);
  });
  it('renders administrator-only login without member registration controls', () => {
    const html = renderToStaticMarkup(<Login adminOnly done={() => {}} />);
    expect(html).toContain('管理员登录');
    expect(html).toContain('使用通行密钥登录');
    expect(html).not.toContain('成员登录');
    expect(html).not.toContain('确认注册并加入家庭');
  });
  it('renders a no-family setup state and prefills model settings from bootstrap', () => {
    const props = { data, changed: () => {}, onBusy: () => {} };
    expect(renderToStaticMarkup(<AdminSettings {...props} section="family" />)).toContain('请先创建一个家庭');
    const ai = renderToStaticMarkup(<AdminSettings {...props} section="ai" />);
    expect(ai).toContain('qwen3.5-27b');
    expect(ai).toContain('已设置，可留空');
    expect(ai).not.toContain('正在读取配置');
  });
});
