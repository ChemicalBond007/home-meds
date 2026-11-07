import { QRCodeSVG } from 'qrcode.react';
import { groupJoinQrValue } from '../shared/group-join';
import { Message } from './components';

export function GroupJoinQr({ name, code }: { name: string; code: string }) {
  return <div className="group-join-qr">
    <p>在 App「注册成员」中点击「扫描分组码二维码」，扫描后仍需填写用户名并确认注册。</p>
    <QRCodeSVG value={groupJoinQrValue(code)} size={280} level="M" marginSize={4}
      bgColor="#FFFFFF" fgColor="#000000" role="img" aria-label={`${name}的分组码二维码`} title={`${name}的分组码二维码`} />
    <p>也可手动输入分组码：<code className="secret">{code}</code></p>
    <Message warning>持有分组码即可注册加入该家庭，请仅向可信家人展示。更换分组码后，旧二维码立即失效。</Message>
    <p className="fine">二维码在本机生成，不请求第三方服务，也不包含网站或 API 地址。</p>
  </div>;
}
