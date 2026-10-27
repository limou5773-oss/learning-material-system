import { shanghaiDateStart } from './statistics'
import { goalLabels, statusLabels, stageLabels, stepStatusLabels, taskErrorMessage, type GoalType, type TaskStatus } from './tasks'

export interface AdminTask {
  id: string
  userId: string
  materialId: string
  goalType: GoalType | 'UNKNOWN'
  status: TaskStatus | 'UNKNOWN'
  stage: string | null
  stepCount: number
  errorCode: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
}

export interface AdminTaskStep {
  stepNo: number
  attemptNo: number
  toolName: 'planner' | 'search_personal_materials' | 'prepare_source' | 'extract_pdf_text' | 'recognize_image' | 'generate_summary_cards' | 'validate_candidate' | 'UNKNOWN'
  providerKind: 'LOCAL' | 'MODEL' | 'OCR' | 'UNKNOWN'
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'UNKNOWN'
  errorCode: string | null
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
}

export function adminTaskRange(fromDate: string, toDate: string): { from?: string; to?: string } {
  function bound(value: string) {
    let instant: string
    try { instant = shanghaiDateStart(value) }
    catch { throw new Error('请选择有效的创建日期') }
    const year = new Date(instant).getUTCFullYear()
    if (year < 1000 || year > 9999) throw new Error('创建日期转换后的 UTC 边界须位于 1000～9999 年')
    return instant
  }
  const range = { ...(fromDate ? { from: bound(fromDate) } : {}), ...(toDate ? { to: bound(toDate) } : {}) }
  if (range.from && range.to && range.from >= range.to) throw new Error('结束日期须晚于开始日期，结束日不含在范围内')
  return range
}

