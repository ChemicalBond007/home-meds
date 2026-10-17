import { QRCodeSVG } from 'qrcode.react';
import { Message } from './components';
import './TotpSetup.css';

export function TotpSetup({ secret, uri }: { secret: string; uri: string }) {
  return <div className="totp-setup">
    <p>用 Microsoft Authenticator、Google Authenticator 等验证器扫描下方二维码。</p>
    <p className="fine">Microsoft Authenticator：点「＋ → 添加帐户 → 其他帐户 → 扫描二维码」，不要选择「工作或学校帐户」。</p>
    <QRCodeSVG className="totp-qr" value={uri} size={280} level="M" marginSize={4}
      bgColor="#FFFFFF" fgColor="#000000" role="img" aria-label="验证器绑定二维码" title="验证器绑定二维码" />
    <Message warning>二维码和密钥等同于登录凭证，请勿向他人展示或分享。扫码后，还须输入下方 6 位验证码才会完成绑定。</Message>
    <details><summary>无法扫码？手动输入密钥</summary>
      <code className="secret">{secret}</code>
      <p className="fine">账号服务：HomeMeds；类型：基于时间（TOTP），SHA-1，6 位数字，每 30 秒更新。</p>
    </details>
    <p><a href={uri}>在支持的验证器中打开（本机）</a></p>
  </div>;
}
