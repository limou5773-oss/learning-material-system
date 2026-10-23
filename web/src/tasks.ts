export type GoalType = 'REVIEW_CARDS' | 'EXTRACT_KEY_POINTS' | 'LINK_NOTES'
export type TaskStatus = 'QUEUED' | 'RUNNING' | 'WAIT_CONFIRM' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface AiTask {
  id: string
  materialId: string
  materialVersion: number
  parentTaskId: string | null
  goalType: GoalType
  status: TaskStatus
  stage: string | null
  stepCount: number
  errorCode: string | null
  canCancel: boolean
  canRetry: boolean
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface TaskStep {
  stepNo: number
  attemptNo: number
  toolName: string
  providerKind: 'LOCAL' | 'MODEL' | 'OCR'
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'UNKNOWN'
  outputSummary: string | null
  errorCode: string | null
  startedAt: string
  finishedAt: string | null
  costAmount: string | null
  currency: string | null
}

export const goalLabels: Record<GoalType, string> = {
  REVIEW_CARDS: '生成复习卡片',
  EXTRACT_KEY_POINTS: '提炼课件重点',
  LINK_NOTES: '整理并关联已有笔记',
}

export const statusLabels: Record<TaskStatus, string> = {
  QUEUED: '排队中', RUNNING: '执行中', WAIT_CONFIRM: '待确认',
  SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}

export const stageLabels: Record<string, string> = {
  QUEUED: '等待领取', STARTING: '开始处理', FAILED: '处理失败',
  CANCELLED: '用户取消', SOURCE_DELETED: '原资料已删除',
}

export const stepStatusLabels: Record<TaskStep['status'], string> = {
  RUNNING: '进行中', SUCCESS: '成功', FAILED: '失败', UNKNOWN: '结果未知',
}
