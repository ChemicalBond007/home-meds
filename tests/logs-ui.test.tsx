import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AdminLogs } from '../src/client/AdminLogs';
import { SettingsPage } from '../src/client/Settings';
import { useLoad } from '../src/client/components';
import type { LogPage } from '../src/shared/logs';
import type { User } from '../src/shared/types';

vi.mock('../src/client/api', () => ({ api: vi.fn(), sse: vi.fn() }));
vi.mock('../src/client/Auth', () => ({ Security: () => null }));
vi.mock('../src/client/components', async importOriginal => ({
  ...await importOriginal<typeof import('../src/client/components')>(), useLoad: vi.fn(),
}));
const admin: User = { id: 'a', username: '管理员', role: 'admin', group_id: null, is_elderly: 0 };
const result: LogPage = {
  items: [{ id: '1', created_at: '2026-10-17 06:00:00', actor_id: 'u', actor_name: '其他用户', group_id: 'other',
    group_name: '另一家庭', action: 'ai.chat', outcome: 'failure', http_status: 200, target_id: null,
    subject_name: null, detail: '<script>alert("not executable")</script>' }],
  next_cursor: 'next-page',
};
beforeEach(() => {
  vi.mocked(useLoad).mockReturnValue({ data: result, error: '', loading: false, reload: vi.fn() });
});
describe('administrator log UI', () => {
  it('only offers the usage log entry to administrators', () => {
    const props = { groups: [], members: [], warehouses: [], changed: vi.fn(), logout: vi.fn() };
    expect(renderToStaticMarkup(<SettingsPage {...props} user={admin} />)).toContain('使用日志');
    expect(renderToStaticMarkup(<SettingsPage {...props} user={{ ...admin, role: 'user' }} />)).not.toContain('使用日志');
  });
  it('renders cross-family actor and outcome details, filters and paging, escaping log content', () => {
    const html = renderToStaticMarkup(<AdminLogs groups={[]} users={[admin]} />);
    for (const text of ['其他用户', '另一家庭', 'AI 计划对话', '失败', 'HTTP 200', '所有家庭', '开始日期', '结束日期', '下一页']) {
      expect(html).toContain(text);
    }
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });
  it('provides empty, loading and retry states without showing stale records', () => {
    const render = () => renderToStaticMarkup(<AdminLogs groups={[]} users={[]} />);
    vi.mocked(useLoad).mockReturnValue({ data: { items: [], next_cursor: null }, error: '', loading: false, reload: vi.fn() });
    expect(render()).toContain('没有符合条件的记录');
    vi.mocked(useLoad).mockReturnValue({ data: undefined, error: '', loading: true, reload: vi.fn() });
    expect(render()).toContain('正在读取日志');
    vi.mocked(useLoad).mockReturnValue({ data: undefined, error: '暂时不可用', loading: false, reload: vi.fn() });
    expect(render()).toContain('重新读取日志');
    expect(render()).not.toContain('其他用户');
  });
});
