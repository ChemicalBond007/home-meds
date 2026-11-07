import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { groupJoinQrValue } from '../src/shared/group-join';
import { GroupJoinQr } from '../src/client/GroupJoinQr';
import { AdminSettings } from '../src/client/AdminSettings';
import type { AdminBootstrap } from '../src/shared/bootstrap';
import fixture from './fixtures/group-join-qr.json';

vi.mock('../src/client/api', () => ({ api: vi.fn() }));
describe('family registration QR contract', () => {
  it('encodes the shared Android fixture without a URL, preserving code case and punctuation', () => {
    expect(groupJoinQrValue(' DEMO-LOCAL-ONLY ')).toBe(JSON.stringify(fixture));
    expect(JSON.parse(groupJoinQrValue(' AbC_-09 '))).toEqual({ type: 'home-meds.join', version: 1, code: 'AbC_-09' });
    expect(groupJoinQrValue(fixture.code)).not.toMatch(/https?:|base_url|server/);
  });
  it('bounds codes and refuses empty payloads instead of generating unusable QR codes', () => {
    for (const value of ['', '   ', 'a'.repeat(101)]) expect(() => groupJoinQrValue(value)).toThrow();
    expect(JSON.parse(groupJoinQrValue('a'.repeat(100))).code).toHaveLength(100);
  });
  it('renders a local accessible QR with manual code and trusted-family warning', () => {
    const html = renderToStaticMarkup(<GroupJoinQr name="测试家庭" code={fixture.code} />);
    expect(html).toContain('<svg');
    expect(html).toContain('测试家庭的分组码二维码');
    expect(html).toContain(fixture.code);
    expect(html).toContain('旧二维码立即失效');
    expect(html).toContain('不包含网站或 API 地址');
    expect(html).not.toContain('<img');
  });
  it('offers QR display for each administered family without adding member registration to Pages', () => {
    const data: AdminBootstrap = { api_version: 1, user: { id: 'a', username: '管理员', role: 'admin', group_id: null, is_elderly: 0 },
      binding_only: false, group_id: 'g', groups: [{ id: 'g', name: '测试家庭', timezone: 'Asia/Shanghai', meal_times: '{}', join_code: fixture.code }],
      users: [], members: [], warehouses: [] };
    const html = renderToStaticMarkup(<AdminSettings section="family" data={data} changed={() => {}} onBusy={() => {}} />);
    expect(html).toContain('显示分组码二维码');
    expect(html).not.toContain('注册成员');
  });
});
