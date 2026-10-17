import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AISettings } from '../src/client/AISettings';
import { useLoad } from '../src/client/components';
import { aiSettingsSchema } from '../src/shared/ai';

vi.mock('../src/client/api', () => ({ api: vi.fn() }));
vi.mock('../src/client/components', async importOriginal => ({
  ...await importOriginal<typeof import('../src/client/components')>(), useLoad: vi.fn(),
}));
function render(value: object) {
  vi.mocked(useLoad).mockReturnValue({ data: { settings: aiSettingsSchema.parse(value), has_llm_key: true, has_tavily_key: false },
    error: '', loading: false, reload: vi.fn() });
  return renderToStaticMarkup(<AISettings />);
}
describe('provider search settings UI', () => {
  it('hides DeepSeek base URL entry and shows model selection and automatic external-search disabling', () => {
    const html = render({ provider: 'deepseek', search_mode: 'builtin' });
    expect(html).not.toContain('type="url"'); expect(html).toContain('可用模型');
    expect(html).toContain('无需填写 Base URL'); expect(html).toContain('自动获取模型列表');
    expect(html).toMatch(/type="password" disabled=""/);
    expect(html).toMatch(/type="checkbox" disabled=""/);
    expect(html).toContain('不会悄悄切回外部搜索');
  });
  it('provides a configurable other-provider URL, manual search choice and a real capability probe', () => {
    const html = render({});
    expect(html).toContain('OpenAI 兼容接口根地址'); expect(html).toContain('type="url"');
    expect(html).toContain('检测内置搜索并自动选择搜索来源');
    expect(html).toContain('Responses · web_search'); expect(html).toContain('Chat Completions');
    expect(html).toContain('Tavily 获取可核对'); expect(html).not.toMatch(/type="password" disabled=""/);
  });
});
