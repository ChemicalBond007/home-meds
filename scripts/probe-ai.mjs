const base = (process.env.LLM_BASE_URL || 'https://claw.shu.edu.cn/api').replace(/\/$/, '');
const key = process.env.LLM_API_KEY;
const model = process.env.LLM_MODEL || 'qwen3.5-27b';
if (!key) { console.error('请在 .dev.vars 设置 LLM_API_KEY，然后运行 npm run probe:ai'); process.exit(1); }
async function request(body, route = '/chat/completions') {
  const r = await fetch(base + route, {
    method: route === '/models' ? 'GET' : 'POST', redirect: 'error',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: route === '/models' ? undefined : JSON.stringify({ model, enable_thinking: false, max_tokens: 1024, ...body }),
    signal: AbortSignal.timeout(60000),
  });
  if (!r.ok) { await r.body?.cancel(); throw new Error(`HTTP ${r.status}`); }
  return r.json();
}
let failed = false;
async function probe(name, task) {
  const start = performance.now();
  try { const result = await task(); console.log(JSON.stringify({ test: name, milliseconds: Math.round(performance.now() - start), result })); }
  catch (e) { failed = true; console.log(JSON.stringify({ test: name, error: e.message })); }
}
await probe('models', async () => (await request({}, '/models')).data?.map(x => x.id));
await probe('prompt_json', async () => (await request({ messages: [{ role: 'user', content: '只输出一个JSON对象，字段 name,manufacturer,indications，未知值为null。没有资料，不要根据记忆补充。' }] })).choices?.[0]?.message?.content);
await probe('tool_roundtrip', async () => {
  const messages = [{ role: 'user', content: '调用 echo 工具，再回复工具给出的值。' }];
  const r = await request({ messages, tools: [{ type: 'function', function: { name: 'echo', description: '测试', parameters: { type: 'object', properties: {}, additionalProperties: false } } }] });
  const m = r.choices?.[0]?.message;
  if (!m?.tool_calls?.length) throw new Error('Missing tool_calls');
  messages.push(m, ...m.tool_calls.map(t => ({ role: 'tool', tool_call_id: t.id, content: '{"value":"echo-ok"}' })));
  return (await request({ messages })).choices?.[0]?.message?.content;
});
await probe('json_schema_optional', async () => (await request({
  messages: [{ role: 'user', content: '返回name为null的对象' }],
  response_format: { type: 'json_schema', json_schema: { name: 'probe', strict: true, schema: { type: 'object', properties: { name: { type: 'null' } }, required: ['name'], additionalProperties: false } } },
})).choices?.[0]?.message?.content);
if (process.env.TAVILY_API_KEY) await probe('tavily', async () => {
  const r = await fetch('https://api.tavily.com/search', {
    method: 'POST', headers: { Authorization: `Bearer ${process.env.TAVILY_API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: '国家药监局 药品说明书', max_results: 3 }), signal: AbortSignal.timeout(10000),
  });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return { results: (await r.json()).results?.length };
});
else console.log('SKIP tavily: TAVILY_API_KEY 未配置');
process.exitCode = failed ? 1 : 0;
