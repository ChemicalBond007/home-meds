import { useEffect, useRef, useState } from 'react';
import { Modal, Message, errorText } from './components';
type Detector = { detect(input: HTMLVideoElement): Promise<{ rawValue: string }[]> };
type DetectorConstructor = new (options?: { formats: string[] }) => Detector;
export function Scanner({ onCode, close }: { onCode: (value: string) => void; close: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState(''), [manual, setManual] = useState('');
  useEffect(() => {
    let stream: MediaStream | undefined, cancelled = false, timer: ReturnType<typeof setTimeout>;
    const stop = () => stream?.getTracks().forEach(t => t.stop());
    const start = async () => {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('此浏览器不支持相机，请手动输入药盒上的码');
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 } }, audio: false });
      if (cancelled) { stop(); return; }
      video.current!.srcObject = stream; await video.current!.play();
      const Native = (window as Window & { BarcodeDetector?: DetectorConstructor }).BarcodeDetector;
      let detector: Detector | undefined = Native ? new Native({ formats: ['ean_13', 'code_128', 'data_matrix', 'qr_code'] }) : undefined;
      const canvas = document.createElement('canvas'), ctx = canvas.getContext('2d', { willReadFrequently: true })!;
      const fallback = async () => {
        const zxing = await import('zxing-wasm/reader');
        const wasm = (await import('zxing-wasm/reader/zxing_reader.wasm?url')).default;
        zxing.prepareZXingModule({ overrides: { locateFile: (p: string) => p.endsWith('.wasm') ? wasm : p } });
        return zxing;
      };
      let zxing: Awaited<ReturnType<typeof fallback>> | undefined;
      const scan = async () => {
        if (cancelled || !video.current) return;
        try {
          let value: string | undefined;
          if (detector) {
            try { value = (await detector.detect(video.current))[0]?.rawValue; } catch { detector = undefined; }
          }
          if (!detector) {
            zxing ??= await fallback();
            canvas.width = Math.min(video.current.videoWidth, 1280);
            canvas.height = Math.round(video.current.videoHeight * canvas.width / video.current.videoWidth);
            ctx.drawImage(video.current, 0, 0, canvas.width, canvas.height);
            value = (await zxing.readBarcodes(ctx.getImageData(0, 0, canvas.width, canvas.height), { tryHarder: true }))[0]?.text;
          }
          if (!cancelled && value) { cancelled = true; stop(); onCode(value); return; }
        } catch (e) { if (!cancelled) setError(errorText(e)); }
        if (!cancelled) timer = setTimeout(() => { void scan(); }, 550);
      };
      await scan();
    };
    start().catch(e => { if (!cancelled) setError(errorText(e)); stop(); });
    return () => { cancelled = true; clearTimeout(timer); stop(); };
  }, [onCode]);
  return <Modal title="对准药盒上的码" close={close}>
    <p>支持条形码、追溯码和 GS1 二维码。请保持药盒平稳。</p>
    <video className="scanner" ref={video} muted playsInline aria-label="扫码相机画面" />
    {error && <Message warning>{error}</Message>}
    <form onSubmit={e => { e.preventDefault(); if (manual.trim()) onCode(manual.trim()); }}>
      <label>也可以手动输入<input value={manual} maxLength={4096} onChange={e => setManual(e.target.value)} required autoComplete="off" /></label>
      <button type="submit">确认这个码</button>
    </form>
  </Modal>;
}
