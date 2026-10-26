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
  favorite: boolean
  createdAt: string
  updatedAt: string
}

export interface CardForm {
  title: string
  content: string
  summary: string
  subject: string
  tagsText: string
}
export interface CardFilters { keyword: string; subject: string; tag: string; favoriteOnly: boolean }

export function cardForm(card?: KnowledgeCard): CardForm {
  return { title: card?.title || '', content: card?.content || '', summary: card?.summary || '',
    subject: card?.subject || '', tagsText: card?.tags.join('，') || '' }
}

export function cardPayload(form: CardForm, originalTags?: string[]) {
  const title = form.title.trim(), subject = form.subject.trim(), summary = form.summary.trim()
  const tags = originalTags && form.tagsText === originalTags.join('，')
    ? [...originalTags] : form.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (!title || !form.content.trim()) throw new Error('请填写标题和正文')
  if (title.length > 255) throw new Error('标题最多 255 字')
  if (form.content.length > 8000) throw new Error('正文最多 8000 字')
  if (summary.length > 2000) throw new Error('摘要最多 2000 字')
  if (subject.length > 64) throw new Error('学科最多 64 字')
  if (tags.length > 10) throw new Error('最多添加 10 个标签')
  if (tags.some(tag => tag.length > 64)) throw new Error('每个标签最多 64 字')
  if (new Set(tags.map(tag => tag.toLowerCase())).size !== tags.length) throw new Error('标签不能重复')
  return { title, content: form.content, summary, subject, tags }
}

export function cardDraftKey(userId: string, id = 'new') {
  return `lms.cardDraft.v1.${encodeURIComponent(userId)}.${encodeURIComponent(id)}`
}

export function readCardDraft(raw: string | null, editing: boolean): { version: number; form: CardForm } | null {
  if (!raw) return null
  try {
    const value = JSON.parse(raw)
    if (!value || !Number.isInteger(value.version) || (editing ? value.version < 1 : value.version !== 0)
        || !value.form || !['title', 'content', 'summary', 'subject', 'tagsText'].every(key => typeof value.form[key] === 'string')) return null
    return { version: value.version, form: { title: value.form.title, content: value.form.content,
      summary: value.form.summary, subject: value.form.subject, tagsText: value.form.tagsText } }
  } catch { return null }
}

export function cardListPath(filters: CardFilters, page = 1) {
  const keyword = filters.keyword.trim(), subject = filters.subject.trim(), tag = filters.tag.trim()
  if (keyword.length > 100 || subject.length > 64 || tag.length > 64) throw new Error('关键词最多 100 字，学科和标签最多 64 字')
  return `/cards?${new URLSearchParams({ keyword, subject, tag, favoriteOnly: String(filters.favoriteOnly), page: String(page), pageSize: '20' })}`
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
