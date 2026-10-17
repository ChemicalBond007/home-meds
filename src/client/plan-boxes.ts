import type { Product, StockItem } from '../shared/types';

export interface PlanBoxes {
  boxes: StockItem[];
  preferred_stock_item_id: string | null;
  last_stock_item_id: string | null;
}
export function availableBoxes(stock: StockItem[], productId: string | null) {
  return stock.filter(b => b.product_id === productId && b.status === 'active' && b.units_remaining > 0)
    .sort((a, b) => (a.expiry_date || '9999-99-99').localeCompare(b.expiry_date || '9999-99-99') || a.id.localeCompare(b.id));
}
export function expiryLabel(box: StockItem, today: string) {
  return box.expiry_date ? `有效期至 ${box.expiry_date}${box.expiry_date < today ? '（已过期）' : ''}` : '有效期未填写，请核对实物';
}
export function boxLabel(box: StockItem, today: string) {
  return `${expiryLabel(box, today)} · ${box.warehouse_name} · 批号 ${box.batch_number || '未填写'} · ${box.opened ? '已开封' : '未开封'} · 剩余 ${box.units_remaining} ${box.base_unit} · 药盒 ${box.trace_code || box.id.slice(-8)}`;
}
export function productLabel(product: Product, stock: StockItem[] | undefined) {
  const dates = [...new Set(availableBoxes(stock || [], product.id).map(b => b.expiry_date || '未填写'))];
  const expiry = stock ? dates.length ? `有效期 ${dates.slice(0, 3).join(' / ')}${dates.length > 3 ? ` 等 ${dates.length} 种` : ''}` : '暂无可用药盒' : '正在读取有效期';
  return [product.name, product.specification, product.manufacturer, expiry].filter(Boolean).join(' · ');
}
export function initialDoseBox(data: PlanBoxes): string | null {
  if (data.preferred_stock_item_id) return data.boxes.some(b => b.id === data.preferred_stock_item_id) ? data.preferred_stock_item_id : null;
  return data.last_stock_item_id && data.boxes.some(b => b.id === data.last_stock_item_id) ? data.last_stock_item_id : null;
}
export function canConfirmDoseBox(data: PlanBoxes | undefined, selected: string | null, verified: boolean) {
  return !!data && verified && (selected === '' || data.boxes.some(b => b.id === selected));
}
