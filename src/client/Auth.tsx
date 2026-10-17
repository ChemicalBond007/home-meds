import { useState } from 'react';
import { startAuthentication, startRegistration } from '@simplewebauthn/browser';
import type { PublicKeyCredentialRequestOptionsJSON, PublicKeyCredentialCreationOptionsJSON } from '@simplewebauthn/browser';
import { api } from './api';
import { Message, errorText, Icon, useLoad } from './components';
import { TotpSetup } from './TotpSetup';
export function Login({ done }: { done: () => void }) {
  const [mode, setMode] = useState<'login' | 'register' | 'admin'>('login');
  const [name, setName] = useState(''), [secret, setSecret] = useState(''), [code, setCode] = useState(''), [join, setJoin] = useState('');
  const [elderly, setElderly] = useState(false), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true); setError('');
    try {
      await api(mode === 'register' ? '/auth/register' : '/auth/login', 'POST', {
        username: name, ...(mode === 'register' ? { join_code: join, is_elderly: elderly } : { secret: secret || undefined, code: code || undefined }),
      });
      done();
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  return <main className="login">
    <section className="login-intro"><div className="brand"><img src="/icon.svg" alt="" />家药箱</div>
      <p className="eyebrow">HOME MEDICINE, TOGETHER</p><h1>药放得安心，<br />家人也放心。</h1>
      <p>一起整理家里的药，<br />记住每一次服药。</p><div className="login-art"><Icon name="box" /><span>一份清单，一家人的照顾</span></div>
    </section>
    <section className="login-form"><h2>{mode === 'register' ? '加入你的家庭' : '欢迎回家'}</h2>
      <p className="muted">{mode === 'admin' ? '使用通行密钥、验证器或一次性凭证。' : '普通成员只需用户名登录，请勿在公共设备使用。'}</p>
      <div className="segmented">{(['login', 'register', 'admin'] as const).map(m => <button type="button" className={mode === m ? 'active' : ''} key={m} onClick={() => { setMode(m); setError(''); }}>{m === 'login' ? '成员登录' : m === 'register' ? '注册' : '管理员'}</button>)}</div>
      <form onSubmit={e => { e.preventDefault(); void submit(); }}>
        <label>用户名<input value={name} onChange={e => setName(e.target.value)} autoComplete="username" minLength={2} maxLength={40} required /></label>
        {mode === 'register' && <><label>分组码<input value={join} onChange={e => setJoin(e.target.value)} required autoComplete="off" /></label>
          <label className="check"><input type="checkbox" checked={elderly} onChange={e => setElderly(e.target.checked)} />我是老人，使用大字简洁界面</label></>}
        {mode === 'admin' && <><label>验证器 6 位验证码<input value={code} onChange={e => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" maxLength={6} /></label>
          <details><summary>使用临时密码或恢复码</summary><label>一次性凭证<input type="password" value={secret} onChange={e => setSecret(e.target.value)} autoComplete="current-password" /></label></details></>}
        {error && <Message warning>{error}</Message>}
        <button className="full" disabled={busy} type="submit">{busy ? '正在登录…' : mode === 'register' ? '确认注册并加入家庭' : '进入家药箱'}</button>
        {mode === 'admin' && <button disabled={busy || !name} className="secondary full" type="button" onClick={() => {
          setBusy(true); setError('');
          api<{ id: string; options: PublicKeyCredentialRequestOptionsJSON }>('/auth/passkey/options', 'POST', { username: name })
            .then(async d => api('/auth/passkey/login', 'POST', { id: d.id, response: await startAuthentication({ optionsJSON: d.options }) }))
            .then(done).catch(e => setError(errorText(e))).finally(() => setBusy(false));
        }}>使用通行密钥登录</button>}
      </form><p className="fine">仅帮助记录和管理，不代替医生或药师的建议。</p>
    </section>
  </main>;
}
function RecoveryList({ codes, note, action, done }: { codes: string[]; note: string; action: string; done: () => void }) {
  return <><Message warning>{note}</Message>
    <ol className="recovery">{codes.map(c => <li key={c}><code>{c}</code></li>)}</ol>
    <button onClick={done}>{action}</button></>;
}
export function Security({ done, bindingOnly = false }: { done: () => void; bindingOnly?: boolean }) {
  const [secret, setSecret] = useState(''), [uri, setUri] = useState(''), [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[]>(), [error, setError] = useState(''), [busy, setBusy] = useState(false), [success, setSuccess] = useState('');
  const [factors, setFactors] = useState(0);
  const bind = async (work: () => Promise<{ recovery_codes?: string[] }>) => {
    setBusy(true); setError('');
    try {
      const r = await work();
      setSecret(''); setUri(''); setCode('');
      if (r.recovery_codes) setCodes(r.recovery_codes);
      else { setSuccess('绑定成功'); setFactors(n => n + 1); }
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  return <><section className="panel"><h2>{bindingOnly ? '请先保护管理员账号' : '管理员安全设置'}</h2>
    <p>绑定通行密钥或验证器后才能管理。恢复码只显示一次，请安全离线保存。</p>
    {error && <Message warning>{error}</Message>}{success && <Message>{success}</Message>}
    {codes ? <RecoveryList codes={codes} note="临时密码和旧恢复码已失效。下面 10 个恢复码每个只能用一次。" action="我已安全保存，继续" done={done} /> : <>
      <button disabled={busy} onClick={() => void bind(async () => {
        const d = await api<{ id: string; options: PublicKeyCredentialCreationOptionsJSON }>('/auth/passkey/register/options', 'POST', {});
        return api('/auth/passkey/register', 'POST', { id: d.id, response: await startRegistration({ optionsJSON: d.options }) });
      })}>绑定通行密钥</button>
      <p>也可以使用身份验证器应用。</p>
      {!secret ? <button className="secondary" disabled={busy} onClick={() => {
        setBusy(true); setError(''); setSuccess('');
        api<{ secret: string; uri: string }>('/auth/totp/setup', 'POST', {}).then(d => { setSecret(d.secret); setUri(d.uri); }).catch(e => setError(errorText(e))).finally(() => setBusy(false));
      }}>开始绑定验证器</button> : <form onSubmit={e => { e.preventDefault(); void bind(() => api('/auth/totp/bind', 'POST', { code })); }}>
        <TotpSetup secret={secret} uri={uri} />
        <label>验证器当前 6 位验证码<input inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e => setCode(e.target.value)} pattern="[0-9]{6}" maxLength={6} required /></label>
        <div className="actions"><button disabled={busy}>验证并完成绑定</button>
          <button type="button" className="secondary" disabled={busy} onClick={() => { setSecret(''); setUri(''); setCode(''); setError(''); }}>取消绑定</button></div>
      </form>}
    </>}
  </section>{!bindingOnly && <RecoveryCodes version={factors} />}</>;
}
function RecoveryCodes({ version }: { version: number }) {
  const status = useLoad<{ remaining: number; totp: boolean; passkeys: number }>('/auth/recovery', version);
  const [code, setCode] = useState(''), [codes, setCodes] = useState<string[]>(), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const regenerate = async (proof: () => Promise<object>) => {
    setBusy(true); setError('');
    try {
      const r = await api<{ recovery_codes: string[] }>('/auth/recovery/regenerate', 'POST', await proof());
      setCode(''); setCodes(r.recovery_codes);
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  };
  const s = status.data;
  return <section className="panel"><h2>恢复码</h2>
    {codes ? <RecoveryList codes={codes} note="旧恢复码已全部失效。下面 10 个新恢复码每个只能用一次，只显示这一次，请安全离线保存。" action="我已安全保存"
      done={() => { setCodes(undefined); status.reload(); }} />
      : !s ? <Message warning={!!status.error}>{status.error || '正在读取恢复码状态…'}</Message> : <>
        <p>当前有 {s.remaining} 个未使用的恢复码。恢复码丢失或可能被他人看到时，可以重新生成一组，旧恢复码会立即全部失效。生成前需要再次验证身份。</p>
        {error && <Message warning>{error}</Message>}
        {s.passkeys > 0 && <button disabled={busy} onClick={() => void regenerate(async () => {
          const d = await api<{ id: string; options: PublicKeyCredentialRequestOptionsJSON }>('/auth/recovery/passkey/options', 'POST', {});
          return { id: d.id, response: await startAuthentication({ optionsJSON: d.options }) };
        })}>用通行密钥验证并重新生成</button>}
        {s.totp && <form onSubmit={e => { e.preventDefault(); void regenerate(async () => ({ code })); }}>
          <label>验证器当前 6 位验证码<input inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e => setCode(e.target.value)} pattern="[0-9]{6}" maxLength={6} required /></label>
          <button className={s.passkeys > 0 ? 'secondary' : ''} disabled={busy}>验证并重新生成</button>
        </form>}
      </>}
  </section>;
}
