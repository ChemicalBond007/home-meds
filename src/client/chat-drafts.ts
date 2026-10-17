import { proposalSchema, type Proposal } from '../shared/chat';

export function parseProposal(value: unknown, targetId: string): Proposal {
  const parsed = proposalSchema.safeParse(value);
  if (!parsed.success) throw new Error('收到的草稿资料不完整或格式不正确，未加入待确认列表，请让助手重试。');
  if (parsed.data.draft.user_id !== targetId) throw new Error('草稿不属于当前计划对象，未加入待确认列表。');
  return { ...parsed.data, draft_id: parsed.data.draft_id || crypto.randomUUID() };
}
export function appendProposal(current: Proposal[], incoming: Proposal): Proposal[] {
  return incoming.draft_id && current.some(p => p.draft_id === incoming.draft_id) ? current : [...current, incoming];
}
