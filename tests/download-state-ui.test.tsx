import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AdminSettings } from '../src/client/AdminSettings';
import type { AdminBootstrap } from '../src/shared/bootstrap';
import type { DownloadSettings } from '../src/shared/download';

const capture = vi.hoisted(() => ({ props: undefined as undefined | {
  initial?: DownloadSettings; onSaved?: (settings: DownloadSettings) => void;
} }));
vi.mock('../src/client/api', () => ({ api: vi.fn() }));
vi.mock('../src/client/DownloadSettings', () => ({
  DownloadSettings: (props: typeof capture.props) => { capture.props = props; return null; },
}));

describe('administrator download snapshot synchronization', () => {
  it('updates parent bootstrap after saving so reopening the section cannot restore the stale link', () => {
    const data: AdminBootstrap = {
      api_version: 1, user: { id: 'a', username: '管理员', role: 'admin', group_id: null, is_elderly: 0 },
      binding_only: false, group_id: null, groups: [], users: [], members: [], warehouses: [],
      download: { android_apk_url: 'https://downloads.example.com/old.apk' },
    };
    const changed = vi.fn();
    renderToStaticMarkup(<AdminSettings section="download" data={data} changed={changed} onBusy={() => {}} />);
    expect(capture.props?.initial).toEqual(data.download);
    const updated = { android_apk_url: 'https://downloads.example.com/new.apk' };
    capture.props!.onSaved!(updated);
    expect(changed).toHaveBeenLastCalledWith({ ...data, download: updated });
    const next = changed.mock.lastCall![0] as AdminBootstrap;
    renderToStaticMarkup(<AdminSettings section="download" data={next} changed={changed} onBusy={() => {}} />);
    expect(capture.props?.initial).toEqual(updated);
    capture.props!.onSaved!({ android_apk_url: '' });
    expect(changed.mock.lastCall![0].download).toEqual({ android_apk_url: '' });
  });
});
