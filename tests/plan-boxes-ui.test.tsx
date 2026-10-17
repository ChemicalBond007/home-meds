import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { DoseBoxChoices, PlanEditor, toDraft } from '../src/client/Plans';
import { availableBoxes, boxLabel, canConfirmDoseBox, initialDoseBox, productLabel, type PlanBoxes } from '../src/client/plan-boxes';
import type { Product, StockItem, User } from '../src/shared/types';

vi.mock('../src/client/api', () => ({ api: vi.fn() }));
vi.mock('../src/client/Scanner', () => ({ Scanner: () => null }));
const member: User = { id: 'u', username: '家人', role: 'user', group_id: 'g', is_elderly: 0 };
const product: Product = { id: 'm', group_id: 'g', name: '虚构同名药', generic_name: null, manufacturer: '虚构厂家',
  specification: '示例规格', indications: null, precautions: null, base_unit: '片', units_per_package: 10, status: 'confirmed', sources: '[]' };
const early: StockItem = { id: 'early', group_id: 'g', product_id: 'm', warehouse_id: 'w', name: product.name,
  warehouse_name: '抽屉', base_unit: '片', trace_code: null, batch_number: 'EARLY', serial_number: null,
  expiry_date: '2090-01-01', metadata_version: 0, opened: 0, units_per_package: 10, units_remaining: 10, status: 'active' };
const late: StockItem = { ...early, id: 'late', batch_number: 'LATE', expiry_date: '2099-01-01' };
const data: PlanBoxes = { boxes: [early, late], preferred_stock_item_id: 'late', last_stock_item_id: 'early' };
const initial = { user_id: 'u', product_id: 'm', preferred_stock_item_id: 'late', medicine_name: product.name,
  amount: 1, unit: '片', times: ['08:00'], frequency: 'daily' as const, weekdays: [], interval_days: 1,
  start_date: '2020-01-01', end_date: null, meal_relation: 'none' as const, notes: '', enabled: true, notify_family: false };
const editor = (stock: StockItem[] | undefined = [early, late], draft = initial) => renderToStaticMarkup(
  <PlanEditor initial={draft} members={[member]} products={[product]} stock={stock}
    family={{ id: 'g', name: '家庭', timezone: 'Asia/Shanghai', meal_times: '{"morning":"08:00"}' }} close={vi.fn()} saved={vi.fn()} />);
const choices = (selected: string | null, choiceData: PlanBoxes | undefined = data, verified = false) => renderToStaticMarkup(
  <DoseBoxChoices data={choiceData} selected={selected} today="2026-10-17" verified={verified} busy={false} choose={vi.fn()} verify={vi.fn()} />);

describe('expiry-aware plan selection', () => {
  it('lists different expiry dates and box identifiers for the same product without merging batches', () => {
    const html = editor();
    for (const text of ['优先药盒', '2090-01-01', '2099-01-01', 'EARLY', 'LATE', '抽屉', '示例规格', '虚构厂家']) expect(html).toContain(text);
    expect(html).toMatch(/value="late" selected=""/);
    expect(productLabel(product, [early, late])).toContain('2090-01-01 / 2099-01-01');
  });
  it('filters unavailable boxes but keeps a warning for the original preference', () => {
    const stock = [{ ...late, status: 'empty' as const, units_remaining: 0 }, early];
    expect(availableBoxes(stock, 'm').map(b => b.id)).toEqual(['early']);
    const html = editor(stock);
    expect(html).toContain('原优先药盒不可用');
    expect(html).toContain('不会自动改用另一盒');
  });
  it('distinguishes unknown and expired dates, and waits for loaded inventory', () => {
    expect(boxLabel({ ...early, expiry_date: null }, '2026-10-17')).toContain('有效期未填写，请核对实物');
    expect(boxLabel({ ...early, expiry_date: '2000-01-01' }, '2026-10-17')).toContain('已过期');
    // Passing no stock prop represents loading; an empty list is a completed empty inventory.
    const html = renderToStaticMarkup(<PlanEditor initial={initial} members={[member]} products={[product]}
      family={{ id: 'g', name: '家庭', timezone: 'Asia/Shanghai', meal_times: '{}' }} close={vi.fn()} saved={vi.fn()} />);
    expect(html).toContain('正在读取药盒有效期');
    expect(html).toMatch(/type="submit" disabled=""/);
    expect(editor([])).toContain('暂无可用药盒');
  });
  it('keeps the preferred box when converting an existing plan to an editable draft', () => {
    expect(toDraft({ ...initial, id: 'p', group_id: 'g', times: '["08:00"]', weekdays: '[]', enabled: 1, notify_family: 0,
      last_stock_item_id: 'early', created_by: 'u', updated_by: 'u' }).preferred_stock_item_id).toBe('late');
  });
});
describe('dose box verification', () => {
  it('prefers the planned box over the last used box, and uses the last box only without a preference', () => {
    expect(initialDoseBox(data)).toBe('late');
    expect(initialDoseBox({ ...data, preferred_stock_item_id: null })).toBe('early');
    expect(initialDoseBox({ ...data, boxes: [early] })).toBeNull();
    expect(initialDoseBox({ ...data, preferred_stock_item_id: null, last_stock_item_id: 'missing' })).toBeNull();
  });
  it('requires an explicit selection and physical verification, including without inventory', () => {
    expect(canConfirmDoseBox(data, 'late', false)).toBe(false);
    expect(canConfirmDoseBox(data, 'late', true)).toBe(true);
    expect(canConfirmDoseBox(data, null, true)).toBe(false);
    expect(canConfirmDoseBox(data, 'missing', true)).toBe(false);
    expect(canConfirmDoseBox(undefined, '', true)).toBe(false);
    expect(canConfirmDoseBox(data, '', false)).toBe(false);
    expect(canConfirmDoseBox(data, '', true)).toBe(true);
  });
  it('shows selected expiry, batch, remaining stock, location and physical verification', () => {
    const html = choices('late');
    for (const text of ['计划优先药盒', '上次使用', '本次药盒', '2099-01-01', 'LATE', '抽屉', '剩余 10 片', '我已核对实物药名、规格、有效期和批号']) expect(html).toContain(text);
    expect(html).toMatch(/type="checkbox"(?! checked)/);
    expect(choices('late', data, true)).toMatch(/type="checkbox" checked=""/);
  });
  it('never silently checks no-stock or another box when the preferred box is unavailable', () => {
    const html = choices(null, { ...data, boxes: [early] });
    expect(html).toContain('未自动换盒');
    expect(html).not.toContain('checked=""');
    expect(html).toMatch(/type="checkbox" disabled=""/);
    expect(choices('')).toContain('系统无法核对实际药盒有效期');
  });
  it('warns for an expired or unknown selected box instead of calling it safe', () => {
    const expired = { ...late, expiry_date: '2000-01-01' };
    expect(choices('late', { ...data, boxes: [expired] })).toContain('已过期');
    expect(choices('late', { ...data, boxes: [{ ...late, expiry_date: null }] })).toContain('有效期未填写，请核对实物');
  });
});
