import type { CandidateCard, CandidateRelation, CandidateResult, ConfirmationInput } from './tasks'

export interface DraftCard extends Omit<CandidateCard, 'tags'> {
  selected: boolean
  tagsText: string
}
export interface ConfirmationDraft {
  taskId: string
  resultVersion: number
  sourceVersion: number
  summary: string
  tagsText: string
  tagMode: 'MERGE' | 'REPLACE'
  cards: DraftCard[]
  relations: (CandidateRelation & { selected: boolean })[]
}

export function createConfirmationDraft(candidate: CandidateResult): ConfirmationDraft {
  return {
    taskId: candidate.taskId, resultVersion: candidate.resultVersion, sourceVersion: candidate.sourceVersion,
    summary: candidate.summary, tagsText: candidate.tags.join('，'), tagMode: 'MERGE',
    cards: candidate.cards.map(card => ({ ...card, selected: true, tagsText: (card.tags || []).join('，'),
      sources: card.sources.map(source => ({ ...source })) })),
    relations: candidate.relations.map(relation => ({ ...relation, source: { ...relation.source },
      target: { ...relation.target }, selected: false })),
  }
}

function tags(value: string): string[] {
  const names = value.split(/[,，]/).map(name => name.trim()).filter(Boolean)
  if (names.length > 10 || names.some(name => name.length > 64)) throw new Error('每组标签最多10个，每个不超过64字')
  if (new Set(names.map(name => name.toLowerCase())).size !== names.length) throw new Error('标签不能重复')
  return names
}

export function buildConfirmationInput(draft: ConfirmationDraft): ConfirmationInput {
  const selected = draft.cards.filter(card => card.selected)
  if (!selected.length) throw new Error('至少选择一张卡片')
  if (selected.length > 30) throw new Error('一次最多保存30张卡片')
  if (draft.summary.length > 4000) throw new Error('摘要不能超过4000字')
  const keys = new Set(selected.map(card => card.candidateKey))
  const cards = selected.map(card => {
    if (!card.title.trim() || !card.content.trim()) throw new Error('请填写选中卡片的标题和正文')
    if (card.title.length > 255 || card.content.length > 8000) throw new Error('卡片标题最多255字，正文最多8000字')
    if ((card.summary?.length || 0) > 2000 || (card.subject?.length || 0) > 64) throw new Error('请缩短卡片摘要或学科')
    return {
      candidateKey: card.candidateKey, title: card.title.trim(), content: card.content,
      summary: card.summary || null, subject: card.subject?.trim() || null, tags: tags(card.tagsText),
      sources: card.sources.map(source => ({ ...source })),
      parentCandidateKey: card.parentCandidateKey && keys.has(card.parentCandidateKey) ? card.parentCandidateKey : null,
    }
  })
  const relations = draft.relations.filter(relation => relation.selected).map(relation => {
    for (const endpoint of [relation.source, relation.target]) {
      if (endpoint.candidateKey && !keys.has(endpoint.candidateKey)) throw new Error('关联包含未选择的卡片，请取消该关联或重新选择卡片')
    }
    return { relationKey: relation.relationKey, source: { ...relation.source }, target: { ...relation.target },
      type: relation.type, evidenceText: relation.evidenceText }
  })
  return { resultVersion: draft.resultVersion, sourceVersion: draft.sourceVersion,
    summary: draft.summary, tags: tags(draft.tagsText), tagMode: draft.tagMode, cards, relations }
}

export function isConfirmationDraft(value: unknown): value is ConfirmationDraft {
  if (!value || typeof value !== 'object') return false
  const draft = value as ConfirmationDraft
  return typeof draft.taskId === 'string' && Number.isInteger(draft.resultVersion)
    && Number.isInteger(draft.sourceVersion) && typeof draft.summary === 'string'
    && typeof draft.tagsText === 'string' && ['MERGE', 'REPLACE'].includes(draft.tagMode)
    && Array.isArray(draft.cards) && draft.cards.length > 0 && draft.cards.length <= 30
    && draft.cards.every(card => card && typeof card.candidateKey === 'string' && typeof card.selected === 'boolean'
      && typeof card.title === 'string' && typeof card.content === 'string' && typeof card.tagsText === 'string'
      && Array.isArray(card.sources) && card.sources.every(source => source && typeof source.chunkId === 'string'
        && typeof source.quoteText === 'string' && Number.isInteger(source.startOffset) && Number.isInteger(source.endOffset)))
    && Array.isArray(draft.relations) && draft.relations.every(relation => relation
      && typeof relation.relationKey === 'string' && typeof relation.selected === 'boolean'
      && relation.source && relation.target && typeof relation.evidenceText === 'string')
}
