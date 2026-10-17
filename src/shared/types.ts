export interface User {
  id: string;
  username: string;
  role: 'admin' | 'user';
  group_id: string | null;
  is_elderly: number;
}
export interface FamilyGroup {
  id: string;
  name: string;
  timezone: string;
  join_code?: string;
  meal_times: string;
}
export interface Product {
  id: string;
  group_id: string;
  name: string;
  generic_name: string | null;
  manufacturer: string | null;
  specification: string | null;
  indications: string | null;
  precautions: string | null;
  base_unit: string;
  units_per_package: number;
  status: 'pending' | 'confirmed';
  sources: string;
}
export interface StockItem {
  id: string;
  group_id: string;
  product_id: string;
  warehouse_id: string;
  name: string;
  warehouse_name: string;
  base_unit: string;
  trace_code: string | null;
  batch_number: string | null;
  serial_number: string | null;
  expiry_date: string | null;
  metadata_version: number;
  opened: number;
  units_per_package: number;
  units_remaining: number;
  status: 'active' | 'empty' | 'discarded';
}
export interface Plan {
  id: string;
  group_id: string;
  user_id: string;
  username?: string;
  product_id: string | null;
  medicine_name: string;
  amount: number;
  unit: string;
  times: string;
  frequency: 'daily' | 'weekly' | 'interval' | 'as_needed';
  weekdays: string;
  interval_days: number;
  start_date: string;
  end_date: string | null;
  meal_relation: 'none' | 'before' | 'after' | 'with';
  notes: string;
  enabled: number;
  notify_family: number;
  last_stock_item_id: string | null;
  preferred_stock_item_id: string | null;
  created_by: string;
  updated_by: string;
}
export interface DoseLog {
  id: string;
  plan_id: string;
  local_date: string;
  scheduled_time: string;
  status: 'taken' | 'skipped' | 'undone';
  taken_at: string | null;
  amount: number;
  unit: string;
  stock_item_id: string | null;
  actor_id: string;
  actor_name?: string;
}
export interface Todo {
  key: string;
  plan: Plan;
  local_date: string;
  scheduled_time: string;
  state: 'pending' | 'overdue' | 'taken' | 'skipped';
  log?: DoseLog;
}
