import { z } from 'zod';
import type { InputHTMLAttributes } from 'react';
import { amountSchema } from '../shared/schemas';

// Keep transient input (including an empty string) untouched until submission.
export function NumberInput({ value, onValue, ...props }: Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange'> & {
  value: string | number; onValue: (value: string) => void;
}) {
  return <input {...props} type="number" value={value} onChange={e => onValue(e.target.value)} />;
}
const completedNumber = (value: unknown) => typeof value === 'string' && value.trim() ? Number(value) : value;
export const enteredAmount = z.preprocess(completedNumber, amountSchema);
export const enteredPackages = z.preprocess(completedNumber, z.number().int().min(1).max(100));
