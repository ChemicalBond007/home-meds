import type { prepareZXingModule } from 'zxing-wasm/reader';

export function prepareScannerReader(reader: { prepareZXingModule: typeof prepareZXingModule }, wasmUrl: string) {
  reader.prepareZXingModule({
    overrides: { locateFile: (path: string) => path.endsWith('.wasm') ? wasmUrl : path },
    // ZXing's default shallow comparator uses Object.hasOwn (Chrome 93+).
    // Identity comparison is sufficient for this configuration and works in WebView 87.
    equalityFn: Object.is,
  });
}
