import { z } from 'zod';
import { planSchema } from '../shared/schemas';
import { planDraftInputSchema } from '../shared/chat';
import { localClock } from '../shared/schedule';
import type { ChatToolExecutor, StagedDraft } from '../shared/ai-agent';
import { medicineCandidates, medicineSearchSchema, medicineListSchema } from './medicine-search';
import type { Plan, FamilyGroup } from '../shared/types';

export interface ChatTarget { id: string; username: string; is_elderly: number }
export function planToDraft(p: Plan) {
  return {
    user_id: p.user_id, product_id: p.product_id, medicine_name: p.medicine_name, amount: p.amount, unit: p.unit,
    times: JSON.parse(p.times), frequency: p.frequency, weekdays: JSON.parse(p.weekdays), interval_days: p.interval_days,
    start_date: p.start_date, end_date: p.end_date, meal_relation: p.meal_relation, notes: p.notes,
    enabled: !!p.enabled, notify_family: !!p.notify_family, preferred_stock_item_id: p.preferred_stock_item_id,
  };
}
/**
 * Read-only database tools and draft validation for plan chat. `knownMedicines`
 * collects product IDs the model has actually read this round; drafts may only
 * reference those.
 */
export function chatToolExecutor(db: D1Database, g: FamilyGroup, target: ChatTarget, knownMedicines: Set<string>): ChatToolExecutor {
  return async (name, args) => {
    switch (name) {
      case 'find_medicines': {
        const q = medicineSearchSchema.parse(args);
        const found = await medicineCandidates(db, g.id, q.query, q.cursor);
        found.medicines.forEach(p => knownMedicines.add(p.id));
        return { result: found };
      }
      case 'list_medicines': {
        const q = medicineListSchema.parse(args);
        const found = await medicineCandidates(db, g.id, '', q.cursor);
        found.medicines.forEach(p => knownMedicines.add(p.id));
        return { result: found };
      }
      case 'list_family_members':
        return { result: (await db.prepare('SELECT id,username,is_elderly FROM users WHERE group_id=?').bind(g.id).all()).results };
      case 'list_plans':
        if (args.userId !== target.id) throw new Error('只能查询当前计划对象');
        return { result: (await db.prepare('SELECT * FROM medication_plans WHERE group_id=? AND user_id=?').bind(g.id, target.id).all<Plan>()).results
          .map(p => { if (p.product_id) knownMedicines.add(p.product_id); return { id: p.id, ...planToDraft(p) }; }) };
      case 'add_plan_draft':
      case 'propose_plan':
      case 'propose_plan_update':
      case 'propose_plan_pause': {
        let planId: string | undefined, draft: unknown = args.draft;
        const operation = ['add_plan_draft', 'propose_plan'].includes(name) ? 'create' : name === 'propose_plan_pause' ? 'pause' : 'update';
        if (name === 'add_plan_draft') {
          const input = planDraftInputSchema.parse(args);
          draft = { ...input, user_id: target.id, start_date: input.start_date || localClock(new Date(), g.timezone).date };
        }
        let sourcePlan: Plan | null = null;
        if (operation !== 'create') {
          planId = z.string().parse(args.planId);
          const p = await db.prepare('SELECT * FROM medication_plans WHERE id=? AND group_id=? AND user_id=?').bind(planId, g.id, target.id).first<Plan>();
          if (!p) throw new Error('计划不存在或无权修改');
          sourcePlan = p;
          if (p.product_id) knownMedicines.add(p.product_id);
          draft = { ...planToDraft(p), ...(operation === 'pause' ? { enabled: false } : z.record(z.string(), z.unknown()).parse(args.changes)) };
        }
        const input = z.record(z.string(), z.unknown()).parse(draft);
        const valid = planSchema.parse({ ...input, preferred_stock_item_id:
          sourcePlan && sourcePlan.product_id === input.product_id ? sourcePlan.preferred_stock_item_id : null });
        if (valid.user_id !== target.id) throw new Error('草稿必须属于当前计划对象');
        if (valid.product_id) {
          if (!knownMedicines.has(valid.product_id)) throw new Error('请先通过药品查询工具读取目标药品，不得凭空填写药品编号');
          const p = await db.prepare('SELECT name,base_unit FROM products WHERE id=? AND group_id=?').bind(valid.product_id, g.id).first<{ name: string; base_unit: string }>();
          if (!p || p.base_unit !== valid.unit) throw new Error('药品或用量单位不正确');
          valid.medicine_name = p.name;
        }
        valid.notify_family ??= !!target.is_elderly;
        const staged: StagedDraft = { operation, plan_id: planId, draft: valid };
        return { draft: staged };
      }
      default: throw new Error('未知工具');
    }
  };
}
