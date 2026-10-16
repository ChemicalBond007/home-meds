export interface ParsedCode {
  kind: 'trace' | 'gtin' | 'gs1' | 'unknown';
  raw: string;
  product_key?: string;
  gtin?: string;
  trace_code?: string;
  expiry_date?: string;
  batch_number?: string;
  serial_number?: string;
}
export function validGtin(s: string) {
  if (!/^\d{8}$|^\d{12,14}$/.test(s)) return false;
  const digits = [...s].map(Number);
  const check = digits.pop()!;
  const sum = digits.reverse().reduce((a, d, i) => a + d * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - sum % 10) % 10 === check;
}
function gs1Date(value: string) {
  const year = 2000 + Number(value.slice(0, 2)), month = Number(value.slice(2, 4)), day = Number(value.slice(4, 6));
  if (month < 1 || month > 12) return undefined;
  const max = new Date(Date.UTC(year, month, 0)).getUTCDate();
  if (day > max) return undefined;
  return `${year}-${String(month).padStart(2, '0')}-${String(day || max).padStart(2, '0')}`;
}
export function parseCode(input: string): ParsedCode {
  const raw = input.trim().slice(0, 4096);
  let value = raw;
  if (/^https?:\/\//i.test(value)) {
    try {
      const u = new URL(value);
      const parts = [u.pathname, ...u.searchParams.values()].map(s => { try { return decodeURIComponent(s); } catch { return s; } });
      const trace = parts.join(' ').match(/(?:^|\D)(\d{20})(?!\d)/)?.[1];
      if (trace) return { ...parseCode(trace), raw };
      const gtin = u.searchParams.get('gtin') || u.pathname.match(/\/01\/(\d{14})/)?.[1];
      if (gtin && validGtin(gtin)) return { ...parseCode(gtin), raw };
    } catch { /* A malformed URL remains an unknown code. */ }
    return { kind: 'unknown', raw };
  }
  if (/^\d{20}$/.test(value)) return { kind: 'trace', raw, trace_code: value, product_key: value.slice(0, 7) };
  if (/^\d{8}$|^\d{12,14}$/.test(value) && validGtin(value)) {
    const gtin = value.padStart(14, '0');
    return { kind: 'gtin', raw, gtin, product_key: gtin };
  }
  value = value.replace(/^\][A-Za-z]\d/, '').replace(/\((\d{2})\)/g, '\x1d$1').replace(/^\x1d/, '');
  if (!value.startsWith('01')) return { kind: 'unknown', raw };
  const result: ParsedCode = { kind: 'gs1', raw };
  while (value) {
    value = value.replace(/^\x1d+/, '');
    const ai = value.slice(0, 2);
    value = value.slice(2);
    if (ai === '01') {
      const gtin = value.slice(0, 14);
      if (!validGtin(gtin)) return { kind: 'unknown', raw };
      result.gtin = gtin; result.product_key = gtin; value = value.slice(14);
    } else if (['17', '11', '15'].includes(ai)) {
      if (!/^\d{6}/.test(value)) return { kind: 'unknown', raw };
      if (ai === '17') result.expiry_date = gs1Date(value.slice(0, 6));
      value = value.slice(6);
    } else if (ai === '10' || ai === '21') {
      const end = value.indexOf('\x1d');
      const field = value.slice(0, end < 0 ? 20 : end);
      if (ai === '10') result.batch_number = field;
      else result.serial_number = field;
      value = value.slice(field.length);
    } else break;
  }
  return result.gtin ? result : { kind: 'unknown', raw };
}
