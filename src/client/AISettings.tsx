import { useEffect, useRef, useState, type ReactNode } from 'react';
import { aiSettingsSchema, connectionId, deepseekBaseUrl, type AIModel, type AISettings as AIConfig, type SearchProbe } from '../shared/ai';
import { api } from './api';
import { Message, errorText, useLoad } from './components';

export function AISettings({ children }: { children?: ReactNode }) {
  const load = useLoad<{ settings: AIConfig; has_llm_key: boolean; has_tavily_key: boolean }>('/ai/settings');
  const [d, setD] = useState<AIConfig>(), [extra, setExtra] = useState<string>(), [nativeExtra, setNativeExtra] = useState<string>();
  const [llmKey, setLlmKey] = useState(''), [searchKey, setSearchKey] = useState('');
  const [message, setMessage] = useState(''), [error, setError] = useState(''), [busy, setBusy] = useState(false), [probe, setProbe] = useState<unknown>();
  const [models, setModels] = useState<AIModel[]>([]), [modelLoading, setModelLoading] = useState(false), [modelError, setModelError] = useState('');
  const [searchProbe, setSearchProbe] = useState<SearchProbe>();
  const config = d || load.data?.settings, provider = config?.provider || 'custom', base = config?.base_url || '';
  const modelVersion = useRef(0), alive = useRef(true), current = useRef('');
  const signature = JSON.stringify([config, extra, nativeExtra, llmKey]);
  current.current = signature;
  const canUseSavedKey = !!(config && load.data?.has_llm_key && connectionId(config) === connectionId(load.data.settings));
  const keyReady = !!llmKey.trim() || canUseSavedKey;
  useEffect(() => { alive.current = true; return () => { alive.current = false; modelVersion.current++; }; }, []);
  const refreshModels = async (snapshot = config, version = ++modelVersion.current) => {
    if (!snapshot || version !== modelVersion.current) return;
    setModelLoading(true); setModelError('');
    try {
      const result = await api<{ models: AIModel[] }>('/ai/models', 'POST', {
        settings: { ...snapshot, model: snapshot.model || 'model-discovery' }, llm_key: llmKey || undefined,
      });
      if (!alive.current || version !== modelVersion.current) return;
      setModels(result.models);
      if (!result.models.length) setModelError('提供商未返回可用模型，请确认密钥权限。');
      else if (snapshot.provider === 'deepseek') setD(prev => {
        const value = prev || snapshot;
        return { ...value, model: result.models.some(m => m.id === value.model) ? value.model : result.models[0].id };
      });
    } catch (e) { if (alive.current && version === modelVersion.current) setModelError(errorText(e)); }
    finally { if (alive.current && version === modelVersion.current) setModelLoading(false); }
  };
  useEffect(() => {
    const version = ++modelVersion.current;
    setModels([]); setModelError(''); setModelLoading(false);
    if (!config || provider !== 'deepseek' || !keyReady) return;
    const timer = setTimeout(() => void refreshModels(config, version), 800);
    return () => { clearTimeout(timer); modelVersion.current++; };
  }, [provider, base, llmKey, canUseSavedKey]);
  useEffect(() => { setSearchProbe(undefined); }, [provider, base, config?.model, llmKey]);
  if (!config) return <Message warning={!!load.error}>{load.error || '正在读取配置…'}</Message>;
  const patch = (p: Partial<AIConfig>) => setD(prev => ({ ...(prev || config), ...p }));
  const builtin = config.search_mode === 'builtin';
  const settings = () => aiSettingsSchema.parse({ ...config,
    extra_body: extra === undefined ? config.extra_body : JSON.parse(extra),
    native_search_body: nativeExtra === undefined ? config.native_search_body : JSON.parse(nativeExtra),
  });
  const switchProvider = (value: AIConfig['provider']) => {
    setLlmKey(''); setExtra(undefined); setNativeExtra(undefined); setProbe(undefined); setMessage(''); setError('');
    patch({ provider: value, base_url: value === 'deepseek' ? deepseekBaseUrl : load.data?.settings.provider === 'custom' ? load.data.settings.base_url : '',
      model: '', extra_body: {}, native_search_body: {}, search_mode: value === 'deepseek' ? 'builtin' : 'external',
      native_search_api: value === 'deepseek' ? 'anthropic' : 'responses', duckduckgo_enabled: false });
  };
  return <section className="panel"><h2>AI 与搜索配置</h2><p>正式密钥只会加密保存到 D1，不会回传。留空仅能沿用同一提供商、同一地址的已有密钥。</p>
    <form onSubmit={e => {
      e.preventDefault(); setBusy(true); setError(''); setMessage('');
      (async () => {
        await api('/ai/settings', 'PUT', { settings: settings(), llm_key: llmKey || undefined, tavily_key: builtin ? undefined : searchKey || undefined });
        setLlmKey(''); setSearchKey(''); setD(undefined); setExtra(undefined); setNativeExtra(undefined); setMessage('配置已保存'); load.reload();
      })().catch(e => setError(errorText(e))).finally(() => setBusy(false));
    }}>
      <label>LLM 提供商<select value={provider} disabled={busy} onChange={e => switchProvider(e.target.value as AIConfig['provider'])}>
        <option value="deepseek">DeepSeek（官方）</option><option value="custom">其他 LLM（OpenAI 兼容）</option>
      </select></label>
      {provider === 'deepseek' ? <p className="fine">使用 DeepSeek 官方接口，无需填写 Base URL。输入 API 密钥后自动获取模型列表；不同模型的内置搜索能力可能不同。</p>
        : <label>OpenAI 兼容接口根地址<input type="url" value={config.base_url} required onChange={e => patch({ base_url: e.target.value })} /></label>}
      <label>LLM API 密钥（{canUseSavedKey ? '已设置，可留空' : '请输入此提供商的密钥'}）<input type="password" value={llmKey} onChange={e => setLlmKey(e.target.value)} autoComplete="new-password" /></label>
      {provider === 'deepseek' ? <label>可用模型<select value={config.model} required disabled={modelLoading} onChange={e => patch({ model: e.target.value })}>
        <option value="">请先输入密钥获取模型</option>
        {config.model && !models.some(m => m.id === config.model) && <option value={config.model}>{config.model}（当前配置，尚未刷新验证）</option>}
        {models.map(m => <option value={m.id} key={m.id}>{m.name === m.id ? m.id : `${m.name} · ${m.id}`}</option>)}
      </select></label> : <label>模型<input value={config.model} list="available-llm-models" required maxLength={100} onChange={e => patch({ model: e.target.value })} />
        <datalist id="available-llm-models">{models.map(m => <option value={m.id} key={m.id}>{m.name}</option>)}</datalist></label>}
      <button type="button" className="secondary" disabled={busy || modelLoading || !keyReady || !base} onClick={() => void refreshModels()}>
        {modelLoading ? '正在获取模型…' : '获取 / 刷新可用模型'}</button>
      {modelError && <Message warning>{modelError}</Message>}
      <label>搜索来源<select value={config.search_mode} onChange={e => { setSearchProbe(undefined); patch({ search_mode: e.target.value as AIConfig['search_mode'], duckduckgo_enabled: false }); }}>
        <option value="external">外部搜索 API（Tavily）</option><option value="builtin">提供商内置搜索（自动停用外部搜索 API）</option>
      </select></label>
      <Message warning={!builtin}>{builtin ? '内置搜索已启用：Tavily、DuckDuckGo 和外部预检索均停用，不需要 Tavily 密钥。未返回来源时会报错，不会悄悄切回外部搜索。'
        : '当前使用 Tavily 获取可核对的药品来源。若提供商支持内置搜索，可手动切换或运行下面的检测。'}</Message>
      <button type="button" className="secondary" disabled={busy || !keyReady || !config.model || !base} onClick={() => {
        const before = signature; setBusy(true); setError(''); setSearchProbe(undefined); setMessage('');
        (async () => {
          const result = await api<SearchProbe>('/ai/search-probe', 'POST', { settings: settings(), llm_key: llmKey || undefined });
          if (!alive.current) return;
          if (current.current !== before) { setError('提供商、模型或参数已变化，已忽略旧检测结果，请重新检测。'); return; }
          patch({ search_mode: result.search_mode, native_search_api: result.native_search_api || config.native_search_api, duckduckgo_enabled: false });
          setSearchProbe(result); setMessage(result.message);
        })().catch(e => { if (alive.current) setError(errorText(e)); }).finally(() => { if (alive.current) setBusy(false); });
      }}>{busy ? '正在处理…' : '检测内置搜索并自动选择搜索来源'}</button>
      <p className="fine">检测使用公开测试问题，不发送药箱资料，最多发起 3 次模型请求，会消耗提供商配额。只有返回接口检索来源才判定可用；网络、鉴权或限流错误不会改动搜索方式。检测后的选择须保存才生效。</p>
      {searchProbe?.sources.length ? <div><p>检测返回的来源（仍需人工核对）：</p><ul>{searchProbe.sources.map(s => <li key={s.url}><a href={s.url} target="_blank" rel="noreferrer">{s.title}</a></li>)}</ul></div> : null}
      <label>Tavily 密钥（{builtin ? '已自动停用，无需填写' : load.data?.has_tavily_key ? '已设置，可留空' : '药品查询必需'}）<input type="password" disabled={builtin} value={searchKey} onChange={e => setSearchKey(e.target.value)} autoComplete="new-password" /></label>
      <label>额外请求参数（JSON）<textarea value={extra ?? JSON.stringify(config.extra_body, null, 2)} onChange={e => { setExtra(e.target.value); setSearchProbe(undefined); }} spellCheck={false} /></label>
      <details><summary>内置搜索接口与参数（按供应商文档设置）</summary>
        {provider === 'deepseek' ? <p>DeepSeek 官方使用兼容 Anthropic 的服务端搜索接口；模型是否可用以检测结果为准。</p>
          : <label>内置搜索接口<select value={config.native_search_api} onChange={e => { setSearchProbe(undefined); patch({ native_search_api: e.target.value as AIConfig['native_search_api'] }); }}>
            <option value="responses">Responses · web_search</option><option value="chat_completions">Chat Completions · 内置联网参数</option><option value="anthropic">Anthropic · 服务端 web_search</option>
          </select></label>}
        <label>内置搜索专用参数（JSON）<textarea value={nativeExtra ?? JSON.stringify(config.native_search_body, null, 2)} spellCheck={false}
          onChange={e => { setNativeExtra(e.target.value); setSearchProbe(undefined); }} /></label>
        <p className="fine">仅发送给检索请求，不发送给私人计划对话。Chat Completions 留空默认带 enable_search=true；填写专用 JSON 后不再添加默认开关，须按供应商文档指定参数，例如 web_search_options。网关必须返回结构化 citations、annotations 或 search_results；只在正文声称联网或列出链接不足以通过检测。</p>
      </details>
      <div className="form-row"><label>外部查询模式<select disabled={builtin} value={config.query_mode} onChange={e => patch({ query_mode: e.target.value as AIConfig['query_mode'] })}><option value="tools">工具调用（默认）</option><option value="prefetch">预检索</option></select></label>
        <label>输出格式<select value={config.output_mode} onChange={e => patch({ output_mode: e.target.value as AIConfig['output_mode'] })}><option value="prompt">提示词约束（默认）</option><option value="json_schema">json_schema（可选）</option></select></label></div>
      <label className="check"><input type="checkbox" disabled={builtin} checked={!builtin && config.duckduckgo_enabled} onChange={e => patch({ duckduckgo_enabled: e.target.checked })} />启用 DuckDuckGo（内置搜索模式自动关闭）</label>
      <div className="form-row">{(['lookup_daily_limit', 'chat_daily_limit', 'max_llm_calls', 'max_tool_calls', 'max_tokens', 'timeout_seconds'] as const).map(key => <label key={key}>{({
        lookup_daily_limit: '每群每天药品查询次数', chat_daily_limit: '每人每天对话轮数', max_llm_calls: '查询最多 LLM 轮数（3–6）',
        max_tool_calls: '查询最多工具次数（1–5）', max_tokens: '最大输出 tokens', timeout_seconds: 'LLM 超时（秒）',
      })[key]}<input type="number" value={config[key]} min="1" onChange={e => patch({ [key]: Number(e.target.value) })} required /></label>)}</div>
      {error && <Message warning>{error}</Message>}{message && <Message>{message}</Message>}
      <div className="actions"><button disabled={busy || !config.model}>保存 AI 配置</button><button type="button" className="secondary" disabled={busy} onClick={() => {
        setBusy(true); setError(''); setProbe(undefined);
        api('/ai/probe', 'POST', {}).then(setProbe).catch(e => setError(errorText(e))).finally(() => setBusy(false));
      }}>检测接口能力（使用已保存配置）</button></div>
    </form>
    {probe !== undefined && <pre className="probe-result">{JSON.stringify(probe, null, 2)}</pre>}
    {children}
  </section>;
}
