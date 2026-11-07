import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { PasskeyManagement } from '../src/client/PasskeyManagement';
import { Security } from '../src/client/Auth';
import type { AdminPasskeys } from '../src/shared/bootstrap';

vi.mock('../src/client/api', () => ({ api: vi.fn() }));
const admins: AdminPasskeys[] = [
  { id: 'a', username: '本人管理员', totp: false, passkeys: [{ id: 'own-credential', transports: ['internal', 'hybrid'] }] },
  { id: 'b', username: '其他管理员', totp: true, passkeys: [{ id: 'other-credential', transports: ['usb'] }] },
  { id: 'c', username: '空密钥管理员', totp: false, passkeys: [] },
];
describe('administrator passkey management UI', () => {
  it('renders own and other administrators, distinguishable keys, delete controls and empty states from initial data', () => {
    const html = renderToStaticMarkup(<PasskeyManagement currentUserId="a" version={0} initial={admins} onBusy={() => {}} onCount={() => {}} />);
    for (const value of ['本人管理员（本人）', '其他管理员', 'own-credential', 'other-credential', '本机验证器', '跨设备验证', 'USB',
      '删除 本人管理员 的通行密钥 1', '删除 其他管理员 的通行密钥 1', '尚未绑定通行密钥', '无法判断密钥当前是否'])
      expect(html).toContain(value);
    expect(html).not.toContain('正在读取通行密钥');
  });
  it('disables changes while other security operations are active', () => {
    const html = renderToStaticMarkup(<PasskeyManagement currentUserId="a" version={0} initial={admins} disabled onBusy={() => {}} onCount={() => {}} />);
    expect(html.match(/disabled=""/g)).toHaveLength(3);
  });
  it('keeps management hidden from restricted binding flows and exposes it on full web security settings', () => {
    const props = { done: () => {}, currentUserId: 'a', passkeyAdmins: admins };
    expect(renderToStaticMarkup(<Security {...props} bindingOnly />)).not.toContain('通行密钥管理');
    expect(renderToStaticMarkup(<Security {...props} />)).toContain('通行密钥管理');
  });
});
