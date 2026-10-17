import { z } from 'zod';

export const timeSchema = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);
export const dateSchema = z.iso.date();
export const idSchema = z.string().min(1).max(100);
export const amountSchema = z.number().min(0.001).max(100000).refine(n => Math.abs(n * 1000 - Math.round(n * 1000)) < 1e-6, '最多三位小数');
export const planSchema = z.object({
  user_id: idSchema,
  product_id: idSchema.nullable().default(null),
  medicine_name: z.string().trim().min(1).max(200),
  amount: amountSchema,
  unit: z.string().trim().min(1).max(20),
  times: z.array(timeSchema).max(24).transform(a => [...new Set(a)].sort()),
  frequency: z.enum(['daily', 'weekly', 'interval', 'as_needed']),
  weekdays: z.array(z.number().int().min(0).max(6)).max(7).default([]),
  interval_days: z.number().int().min(1).max(365).default(1),
  start_date: dateSchema,
  end_date: dateSchema.nullable().default(null),
  meal_relation: z.enum(['none', 'before', 'after', 'with']).default('none'),
  notes: z.string().max(2000).default(''),
  enabled: z.boolean().default(true),
  notify_family: z.boolean().optional(),
}).refine(p => !p.end_date || p.end_date >= p.start_date, '结束日期不能早于开始日期')
  .refine(p => p.frequency === 'as_needed' || p.times.length > 0, '请选择服药时间')
  .refine(p => p.frequency !== 'weekly' || p.weekdays.length > 0, '请选择每周几');

export const sourceSchema = z.object({ title: z.string().max(300), url: z.url().refine(s => /^https?:\/\//.test(s)) });
export const medicineInfoSchema = z.object({
  name: z.string().max(200).nullable(),
  generic_name: z.string().max(200).nullable(),
  manufacturer: z.string().max(300).nullable(),
  specification: z.string().max(300).nullable(),
  indications: z.string().max(4000).nullable(),
  precautions: z.string().max(4000).nullable(),
  base_unit: z.string().max(20).nullable(),
  units_per_package: z.number().positive().max(100000).nullable(),
  sources: z.array(sourceSchema).max(20),
});
export const productSchema = medicineInfoSchema.extend({
  name: z.string().trim().min(1).max(200),
  base_unit: z.string().trim().min(1).max(20),
  units_per_package: amountSchema,
  status: z.enum(['pending', 'confirmed']).default('confirmed'),
  code: z.string().max(100).optional(),
});
export const productUpdateSchema = productSchema.extend({
  codes: z.array(z.string().trim().min(1).max(100)).max(50).optional(),
});
export const stockMetadataSchema = z.object({
  expiry_date: dateSchema.nullable(),
  batch_number: z.string().trim().max(100).nullable(),
  serial_number: z.string().trim().max(100).nullable(),
  trace_code: z.string().regex(/^\d{20}$/, '追溯码须为 20 位数字').nullable(),
  units_per_package: amountSchema,
  opened: z.boolean(),
  expected_version: z.number().int().min(0),
});
