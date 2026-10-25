export interface KnowledgeCard {
  id: string
  title: string
  content: string
  summary: string | null
  tags: string[]
  subject: string | null
  parentCardId: string | null
  version: number
  origin: 'AI' | 'MANUAL'
  generationMode: string
  isEdited: boolean
  createdAt: string
  updatedAt: string
}

export interface CardSource {
  chunkId: string
  materialId: string
  materialTitle: string
  materialVersion: number
  pageNo: number | null
  extractionMethod?: 'TEXT' | 'METADATA' | 'PDF_TEXT' | 'OCR'
  qualityNote?: string | null
  quoteText: string | null
  content: string | null
  isAvailable: boolean
  unavailableReason: string | null
}

export const sourceUnavailableLabels: Record<string, string> = {
  SOURCE_DELETED: '原资料已删除，来源内容不可访问',
  SOURCE_RESTRICTED: '原资料暂时受限，来源内容不可访问',
}
