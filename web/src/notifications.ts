export interface NotificationView {
  id: string
  type: 'RESULT_READY' | 'TASK_FAILED'
  content: string
  target: { type: 'AI_TASK'; taskId: string }
  isRead: boolean
  createdAt: string
  channelStatus: 'NOT_REQUESTED'
}

export function notificationTaskPath(target: unknown): string | null {
  if (!target || typeof target !== 'object') return null
  const value = target as { type?: unknown; taskId?: unknown }
  if (value.type !== 'AI_TASK' || typeof value.taskId !== 'string' || !/^[1-9]\d{0,18}$/.test(value.taskId)) return null
  if (BigInt(value.taskId) > 9223372036854775807n) return null
  return `/ai-tasks/${value.taskId}`
}
