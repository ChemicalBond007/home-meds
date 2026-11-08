import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, selectGroup } from '../src/client/api';

vi.hoisted(() => vi.stubGlobal('localStorage', { getItem: () => '', setItem: () => {} }));
beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: () => '', setItem: () => {} });
  selectGroup('');
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('administrator web requests', () => {
  it('uses browser credentials without the retired Android bridge or unnecessary headers', async () => {
    const postMessage = vi.fn();
    vi.stubGlobal('window', { HomeMedsNative: { postMessage } });
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json({ ok: true }));
    expect(await api('/admin/bootstrap')).toEqual({ ok: true });
    expect(fetch).toHaveBeenCalledWith('/api/admin/bootstrap', {
      method: 'GET', credentials: 'same-origin', signal: undefined, headers: {}, body: undefined,
    });
    expect(postMessage).not.toHaveBeenCalled();
  });
  it('preserves selected-family writes and consumes the returned state without an extra request', async () => {
    selectGroup('family');
    const state = { group_id: 'family' };
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json({ ok: true, admin_state: state }));
    expect(await api('/groups/family', 'PUT', { name: '测试家庭' }, { state: 'admin' }))
      .toEqual({ ok: true, admin_state: state });
    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[0][1]).toMatchObject({
      method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Group-Id': 'family', 'X-HomeMeds-State': 'admin' },
      body: JSON.stringify({ name: '测试家庭' }),
    });
  });
  it('keeps HTTP status and server error messages for authentication handling', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json({ error: '仅限管理员' }, { status: 403 }));
    const error = await api('/admin/bootstrap').catch(e => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, message: '仅限管理员' });
  });
  it('forwards cancellation without swallowing the abort', async () => {
    const controller = new AbortController();
    const reason = new DOMException('cancelled', 'AbortError');
    const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValue(reason);
    await expect(api('/download', 'GET', undefined, { signal: controller.signal })).rejects.toBe(reason);
    expect(fetch.mock.calls[0][1]?.signal).toBe(controller.signal);
  });
});
