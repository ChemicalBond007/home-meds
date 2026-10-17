import { afterEach, describe, expect, it, vi } from 'vitest';
import { Children, isValidElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { QRCodeSVG } from 'qrcode.react';
import { TotpSetup } from '../src/client/TotpSetup';
import { base32 } from '../src/worker/crypto';

vi.mock('../src/client/api', () => ({ api: vi.fn() }));
// Generate an ephemeral test key instead of storing a credential-like fixture.
const secret = base32(crypto.getRandomValues(new Uint8Array(20)));
const uri = (name: string) => `otpauth://totp/${encodeURIComponent(`HomeMeds:${name}`)}?secret=${secret}&issuer=HomeMeds&algorithm=SHA1&digits=6&period=30`;
afterEach(() => vi.restoreAllMocks());
describe('authenticator enrolment QR', () => {
  it('encodes the exact provisioning URI locally as a high-contrast SVG with a quiet zone', () => {
    const fetch = vi.spyOn(globalThis, 'fetch'), value = uri('测试管理员');
    const view = TotpSetup({ secret, uri: value });
    const qr = Children.toArray(view.props.children).find(child => isValidElement(child) && child.type === QRCodeSVG);
    expect(isValidElement(qr) && qr.props).toMatchObject({
      value, size: 280, level: 'M', marginSize: 4, bgColor: '#FFFFFF', fgColor: '#000000', role: 'img',
    });
    const html = renderToStaticMarkup(view);
    expect(html).toContain('<svg');
    expect(html).toContain('<path');
    expect(html).toContain('aria-label="验证器绑定二维码"');
    expect(html).not.toMatch(/<(img|image|script)\b/);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('keeps manual and same-device setup available, explains Microsoft account type and requires code confirmation', () => {
    const html = renderToStaticMarkup(<TotpSetup secret={secret} uri={uri('测试管理员')} />);
    for (const text of ['Microsoft Authenticator', '其他帐户', '工作或学校帐户', '无法扫码？手动输入密钥',
      secret, '在支持的验证器中打开', '6 位验证码', '请勿向他人展示或分享']) expect(html).toContain(text);
    expect(html).toContain('<details>');
    expect(html).toContain('href="otpauth://totp/');
  });
  it('renders the longest allowed Unicode account label without losing the QR or leaking it to an external image URL', () => {
    const html = renderToStaticMarkup(<TotpSetup secret={secret} uri={uri('药'.repeat(40))} />);
    expect(html).toContain('<svg');
    expect(html).not.toMatch(/(?:href|src)="https?:\/\//);
  });
});
