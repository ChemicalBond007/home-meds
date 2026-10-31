import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';

// Check production output: source CSS and the Vite dev server do not exercise
// the minifier that previously broke mobile layouts in older Android WebViews.
const assets = new URL('../dist/assets/', import.meta.url);
const files = (await readdir(assets)).filter(name => name.endsWith('.css'));
assert.ok(files.length, 'The build must produce CSS assets');
const styles = await Promise.all(files.map(name => readFile(new URL(name, assets), 'utf8')));
for (let i = 0; i < styles.length; i++) {
  assert.doesNotMatch(styles[i], /@media[^{]*[<>]/, `${files[i]} contains media range syntax unsupported by WebView 87`);
}
assert.ok(styles.some(css => /@media[^{]*max-width\s*:\s*800px/.test(css)), 'The built CSS must retain the 800px mobile breakpoint');
console.log('Built CSS keeps mobile breakpoints compatible with WebView 87.');
