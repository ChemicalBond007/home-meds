import { afterEach, describe, expect, it, vi } from 'vitest';
import * as reader from 'zxing-wasm/reader';
import { prepareScannerReader } from '../src/client/scanner-reader';

afterEach(() => { reader.purgeZXingModule(); vi.restoreAllMocks(); });

describe('scanner reader compatibility', () => {
  it('configures the real ZXing reader without Object.hasOwn, including reopening the scanner', () => {
    reader.purgeZXingModule();
    const descriptor = Object.getOwnPropertyDescriptor(Object, 'hasOwn')!;
    let failure: unknown;
    let hasOwnAfterSetup: unknown;
    try {
      Object.defineProperty(Object, 'hasOwn', { ...descriptor, value: undefined });
      prepareScannerReader(reader, '/assets/zxing_reader.wasm');
      prepareScannerReader(reader, '/assets/zxing_reader.wasm');
      hasOwnAfterSetup = Object.hasOwn;
    } catch (error) { failure = error; }
    finally { Object.defineProperty(Object, 'hasOwn', descriptor); }
    expect(failure).toBeUndefined();
    expect(hasOwnAfterSetup).toBeUndefined();
  });

  it('keeps the decoder self-hosted and selects the legacy-safe comparator', () => {
    const prepareZXingModule = vi.spyOn(reader, 'prepareZXingModule');
    prepareScannerReader(reader, '/assets/zxing_reader-123.wasm');
    expect(prepareZXingModule).toHaveBeenCalledOnce();
    const options = prepareZXingModule.mock.calls[0][0]!;
    expect(options.equalityFn).toBe(Object.is);
    expect(options.overrides!.locateFile!('zxing_reader.wasm', '')).toBe('/assets/zxing_reader-123.wasm');
    expect(options.overrides!.locateFile!('other.data', '')).toBe('other.data');
    expect(options.fireImmediately).not.toBe(true);
  });

  it('does not replace Object.hasOwn in modern browsers', () => {
    const original = Object.hasOwn;
    prepareScannerReader(reader, '/assets/zxing_reader.wasm');
    expect(Object.hasOwn).toBe(original);
  });
});