const label = (labels: Record<string, string>, value: string) => Object.hasOwn(labels, value) ? labels[value]! : '未知'
const toolLabels = {
  planner: '规划下一步', search_personal_materials: '检索本人已有卡片', prepare_source: '准备来源',
  extract_pdf_text: '提取PDF文字', recognize_image: '识别图片文字', generate_summary_cards: '生成候选内容', validate_candidate: '核验候选',
}
export const adminTaskStatuses = statusLabels
export const adminGoalLabel = (value: string) => label(goalLabels, value)
export const adminStatusLabel = (value: string) => label(statusLabels, value)
const adminStageLabels = { ...stageLabels, SOURCE_RESTRICTED: '原资料受限' }
export const adminStageLabel = (value: string | null) => value === null ? '未记录' : label(adminStageLabels, value)
export const adminToolLabel = (value: string) => label(toolLabels, value)
export const adminProviderLabel = (value: string) => label({ LOCAL: '本地', MODEL: '模型', OCR: 'OCR' }, value)
export const adminStepStatusLabel = (value: string) => label(stepStatusLabels, value)
// Mirrors AdminTaskService.ERROR_CODES; arbitrary historical strings remain private.
const errorCodes = new Set([
  'AGENT_ACTION_NOT_ALLOWED', 'AGENT_PLAN_INVALID', 'AGENT_PLANNER_LIMIT', 'AGENT_RELATED_CHANGED',
  'AGENT_SEARCH_NOT_ALLOWED', 'AGENT_STOP_SOURCE_UNREADABLE', 'AGENT_STOP_NO_USABLE_SOURCE',
  'AGENT_STOP_UNSUPPORTED_REQUEST', 'EXTERNAL_PROCESSING_CONSENT_REQUIRED', 'INVALID_CANDIDATE',
  'INVALID_PAGE_RANGE', 'INVALID_TASK_CONFIG', 'INVALID_TOOL_ARGUMENTS', 'LEASE_LOST', 'LEASE_RECOVERED',
  'MODEL_ATTEMPT_LIMIT', 'MODEL_AUTH_FAILED', 'MODEL_CONFIG_CHANGED', 'MODEL_CONFIG_INVALID',
  'MODEL_CONNECTION_FAILED', 'MODEL_CONTENT_FILTERED', 'MODEL_CREDENTIALS_INVALID', 'MODEL_EMPTY_RESULT',
  'MODEL_INPUT_LIMIT', 'MODEL_INTERRUPTED', 'MODEL_INVALID_INPUT', 'MODEL_INVALID_RESPONSE',
  'MODEL_NOT_CONFIGURED', 'MODEL_OUTPUT_INVALID', 'MODEL_OUTPUT_TRUNCATED', 'MODEL_PROVIDER_UNAVAILABLE',
  'MODEL_PUBLICATION_INCOMPLETE', 'MODEL_QUOTA_EXCEEDED', 'MODEL_RATE_LIMITED', 'MODEL_REDIRECT_REJECTED',
  'MODEL_REQUEST_FAILED', 'MODEL_REQUEST_REJECTED', 'MODEL_SOURCE_TEXT_REQUIRED', 'MODEL_TEXT_LIMIT_EXCEEDED',
  'MODEL_TIMEOUT', 'OCR_ATTEMPT_LIMIT', 'OCR_AUTH_FAILED', 'OCR_CACHE_CONFLICT', 'OCR_CONNECTION_FAILED',
  'OCR_CREDENTIALS_INVALID', 'OCR_EMPTY_RESULT', 'OCR_FILE_UNAVAILABLE', 'OCR_IMAGE_INVALID',
  'OCR_IMAGE_TOO_LARGE', 'OCR_INTERRUPTED', 'OCR_INVALID_INPUT', 'OCR_INVALID_RESPONSE', 'OCR_NOT_CONFIGURED',
  'OCR_PROVIDER_FAILED', 'OCR_PROVIDER_INVALID', 'OCR_PROVIDER_UNAVAILABLE', 'OCR_PUBLICATION_INCOMPLETE',
  'OCR_RATE_LIMITED', 'OCR_REDIRECT_REJECTED', 'OCR_REQUEST_FAILED', 'OCR_RESPONSE_INVALID',
  'OCR_SERVICE_NOT_ENABLED', 'OCR_TEXT_LIMIT_EXCEEDED', 'OCR_TIMEOUT', 'PDF_CACHE_CONFLICT', 'PDF_ENCRYPTED',
  'PDF_EXTRACTION_FORBIDDEN', 'PDF_FILE_UNAVAILABLE', 'PDF_OCR_REQUIRED', 'PDF_READ_FAILED',
  'PDF_TEXT_LIMIT_EXCEEDED', 'PROCESSOR_INCOMPLETE', 'PROCESSOR_MODE_CHANGED', 'PROCESSOR_NOT_CONFIGURED',
  'SOURCE_CHANGED', 'SOURCE_CHUNK_CONFLICT', 'SOURCE_DELETED', 'SOURCE_RESTRICTED', 'SOURCE_TEXT_NOT_AVAILABLE',
  'SOURCE_UNAVAILABLE', 'SOURCE_VERSION_CHANGED', 'STEP_LIMIT', 'TASK_CANCELLED', 'TASK_EXECUTION_FAILED',
  'TASK_TIMEOUT', 'UNSUPPORTED_SOURCE_TYPE',
])
export function adminErrorLabel(value: string | null) {
  if (value === null) return '无错误'
  if (!errorCodes.has(value)) return 'UNRECOGNIZED_ERROR · 未识别错误'
  return `${value} · ${taskErrorMessage(value) || '执行异常'}`
}

export function formatAdminTime(value: string | null) {
  return value === null ? '未记录' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))
}

export function formatAdminDuration(item: Pick<AdminTask, 'durationMs' | 'startedAt' | 'finishedAt'>) {
  const duration = item.durationMs
  if (duration === null) return !item.startedAt ? '未开始' : !item.finishedAt ? '尚未结束' : '用时未知'
  if (duration < 1000) return `${duration} 毫秒`
  if (duration < 60_000) return `${duration / 1000} 秒`
  if (duration < 3_600_000) return `${Math.floor(duration / 60_000)} 分 ${duration % 60_000 / 1000} 秒`
  return `${Math.floor(duration / 3_600_000)} 小时 ${Math.floor(duration % 3_600_000 / 60_000)} 分 ${duration % 60_000 / 1000} 秒`
}
