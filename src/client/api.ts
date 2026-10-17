export let selectedGroup = localStorage.getItem('home-meds-group') || '';
export function selectGroup(id: string) { selectedGroup = id; localStorage.setItem('home-meds-group', id); }
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method, credentials: 'same-origin',
    headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(selectedGroup ? { 'X-Group-Id': selectedGroup } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const data = await response.json() as T & { error?: string };
  if (!response.ok) throw new Error(data.error || '请求失败');
  return data;
}
export async function sse(path: string, body: unknown, onEvent: (event: string, data: unknown) => void, signal?: AbortSignal) {
  const response = await fetch(`/api${path}`, {
    method: 'POST', credentials: 'same-origin', signal,
    headers: { 'Content-Type': 'application/json', 'X-Group-Id': selectedGroup }, body: JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json() as { error?: string };
    throw new Error(data.error || '请求失败');
  }
  if (!response.body) throw new Error('浏览器不支持流式响应');
  const reader = response.body.getReader(), decoder = new TextDecoder();
  let buffer = '', complete = false;
  const process = (block: string) => {
    let event = 'message';
    const parts: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) parts.push(line.slice(5).trimStart());
    }
    if (parts.length) {
      const data: unknown = JSON.parse(parts.join('\n'));
      if (event === 'error') throw new Error(String(data));
      if (event === 'done') complete = true;
      onEvent(event, data);
    }
  };
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replaceAll('\r\n', '\n');
      let end;
      while ((end = buffer.indexOf('\n\n')) >= 0) { process(buffer.slice(0, end)); buffer = buffer.slice(end + 2); }
    }
    buffer += decoder.decode();
    if (buffer.trim()) process(buffer);
    if (!complete) throw new Error('连接已中断，请检查结果后再试');
  } finally { await reader.cancel().catch(() => {}); }
}
