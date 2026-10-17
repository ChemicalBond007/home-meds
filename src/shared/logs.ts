export const logSourceKeys = ['audit', 'lookup', 'usage', 'dose', 'stock'] as const;
export const logSources = {
  audit: '操作审计', lookup: 'AI 查询', usage: 'AI 用量', dose: '服药记录', stock: '库存操作',
} as const;
export type LogSource = keyof typeof logSources;
export const logActions: Record<string, string> = {
  'auth.register': '注册账号', 'auth.login': '登录', 'auth.logout': '退出登录',
  'auth.totp.setup': '开始绑定验证器', 'auth.totp.bind': '绑定验证器',
  'auth.passkey.options': '开始通行密钥登录', 'auth.passkey.login': '通行密钥登录',
  'auth.passkey.register.options': '开始绑定通行密钥', 'auth.passkey.register': '绑定通行密钥',
  'auth.recovery.options': '验证恢复码操作', 'auth.recovery.regenerate': '重新生成恢复码',
  'admin.reset': '重置管理员', 'group.create': '创建家庭', 'group.update': '修改家庭',
  'group.rotate': '更换分组码', 'member.update': '修改成员',
  'warehouse.create': '创建药箱', 'warehouse.update': '修改药箱', 'warehouse.delete': '删除药箱',
  'product.create': '创建药品', 'product.update': '修改药品',
  'stock.create': '药品入库', 'stock.change': '调整库存', 'stock.update': '修改药盒资料',
  'stock.in': '入库', 'stock.out': '出库', 'stock.move': '移动药盒', 'stock.discard': '丢弃药盒',
  'stock.dose': '服药扣库存', 'stock.undo_dose': '撤销服药恢复库存',
  'plan.create': '创建计划', 'plan.update': '修改计划', 'plan.pause': '暂停计划',
  'dose.create': '记录服药或跳过', 'dose.undo': '撤销服药记录', 'dose.record': '服药记录',
  'ai.settings.read': '查看 AI 配置', 'ai.settings.update': '修改 AI 配置',
  'ai.probe': '检测接口能力', 'ai.lookup': 'AI 药品查询', 'ai.chat': 'AI 计划对话',
  'usage.lookup': '家庭药品查询用量', 'usage.chat': '个人对话用量',
  'push.subscribe': '开启设备提醒', 'push.unsubscribe': '关闭设备提醒',
  'logs.view': '查看使用日志', 'request.other': '其他写入请求',
};
export interface UsageLog {
  id: string;
  created_at: string;
  actor_id: string | null;
  actor_name: string | null;
  group_id: string | null;
  group_name: string | null;
  action: string;
  outcome: 'success' | 'failure' | null;
  http_status: number | null;
  target_id: string | null;
  subject_name: string | null;
  detail: string | null;
}
export interface LogPage { items: UsageLog[]; next_cursor: string | null }
