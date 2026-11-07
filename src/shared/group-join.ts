export function groupJoinQrValue(code: string): string {
  const value = code.trim();
  if (!value || value.length > 100) throw new Error('分组码不能为空或超过 100 个字符');
  return JSON.stringify({ type: 'home-meds.join', version: 1, code: value });
}
