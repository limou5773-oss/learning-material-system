export interface NotificationView {
  id: string
  type: 'RESULT_READY' | 'TASK_FAILED' | 'REVIEW_DECIDED' | 'REVIEW_SUPERSEDED'
  content: string
  target: { type: 'AI_TASK'; taskId: string } | { type: 'MATERIAL'; materialId: string; reviewId: string }
  isRead: boolean
  createdAt: string
  channelStatus: 'NOT_REQUESTED'
}

export function notificationTaskPath(target: unknown): string | null {
  if (!target || typeof target !== 'object') return null
  const value = target as { type?: unknown; taskId?: unknown; materialId?: unknown; reviewId?: unknown }
  const validId = (id: unknown): id is string => typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
  if (value.type === 'AI_TASK' && validId(value.taskId)) return `/ai-tasks/${value.taskId}`
  if (value.type === 'MATERIAL' && validId(value.materialId) && validId(value.reviewId)) return `/materials/${value.materialId}`
  return null
}
