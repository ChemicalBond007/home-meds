import { describe, it, expect } from 'vitest';
import { parseCode } from '../src/shared/barcode';
import { localClock, scheduledOn, todayTodos } from '../src/shared/schedule';
import { base32, totpCode, checkTotp, encrypt, decrypt } from '../src/worker/crypto';
import type { Plan } from '../src/shared/types';

export const fixturePlan: Plan = {
  id: 'p', group_id: 'g', user_id: 'u', product_id: null, medicine_name: '药', amount: 1, unit: '片',
  times: '["08:00","20:00"]', frequency: 'daily', weekdays: '[1,3]', interval_days: 3,
  start_date: '2026-01-01', end_date: null, meal_relation: 'after', notes: '', enabled: 1,
  notify_family: 1, last_stock_item_id: null, preferred_stock_item_id: null, created_by: 'u', updated_by: 'u',
};
describe('barcode parser', () => {
  it('normalizes EAN13 and rejects invalid checksums', () => {
    expect(parseCode('6901234567892').gtin).toBe('06901234567892');
    expect(parseCode('6901234567891').kind).toBe('unknown');
  });
  it('extracts trace codes and URL traces', () => {
    expect(parseCode('81234567890123456789').product_key).toBe('8123456');
    expect(parseCode('https://example.com/?code=81234567890123456789').trace_code).toBe('81234567890123456789');
  });
  it('parses GS1 fixed and variable fields, including day zero', () => {
    const p = parseCode('(01)06901234567892(17)280200(10)LOT1(21)SN99');
    expect(p).toMatchObject({ gtin: '06901234567892', expiry_date: '2028-02-29', batch_number: 'LOT1', serial_number: 'SN99' });
    expect(parseCode(']C101069012345678921727123110LOT\x1d21SN').serial_number).toBe('SN');
  });
});
describe('schedule in group timezone', () => {
  it('handles daily, weekly, interval and inclusive end dates', () => {
    expect(scheduledOn(fixturePlan, '2026-01-01')).toBe(true);
    expect(scheduledOn({ ...fixturePlan, frequency: 'weekly' }, '2026-01-05')).toBe(true);
    expect(scheduledOn({ ...fixturePlan, frequency: 'weekly' }, '2026-01-06')).toBe(false);
    expect(scheduledOn({ ...fixturePlan, frequency: 'interval' }, '2026-01-04')).toBe(true);
    expect(scheduledOn({ ...fixturePlan, frequency: 'interval' }, '2026-01-05')).toBe(false);
    expect(scheduledOn({ ...fixturePlan, end_date: '2026-01-04' }, '2026-01-05')).toBe(false);
    expect(scheduledOn({ ...fixturePlan, frequency: 'as_needed' }, '2026-01-05')).toBe(false);
  });
  it('uses local dates across UTC and DST boundaries', () => {
    expect(localClock(new Date('2026-01-01T16:01:00Z'), 'Asia/Shanghai')).toEqual({ date: '2026-01-02', time: '00:01' });
    expect(localClock(new Date('2026-03-08T07:01:00Z'), 'America/New_York')).toEqual({ date: '2026-03-08', time: '03:01' });
    expect(localClock(new Date('2026-01-01T02:00:00Z'), 'America/Los_Angeles').date).toBe('2025-12-31');
  });
  it('marks overdue only after 60 minutes', () => {
    expect(todayTodos([fixturePlan], [], 'Asia/Shanghai', new Date('2026-01-01T01:00:00Z'))[0].state).toBe('pending');
    expect(todayTodos([fixturePlan], [], 'Asia/Shanghai', new Date('2026-01-01T01:01:00Z'))[0].state).toBe('overdue');
  });
});
describe('WebCrypto security', () => {
  it('matches RFC 6238 and rejects replay', async () => {
    const secret = base32(new TextEncoder().encode('12345678901234567890'));
    expect(await totpCode(secret, 1)).toBe('287082');
    expect(await checkTotp(secret, '287082', -1, 59000)).toBe(1);
    expect(await checkTotp(secret, '287082', 1, 59000)).toBeNull();
  });
  it('separates encryption purposes', async () => {
    const master = 'test-key-that-has-at-least-32-characters';
    const encrypted = await encrypt(master, 'settings', 'test-value');
    expect(await decrypt(master, 'settings', encrypted)).toBe('test-value');
    await expect(decrypt(master, 'totp', encrypted)).rejects.toThrow();
  });
});
