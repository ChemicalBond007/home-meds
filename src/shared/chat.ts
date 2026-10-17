import { z } from 'zod';
import { dateSchema, idSchema, planSchema } from './schemas';

// The server supplies the selected person and, if omitted, today's local date.
// The complete plan is still validated with planSchema before emitting a card.
export const planDraftInputSchema = z.object({ ...planSchema.shape }).omit({ user_id: true }).extend({
  start_date: dateSchema.optional(),
  times: planSchema.shape.times.default([]),
});
const proposal = z.object({ draft_id: idSchema.optional(), draft: planSchema });
export const proposalSchema = z.discriminatedUnion('operation', [
  proposal.extend({ operation: z.literal('create') }),
  proposal.extend({ operation: z.literal('update'), plan_id: idSchema }),
  proposal.extend({ operation: z.literal('pause'), plan_id: idSchema }),
]);
export type Proposal = z.infer<typeof proposalSchema> & { saved?: boolean };
