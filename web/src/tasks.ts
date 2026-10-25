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

export interface CandidateSourceRef {
  chunkId: string
  quoteText: string
  startOffset: number
  endOffset: number
}

export interface CandidateResult {
  taskId: string
  resultVersion: number
  sourceVersion: number
  generationMode: 'MOCK'
  summary: string
  tags: string[]
  cards: CandidateCard[]
  relations: CandidateRelation[]
  totalPages: number | null
  processedPages: number[]
  warnings: string[]
}

export interface CandidateCard {
  candidateKey: string
  title: string
  content: string
  summary?: string | null
  subject?: string | null
  tags?: string[]
  sources: CandidateSourceRef[]
  parentCandidateKey?: string | null
}

export interface RelationEndpoint { candidateKey?: string; cardId?: string; cardVersion?: number }
export interface CandidateRelation {
  relationKey: string
  source: RelationEndpoint
  target: RelationEndpoint
  type: 'RELATED' | 'PREREQUISITE' | 'EXAMPLE'
  evidenceText: string
}

export interface ConfirmationInput {
  resultVersion: number
  sourceVersion: number
  summary: string
  tags: string[]
  tagMode: 'MERGE' | 'REPLACE'
  cards: CandidateCard[]
  relations: CandidateRelation[]
}

export interface ConfirmationResult {
  taskId: string
  status: 'SUCCEEDED'
  cardIds: string[]
  resultingMaterialVersion: number
  confirmedAt: string
}

export interface CandidateSource {
  chunkId: string
  materialId: string
  materialVersion: number
  chunkNo: number
  pageNo: number | null
  extractionMethod: 'TEXT' | 'METADATA' | 'PDF_TEXT' | 'OCR'
  content: string
  qualityNote: string | null
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
  PREPARE_SOURCE: '准备来源', GENERATE_SUMMARY_CARDS: '生成模拟候选',
  EXTRACT_PDF_TEXT: '提取PDF文字',
  RECOGNIZE_IMAGE: '识别图片文字',
  VALIDATE_CANDIDATE: '核验候选', CANDIDATE_READY: '等待人工确认',
  CONFIRMED: '人工确认完成',
}

export const stepStatusLabels: Record<TaskStep['status'], string> = {
  RUNNING: '进行中', SUCCESS: '成功', FAILED: '失败', UNKNOWN: '结果未知',
}

export function sourceLocator(source?: Partial<Pick<CandidateSource, 'extractionMethod' | 'pageNo' | 'chunkNo'>>) {
  if (!source) return '来源暂不可用'
  if (source.extractionMethod === 'METADATA') return '资料标题元数据，正文未提取'
  if (source.extractionMethod === 'PDF_TEXT') return source.pageNo ? `PDF 第 ${source.pageNo} 页` : 'PDF文字片段'
  if (source.extractionMethod === 'OCR') return source.pageNo ? `PDF 第 ${source.pageNo} 页 · OCR 识别文字` : '图片 OCR 识别文字'
  return `文字笔记片段 ${source.chunkNo || ''}`.trim()
}

export function taskErrorMessage(code: string) {
  const messages: Record<string, string> = {
    PDF_OCR_REQUIRED: '所选页段没有可用文字层，可能需要OCR或为空白页；当前未启用OCR，请配置OCR或选择有文字的页段。',
    OCR_NOT_CONFIGURED: 'OCR尚未配置，请在后端本机配置厂商与密钥后重试。',
    OCR_CREDENTIALS_INVALID: 'OCR凭证格式无效，请检查本机配置，勿在页面提交密钥。',
    OCR_AUTH_FAILED: 'OCR鉴权失败，请检查密钥及OCR调用权限。',
    OCR_SERVICE_NOT_ENABLED: '腾讯云OCR服务尚未开通，请在厂商控制台开通后重试。',
    OCR_EMPTY_RESULT: 'OCR未识别到可用文字，请换用更清晰的图片或调整页段。',
    OCR_TIMEOUT: 'OCR请求超时，费用状态未知；请查看记录后重试。',
    OCR_RATE_LIMITED: 'OCR请求被限流，请稍后重试。',
    OCR_ATTEMPT_LIMIT: '本任务已达到OCR请求尝试上限，请缩小页段或重新提交。',
    OCR_CACHE_CONFLICT: '当前资料版本的OCR缓存与配置不一致，请更新资料版本或重新上传。',
    OCR_TEXT_LIMIT_EXCEEDED: '识别文字超过处理上限，请缩小页段或分开上传。',
    OCR_IMAGE_TOO_LARGE: '图片超过OCR处理上限，请裁剪或降低图片尺寸后重试。',
    OCR_IMAGE_INVALID: '图片无法供OCR读取，请重新上传有效图片。',
    OCR_FILE_UNAVAILABLE: 'OCR来源文件暂时无法读取，请检查或重新上传。',
    OCR_CONNECTION_FAILED: '无法连接OCR服务，请检查后端网络后重试。',
    OCR_INVALID_RESPONSE: 'OCR服务返回格式无效，未保存识别内容。',
    OCR_RESPONSE_INVALID: 'OCR结果校验失败，未保存识别内容。',
    OCR_PROVIDER_UNAVAILABLE: 'OCR服务暂不可用，请稍后重试。',
    PDF_TEXT_LIMIT_EXCEEDED: '所选PDF文字超过本次处理上限，请缩小页段后重试。',
    PDF_EXTRACTION_FAILED: 'PDF文字提取失败，请检查文件是否损坏或换用可读取的PDF。',
    PDF_READ_FAILED: 'PDF读取或文字提取失败，请检查文件是否损坏或换用可读取的PDF。',
    PDF_FILE_UNAVAILABLE: 'PDF文件暂时无法读取，请重新上传后重试。',
    PDF_ENCRYPTED: '暂不支持加密PDF，请上传可直接读取的文件。',
    PDF_EXTRACTION_FORBIDDEN: '文件未授予文字提取权限，请换用允许提取文字的PDF。',
  }
  return messages[code] || ''
}
