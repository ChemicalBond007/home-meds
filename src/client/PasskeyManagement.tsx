import { useEffect, useState } from 'react';
import { api } from './api';
import { Confirm, Message, useLoad } from './components';
import type { AdminPasskeys } from '../shared/bootstrap';

const transportNames: Record<string, string> = {
  internal: '本机验证器', hybrid: '跨设备验证', usb: 'USB', nfc: 'NFC', ble: '蓝牙', 'smart-card': '智能卡',
};
export function PasskeyManagement({ currentUserId, initial, version, disabled = false, onBusy, onCount }: {
  currentUserId: string; initial?: AdminPasskeys[]; version: number; disabled?: boolean;
  onBusy: (value: boolean) => void; onCount: (count: number) => void;
}) {
  const list = useLoad<AdminPasskeys[]>('/admin/passkeys', version, initial);
  const [updated, setUpdated] = useState<AdminPasskeys[]>();
  const data = updated ?? list.data;
  const [selected, setSelected] = useState<{ admin: AdminPasskeys; id: string }>();
  const [busy, setBusy] = useState(false), [message, setMessage] = useState('');
  useEffect(() => { onBusy(busy); return () => onBusy(false); }, [busy, onBusy]);
  useEffect(() => { setUpdated(undefined); }, [version, initial]);
  useEffect(() => {
    const own = data?.find(u => u.id === currentUserId);
    if (own) onCount(own.passkeys.length);
  }, [data, currentUserId, onCount]);
  return <section className="panel"><div className="between"><h2>通行密钥管理</h2>
    <button className="secondary" disabled={disabled || busy || list.loading} onClick={() => { setMessage(''); setUpdated(undefined); list.reload(); }}>刷新列表</button></div>
    <p className="fine">列出所有管理员在本站登记的通行密钥。连接方式不代表设备名称，本站无法判断密钥当前是否在你身边或仍保存在设备中。</p>
    {message && <Message>{message}</Message>}
    {list.error && <Message warning>{list.error}</Message>}
    {list.loading && <p role="status">正在读取通行密钥…</p>}
    {data?.map(u => <section className="passkey-admin" key={u.id} aria-label={`${u.username}的通行密钥`}>
      <h3>{u.username}{u.id === currentUserId ? '（本人）' : ''} · {u.passkeys.length} 把通行密钥</h3>
      {!u.passkeys.length && <p className="muted">尚未绑定通行密钥。</p>}
      {u.passkeys.map((key, index) => <div className="passkey-row" key={key.id}><div>
        <strong>通行密钥 {index + 1}</strong><p className="fine">{key.transports.map(t => transportNames[t] || t).join(' / ') || '未记录连接方式'}</p>
        <code className="passkey-id">{key.id}</code></div>
        <button className="secondary" aria-label={`删除 ${u.username} 的通行密钥 ${index + 1}`} disabled={disabled || busy || list.loading}
          onClick={() => { setMessage(''); setSelected({ admin: u, id: key.id }); }}>删除</button></div>)}
    </section>)}
    {selected && <Confirm title={`删除 ${selected.admin.username} 的通行密钥？`} close={() => setSelected(undefined)} action={async () => {
      setBusy(true);
      try {
        const result = await api<{ ok: boolean; passkey_admins?: AdminPasskeys[]; state_error?: string }>(
          `/admin/users/${encodeURIComponent(selected.admin.id)}/passkeys`, 'DELETE', { credential_id: selected.id });
        setMessage(result.state_error || '通行密钥已删除。');
        if (result.passkey_admins) setUpdated(result.passkey_admins);
        else { setUpdated(undefined); list.reload(); }
      } finally { setBusy(false); }
    }}>
      <p>密钥标识：<code className="passkey-id">{selected.id}</code></p>
      <Message warning>删除后，该密钥不能再用于本站登录，无法撤销。不会删除验证器、恢复码或现有登录会话，也不会从设备或密码管理器中移除密钥副本。</Message>
      {selected.admin.passkeys.length === 1 && <Message warning>这是该管理员最后一把通行密钥。{selected.admin.totp
        ? '删除后仍可使用已绑定的验证器登录。' : '未绑定验证器，之后需使用恢复码，或请其他管理员重置身份验证。请先确认能够恢复访问。'}</Message>}
    </Confirm>}
  </section>;
}
