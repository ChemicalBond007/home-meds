import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { NumberInput, enteredAmount, enteredPackages } from '../src/client/NumberInput';
import { InventoryPage, ProductEditor, ProductFields, StockMetadataEditor, parseProductDraft, type ProductDraft } from '../src/client/Inventory';
import { useLoad } from '../src/client/components';
import type { Product, StockItem, User } from '../src/shared/types';
vi.mock('../src/client/api', () => ({ api: vi.fn(), sse: vi.fn() }));
vi.mock('../src/client/Scanner', () => ({ Scanner: () => null }));
vi.mock('../src/client/components', async importOriginal => ({
  ...await importOriginal<typeof import('../src/client/components')>(), useLoad: vi.fn(),
}));
const draft: ProductDraft = { name: '虚构药', generic_name: null, manufacturer: null, specification: null, indications: null, precautions: null,
  base_unit: '片', units_per_package: '', sources: [], status: 'confirmed' };
const box: StockItem = { id: 'box', group_id: 'g', product_id: 'p', warehouse_id: 'w', name: '虚构药', warehouse_name: '药箱',
  base_unit: '片', trace_code: null, batch_number: 'BATCH', serial_number: 'SERIAL', expiry_date: '2027-01-01', opened: 0,
  units_per_package: 10, units_remaining: 10, status: 'active', metadata_version: 0 };
beforeEach(() => {
  vi.mocked(useLoad).mockImplementation(path => ({
    data: path.startsWith('/products/') ? ['barcode'] : [box], loading: false, error: '', reload: vi.fn(),
  }));
});
describe('quantity entry', () => {
  it('preserves clearing and unfinished input without coercion or validation on change', () => {
    const onValue = vi.fn(), field = NumberInput({ value: '', onValue });
    expect(field.props.value).toBe('');
    for (const value of ['', '0', '2.', '24']) field.props.onChange({ target: { value } });
    expect(onValue.mock.calls.map(args => args[0])).toEqual(['', '0', '2.', '24']);
    expect(renderToStaticMarkup(<NumberInput value="" onValue={onValue} />)).toContain('value=""');
  });
  it('validates completed values and package counts only when explicitly submitted', () => {
    for (const value of ['', ' ', '0', '-1', '0.0001', '100001', 'Infinity', 'not-number']) expect(() => enteredAmount.parse(value)).toThrow();
    expect(enteredAmount.parse('24')).toBe(24);
    expect(enteredAmount.parse('0.125')).toBe(0.125);
    expect(() => enteredPackages.parse('')).toThrow();
    expect(() => enteredPackages.parse('1.5')).toThrow();
    expect(enteredPackages.parse('2')).toBe(2);
    expect(() => parseProductDraft(draft)).toThrow();
    expect(parseProductDraft({ ...draft, units_per_package: '24' }).units_per_package).toBe(24);
  });
  it('renders a blank product quantity and editable information sources', () => {
    const html = renderToStaticMarkup(<ProductFields value={{ ...draft, sources: [{ title: '说明书', url: 'https://example.com/info' }] }} set={vi.fn()} />);
    expect(html).toMatch(/type="number" value=""/);
    expect(html).toContain('添加资料来源');
    expect(html).toContain('来源 1 网址');
  });
  it('offers full box metadata editing only to admins and renders shared-data editing separately', () => {
    const user: User = { id: 'u', username: '家人', role: 'user', group_id: 'g', is_elderly: 0 };
    const props = { family: { id: 'g', name: '家', timezone: 'Asia/Shanghai', meal_times: '{}' }, products: [], warehouses: [],
      version: 0, changed: vi.fn(), openIntake: vi.fn() };
    expect(renderToStaticMarkup(<InventoryPage {...props} user={user} />)).not.toContain('修改药盒资料');
    expect(renderToStaticMarkup(<InventoryPage {...props} user={{ ...user, role: 'admin' }} />)).toContain('修改药盒资料');
    const html = renderToStaticMarkup(<StockMetadataEditor box={box} close={vi.fn()} saved={vi.fn()} />);
    for (const text of ['有效期截止', '批号', '序列号', '完整追溯码', '每盒数量', '已开封', '取消编辑并刷新药盒']) expect(html).toContain(text);
    const product: Product = { ...draft, id: 'p', group_id: 'g', units_per_package: 10, sources: '[]' };
    expect(renderToStaticMarkup(<ProductEditor product={product} close={vi.fn()} saved={vi.fn()} />)).toContain('条码映射键');
  });
});
