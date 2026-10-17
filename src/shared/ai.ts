import { z } from 'zod';

export const deepseekBaseUrl = 'https://api.deepseek.com';
export const nativeSearchApis = ['responses', 'chat_completions', 'anthropic'] as const;
const requestFields = ['messages', 'tools', 'tool_choice', 'stream', 'model', 'response_format'];
export const aiSettingsSchema = z.object({
  provider: z.enum(['custom', 'deepseek']).default('custom'),
  base_url: z.url().default('https://claw.shu.edu.cn/api'),
  model: z.string().min(1).max(100).default('qwen3.5-27b'),
  extra_body: z.record(z.string(), z.unknown()).default({ enable_thinking: false }).refine(
    x => !requestFields.some(k => k in x), '额外参数不能覆盖消息、工具或输出配置',
  ),
  search_mode: z.enum(['external', 'builtin']).default('external'),
  native_search_api: z.enum(nativeSearchApis).default('responses'),
  native_search_body: z.record(z.string(), z.unknown()).default({}).refine(
    x => ![...requestFields, 'input', 'system', 'store', 'include', 'max_tokens', 'max_output_tokens', 'max_tool_calls'].some(k => k in x)
      && JSON.stringify(x).length <= 12000, '内置搜索参数不能覆盖消息、工具、模型、隐私或调用限制，且不能超过 12KB',
  ),
  query_mode: z.enum(['tools', 'prefetch']).default('tools'),
  output_mode: z.enum(['prompt', 'json_schema']).default('prompt'),
  duckduckgo_enabled: z.boolean().default(false),
  lookup_daily_limit: z.number().int().min(1).max(1000).default(20),
  chat_daily_limit: z.number().int().min(1).max(1000).default(50),
  max_llm_calls: z.number().int().min(3).max(6).default(6),
  max_tool_calls: z.number().int().min(1).max(5).default(5),
  max_tokens: z.number().int().min(256).max(8192).default(2048),
  timeout_seconds: z.number().int().min(10).max(120).default(60),
}).transform(d => ({
  ...d,
  base_url: d.provider === 'deepseek' ? deepseekBaseUrl : d.base_url,
  native_search_api: d.provider === 'deepseek' ? 'anthropic' as const : d.native_search_api,
  duckduckgo_enabled: d.search_mode === 'builtin' ? false : d.duckduckgo_enabled,
}));
export type AISettings = z.infer<typeof aiSettingsSchema>;
export type NativeSearchApi = typeof nativeSearchApis[number];
export interface AIModel { id: string; name: string }
export interface SearchProbe {
  supported: boolean;
  search_mode: 'builtin' | 'external';
  native_search_api?: NativeSearchApi;
  sources: { title: string; url: string }[];
  message: string;
}
export function connectionId(settings: Pick<AISettings, 'provider' | 'base_url'>) {
  return `${settings.provider}:${(settings.provider === 'deepseek' ? deepseekBaseUrl : settings.base_url).replace(/\/+$/, '')}`;
}
