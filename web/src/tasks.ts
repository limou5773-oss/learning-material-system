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

export interface GenerationInfo { provider: string; model: string; promptVersion: string }

export interface CandidateResult {
  taskId: string
  resultVersion: number
  sourceVersion: number
  generationMode: 'MOCK' | 'MODEL'
  generationInfo?: GenerationInfo | null
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

export const goalDescriptions: Record<GoalType, string> = {
  REVIEW_CARDS: '围绕所选资料生成便于复习的知识卡片，并保留原文引用供核对。',
  EXTRACT_KEY_POINTS: '提炼所选课件的核心重点，按知识点整理为候选卡片。',
  LINK_NOTES: '整理所选资料；允许检索时可参考本人已有知识卡片提出关联建议，逐条确认后才保存关联。',
}

export const relatedSearchDescription = '允许检索本人已有知识卡片；模型启用时，将召回的相关片段发送给模型用于关联建议。不开启则不检索。'

export function toolLabel(name: string) {
  const labels: Record<string, string> = {
    planner: '规划下一步', search_personal_materials: '检索本人已有卡片', prepare_source: '准备来源',
    extract_pdf_text: '提取PDF文字', recognize_image: '识别图片文字',
    generate_summary_cards: '生成候选内容', validate_candidate: '核验候选',
  }
  return labels[name] || name
}

export function relationEndpointLabel(endpoint: RelationEndpoint, cards: Pick<CandidateCard, 'candidateKey' | 'title'>[]) {
  return endpoint.candidateKey ? cards.find(card => card.candidateKey === endpoint.candidateKey)?.title || endpoint.candidateKey
    : `已有卡片 #${endpoint.cardId}`
}

export const statusLabels: Record<TaskStatus, string> = {
  QUEUED: '排队中', RUNNING: '执行中', WAIT_CONFIRM: '待确认',
  SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}

export const stageLabels: Record<string, string> = {
  QUEUED: '等待领取', STARTING: '开始处理', FAILED: '处理失败',
  CANCELLED: '用户取消', SOURCE_DELETED: '原资料已删除',
  PREPARE_SOURCE: '准备来源', GENERATE_SUMMARY_CARDS: '生成候选内容',
  EXTRACT_PDF_TEXT: '提取PDF文字',
  PLANNER: '规划下一步', SEARCH_PERSONAL_MATERIALS: '检索本人已有卡片',
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
    AGENT_PLAN_INVALID: '规划响应格式、工具或参数不符合约定，任务已停止，未发布候选。',
    AGENT_ACTION_NOT_ALLOWED: '规划动作不符合当前处理状态，任务已停止，未发布候选。',
    AGENT_SEARCH_NOT_ALLOWED: '本任务未允许检索本人已有卡片，检索动作已拒绝，未发布候选。',
    AGENT_PLANNER_LIMIT: '本任务已达到规划请求上限，未发布候选；请查看执行记录后重新提交。',
    STEP_LIMIT: '本任务已达到执行步骤上限，未发布候选；请查看执行记录或缩小资料范围后重新提交。',
    AGENT_STOP_SOURCE_UNREADABLE: '规划已明确停止：来源无法读取，未发布候选；请检查原资料后重新提交。',
    AGENT_STOP_NO_USABLE_SOURCE: '规划已明确停止：没有可用正文，未发布候选；请检查文字层或OCR识别结果。',
    AGENT_STOP_UNSUPPORTED_REQUEST: '规划已明确停止：当前支持的工具无法完成此请求，未发布候选；请调整整理目标或补充说明。',
    MODEL_CONFIG_INVALID: '模型配置无效，请检查后端厂商与模型名称配置。',
    MODEL_INPUT_LIMIT: '所选文字超过模型处理上限，请缩小页段或拆分笔记后重试。',
    MODEL_INVALID_INPUT: '模型输入无效，请检查所选文字来源后重新整理。',
    MODEL_INTERRUPTED: '模型处理已中断，请查看任务状态与执行记录后重试。',
    MODEL_QUOTA_EXCEEDED: '模型账户额度不足，请检查厂商账户额度后重试。',
    MODEL_REQUEST_REJECTED: '模型服务拒绝请求，请检查后端模型配置及调用权限。',
    MODEL_REDIRECT_REJECTED: '模型服务返回了不允许的跳转，请检查后端服务配置。',
    MODEL_OUTPUT_TRUNCATED: '模型输出不完整，未发布候选；请缩小输入范围后重试。',
    MODEL_CONTENT_FILTERED: '模型服务未返回可用内容，未发布候选；请调整所选内容后重试。',
    MODEL_EMPTY_RESULT: '模型没有返回可用内容，未发布候选；请检查来源后重试。',
    MODEL_NOT_CONFIGURED: '模型尚未配置，请在后端本机配置厂商、模型与密钥后重试。',
    MODEL_CREDENTIALS_INVALID: '模型凭证格式无效，请检查后端本机配置，勿在页面提交密钥。',
    MODEL_AUTH_FAILED: '模型鉴权失败，请检查后端密钥及调用权限。',
    MODEL_TIMEOUT: '模型请求超时，费用状态可能未知；请查看执行记录后重试。',
    MODEL_RATE_LIMITED: '模型请求被限流，请稍后重试。',
    MODEL_CONNECTION_FAILED: '无法连接模型服务，请检查后端网络后重试。',
    MODEL_PROVIDER_UNAVAILABLE: '模型服务暂不可用，请稍后重试。',
    MODEL_INVALID_RESPONSE: '模型响应格式无效，未发布候选内容。',
    MODEL_OUTPUT_INVALID: '模型输出格式或来源引用校验失败，未发布候选；请核对所选来源后重新整理。',
    MODEL_SOURCE_INVALID: '模型引用与已选来源不匹配，未发布候选；请核对来源后重新整理。',
    MODEL_TEXT_LIMIT_EXCEEDED: '所选文字超过模型处理上限，请缩小页段或拆分笔记后重试。',
    MODEL_SOURCE_TEXT_REQUIRED: '所选资料没有可用正文，请先配置OCR或换用有文字的来源。',
    MODEL_SOURCE_REQUIRED: '所选资料没有可用正文，请先配置OCR或换用有文字的来源。',
    MODEL_ATTEMPT_LIMIT: '本任务已达到模型请求尝试上限，请查看执行记录后重新提交。',
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
  return messages[code] || (code && code.startsWith('AGENT_') ? '智能体处理失败，未发布候选；请查看执行记录及失败步骤后重新提交。'
    : code && code.startsWith('MODEL_') ? '模型处理失败，未发布候选；请查看执行记录并检查后端配置后重试。' : '')
}

export const externalProcessingConsent = '我同意所选资料的文字、OCR识别文字及补充说明，以及选择允许检索时召回的本人已有知识卡片相关片段，在模型启用时发送给已配置的模型服务，并在OCR启用时将所选图片或页面图像发送给已配置的OCR服务。处理模式由服务端配置决定。'
export function generationPresentation(mode?: string | null, info?: GenerationInfo | null) {
  if (mode === 'MODEL') return {
    label: '模型生成',
    modelLabel: info ? `${info.provider} · ${info.model} · 提示版本 ${info.promptVersion}` : '模型标识暂不可用',
    warning: '模型生成内容可能有误，须人工核对原文及OCR质量；引用能定位原文，不代表足以支持卡片结论。确认后才会保存。',
  }
  if (mode === 'MOCK') return {
    label: '模拟生成', modelLabel: '',
    warning: '当前为模拟生成结果，须人工核对固定版本来源与OCR质量；标题元数据不代表文件正文。确认后才会保存。',
  }
  return { label: '生成方式待核实', modelLabel: '', warning: '请人工核对固定版本来源与OCR质量后再确认保存。' }
}
export function savedGenerationLabel(mode?: string | null, isEdited = false) {
  if (mode === 'MANUAL') return isEdited ? '人工创建 · 已人工修改' : '人工创建'
  if (isEdited && mode === 'MODEL') return '模型生成 · 已人工修改'
  if (isEdited && mode === 'MOCK') return '模拟候选 · 已人工修改'
  if (mode === 'MODEL') return '模型生成 · 人工确认'
  if (mode === 'MOCK') return '模拟候选 · 人工确认'
  return '人工确认保存'
}
export function savedGenerationWarning(mode?: string | null, isEdited = false) {
  if (mode === 'MANUAL') return '本卡片为人工创建，无自动来源。请自行核对正文与依据。'
  if (isEdited && mode === 'MODEL') return '本卡片由模型生成后经人工修改；来源为创建时的历史依据，请重新核对其与当前正文的对应关系及OCR质量。'
  if (isEdited && mode === 'MOCK') return '本卡片最初为模拟候选，保存后经人工修改；来源为创建时的历史依据，请重新核对其与当前正文的对应关系及OCR质量。'
  if (mode === 'MODEL') return '本卡片由模型生成并经人工确认保存。仍须人工核对固定版本来源及OCR质量；引用能定位原文，不代表足以支持卡片结论。'
  if (mode === 'MOCK') return '本卡片经人工确认保存，生成内容仍为模拟结果。请核对固定版本来源与OCR质量；标题元数据不代表文件正文。'
  return '请核对固定版本来源与OCR质量。'
}
