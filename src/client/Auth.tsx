import { useEffect, useState } from 'react';
import { startAuthentication, startRegistration } from '@simplewebauthn/browser';
import type { PublicKeyCredentialRequestOptionsJSON, PublicKeyCredentialCreationOptionsJSON } from '@simplewebauthn/browser';
import { api } from './api';
import { Message, errorText, Icon, useLoad } from './components';
import { TotpSetup } from './TotpSetup';
import type { AdminPasskeys, SecurityStatus } from '../shared/bootstrap';
import { PasskeyManagement } from './PasskeyManagement';
export function Login({ done }: { done: () => void }) {
  const [name, setName] = useState(''), [secret, setSecret] = useState(''), [code, setCode] = useState('');
  const [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true); setError('');
    try {
      await api('/auth/admin/login', 'POST', {
        username: name, secret: secret || undefined, code: code || undefined,
      });
      done();
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  return <main className="login">
    <section className="login-intro"><div className="brand"><img src="/icon.svg" alt="" />赛博药箱</div>
      <p className="eyebrow">ADMINISTRATION CONSOLE</p><h1>配置有序，<br />照顾有据。</h1>
      <p>管理员设置专用。<br />日常用药请使用手机应用。</p><div className="login-art"><Icon name="settings" /><span>安全 · 家庭 · 模型</span></div>
    </section>
    <section className="login-form"><h2>管理员登录</h2>
      <p className="muted">使用通行密钥、验证器或一次性凭证。</p>
      <form onSubmit={e => { e.preventDefault(); void submit(); }}>
        <label>用户名<input value={name} onChange={e => setName(e.target.value)} autoComplete="username" minLength={2} maxLength={40} required /></label>
        <label>验证器 6 位验证码<input value={code} onChange={e => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} /></label>
        <details><summary>使用临时密码或恢复码</summary><label>一次性凭证<input type="password" value={secret} onChange={e => setSecret(e.target.value)} autoComplete="current-password" /></label></details>
        {error && <Message warning>{error}</Message>}
        <button className="full" disabled={busy} type="submit">{busy ? '正在登录…' : '进入管理台'}</button>
        <button disabled={busy || !name} className="secondary full" type="button" onClick={() => {
          setBusy(true); setError('');
          api<{ id: string; options: PublicKeyCredentialRequestOptionsJSON }>('/auth/passkey/options', 'POST', { username: name })
            .then(async d => api('/auth/passkey/login', 'POST', { id: d.id, response: await startAuthentication({ optionsJSON: d.options }) }))
            .then(done).catch(e => setError(errorText(e))).finally(() => setBusy(false));
        }}>使用通行密钥登录</button>
      </form><p className="fine">仅帮助记录和管理，不代替医生或药师的建议。</p>
    </section>
  </main>;
}
function RecoveryList({ codes, note, action, done }: { codes: string[]; note: string; action: string; done: () => void }) {
  return <><Message warning>{note}</Message>
    <ol className="recovery">{codes.map(c => <li key={c}><code>{c}</code></li>)}</ol>
    <button onClick={done}>{action}</button></>;
}
export function Security({ done, bindingOnly = false, initialStatus, onBusy, passkeyAdmins, currentUserId }: {
  done: () => void; bindingOnly?: boolean; initialStatus?: SecurityStatus; onBusy?: (value: boolean) => void;
  passkeyAdmins?: AdminPasskeys[]; currentUserId?: string;
}) {
  const [secret, setSecret] = useState(''), [uri, setUri] = useState(''), [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[]>(), [error, setError] = useState(''), [busy, setBusy] = useState(false), [success, setSuccess] = useState('');
  const [factors, setFactors] = useState(0);
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [passkeyBusy, setPasskeyBusy] = useState(false), [passkeyCount, setPasskeyCount] = useState<number>();
  const locked = busy || recoveryBusy || passkeyBusy;
  useEffect(() => {
    onBusy?.(locked || !!codes || !!secret);
    return () => onBusy?.(false);
  }, [locked, codes, secret, onBusy]);
  const bind = async (work: () => Promise<{ recovery_codes?: string[] }>) => {
    setBusy(true); setError('');
    try {
      const r = await work();
      setSecret(''); setUri(''); setCode('');
      if (r.recovery_codes) setCodes(r.recovery_codes);
      else { setSuccess('绑定成功'); setPasskeyCount(undefined); setFactors(n => n + 1); }
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  return <><section className="panel"><h2>{bindingOnly ? '请先保护管理员账号' : '管理员安全设置'}</h2>
    <p>绑定通行密钥或验证器后才能管理。恢复码只显示一次，请安全离线保存。</p>
    {error && <Message warning>{error}</Message>}{success && <Message>{success}</Message>}
    {codes ? <RecoveryList codes={codes} note="临时密码和旧恢复码已失效。下面 10 个恢复码每个只能用一次。" action="我已安全保存，继续" done={done} /> : <>
      <button disabled={locked} onClick={() => void bind(async () => {
        const d = await api<{ id: string; options: PublicKeyCredentialCreationOptionsJSON }>('/auth/passkey/register/options', 'POST', {});
        return api('/auth/passkey/register', 'POST', { id: d.id, response: await startRegistration({ optionsJSON: d.options }) });
      })}>绑定通行密钥</button>
      <p>也可以使用身份验证器应用。</p>
      {!secret ? <button className="secondary" disabled={locked} onClick={() => {
        setBusy(true); setError(''); setSuccess('');
        api<{ secret: string; uri: string }>('/auth/totp/setup', 'POST', {}).then(d => { setSecret(d.secret); setUri(d.uri); }).catch(e => setError(errorText(e))).finally(() => setBusy(false));
      }}>开始绑定验证器</button> : <form onSubmit={e => { e.preventDefault(); void bind(() => api('/auth/totp/bind', 'POST', { code })); }}>
        <TotpSetup secret={secret} uri={uri} />
        <label>验证器当前 6 位验证码<input inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e => setCode(e.target.value)} pattern="[0-9]{6}" maxLength={6} required /></label>
        <div className="actions"><button disabled={locked}>验证并完成绑定</button>
          <button type="button" className="secondary" disabled={locked} onClick={() => { setSecret(''); setUri(''); setCode(''); setError(''); }}>取消绑定</button></div>
      </form>}
    </>}
  </section>{!bindingOnly && <><RecoveryCodes version={factors} initial={initialStatus} onBusy={setRecoveryBusy}
    disabled={busy || passkeyBusy || !!codes || !!secret} passkeyCount={passkeyCount} />
    {currentUserId && <PasskeyManagement currentUserId={currentUserId} initial={passkeyAdmins} version={factors}
      disabled={busy || recoveryBusy || !!codes || !!secret} onBusy={setPasskeyBusy} onCount={setPasskeyCount} />}</>}</>;
}
function RecoveryCodes({ version, initial, onBusy, disabled = false, passkeyCount }: {
  version: number; initial?: SecurityStatus; onBusy?: (value: boolean) => void; disabled?: boolean; passkeyCount?: number;
}) {
  const status = useLoad<SecurityStatus>('/auth/recovery', version, initial);
  const [code, setCode] = useState(''), [codes, setCodes] = useState<string[]>(), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  useEffect(() => { onBusy?.(busy || !!codes); return () => onBusy?.(false); }, [busy, codes, onBusy]);
  const regenerate = async (proof: () => Promise<object>) => {
    setBusy(true); setError('');
    try {
      const r = await api<{ recovery_codes: string[] }>('/auth/recovery/regenerate', 'POST', await proof());
      setCode(''); setCodes(r.recovery_codes);
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  const s = status.data;
  const hasPasskeys = (passkeyCount ?? s?.passkeys ?? 0) > 0;
  return <section className="panel"><h2>恢复码</h2>
    {codes ? <RecoveryList codes={codes} note="旧恢复码已全部失效。下面 10 个新恢复码每个只能用一次，只显示这一次，请安全离线保存。" action="我已安全保存"
      done={() => { setCodes(undefined); status.reload(); }} />
      : !s ? <Message warning={!!status.error}>{status.error || '正在读取恢复码状态…'}</Message> : <>
        <p>当前有 {s.remaining} 个未使用的恢复码。恢复码丢失或可能被他人看到时，可以重新生成一组，旧恢复码会立即全部失效。生成前需要再次验证身份。</p>
        {error && <Message warning>{error}</Message>}
        {hasPasskeys && <button disabled={busy || disabled} onClick={() => void regenerate(async () => {
          const d = await api<{ id: string; options: PublicKeyCredentialRequestOptionsJSON }>('/auth/recovery/passkey/options', 'POST', {});
          return { id: d.id, response: await startAuthentication({ optionsJSON: d.options }) };
        })}>用通行密钥验证并重新生成</button>}
        {s.totp && <form onSubmit={e => { e.preventDefault(); void regenerate(async () => ({ code })); }}>
          <label>验证器当前 6 位验证码<input inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e => setCode(e.target.value)} pattern="[0-9]{6}" maxLength={6} required /></label>
          <button className={hasPasskeys ? 'secondary' : ''} disabled={busy || disabled}>验证并重新生成</button>
        </form>}
      </>}
  </section>;
}
