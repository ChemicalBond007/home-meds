import type { User, FamilyGroup, Product, StockItem, Plan, Todo } from './types';
import type { AISettings } from './ai';

export interface SecurityStatus { remaining: number; totp: boolean; passkeys: number }
export interface AdminPasskeys {
  id: string;
  username: string;
  totp: boolean;
  passkeys: { id: string; transports: string[] }[];
}
export interface AISettingsState { settings: AISettings; has_llm_key: boolean; has_tavily_key: boolean }

export interface Warehouse { id: string; group_id: string; name: string }
export interface AdminBootstrap {
  api_version: 1;
  user: User;
  binding_only: boolean;
  groups: FamilyGroup[];
  group_id: string | null;
  users: User[];
  members: User[];
  warehouses: Warehouse[];
  security?: SecurityStatus;
  passkey_admins?: AdminPasskeys[];
  ai?: AISettingsState;
}
export interface AppBootstrap {
  api_version: 1;
  user: User;
  binding_only: boolean;
  groups: FamilyGroup[];
  group_id: string | null;
  members: User[];
  products: Product[];
  stock: StockItem[];
  plans: Plan[];
  warehouses: Warehouse[];
  today: { date: string; time: string; timezone: string; items: Todo[] } | null;
}
