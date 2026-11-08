export let selectedGroup = localStorage.getItem('home-meds-group') || '';
export function selectGroup(id: string) { selectedGroup = id; localStorage.setItem('home-meds-group', id); }
export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}
export async function api<T>(path: string, method = 'GET', body?: unknown, options?: { state?: 'admin'; signal?: AbortSignal }): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method, credentials: 'same-origin', signal: options?.signal,
    headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(selectedGroup ? { 'X-Group-Id': selectedGroup } : {}),
      ...(options?.state ? { 'X-HomeMeds-State': options.state } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const data = await response.json() as T & { error?: string };
  if (!response.ok) throw new ApiError(data.error || '请求失败', response.status);
  return data;
}
