import type { KnowledgeCard } from './knowledge'

export type RelationType = 'RELATED' | 'PREREQUISITE' | 'EXAMPLE'
export interface CardRelation {
  id: string
  sourceCardId: string
  targetCardId: string
  type: RelationType
  evidenceText: string
}
export interface KnowledgeGraph { nodes: KnowledgeCard[]; edges: CardRelation[]; hasMore: boolean }
export interface GraphFilters { subject: string; rootCardId: string }
export interface RelationForm { sourceCardId: string; targetCardId: string; type: RelationType; evidenceText: string }

export const relationTypeLabels: Record<RelationType, string> = {
  RELATED: '相关', PREREQUISITE: '前置知识', EXAMPLE: '示例',
}

export function relationDescription(type: RelationType, source: string, target: string) {
  if (type === 'RELATED') return `${source} ↔ ${target}（无向关联）`
  if (type === 'PREREQUISITE') return `${source} → ${target}：${source} 是 ${target} 的前置知识`
  return `${source} → ${target}：${target} 是 ${source} 的示例`
}

export function cardId(value: string) {
  const id = value.trim()
  if (!/^[1-9]\d*$/.test(id) || BigInt(id) > 9223372036854775807n) throw new Error('卡片 ID 须为有效的正整数')
  return id
}

export function graphPath(filters: GraphFilters) {
  const subject = filters.subject.trim(), rootCardId = filters.rootCardId.trim() ? cardId(filters.rootCardId) : ''
  if (subject.length > 64) throw new Error('学科最多 64 字')
  const query = new URLSearchParams()
  if (subject) query.set('subject', subject)
  if (rootCardId) query.set('rootCardId', rootCardId)
  return `/knowledge-graphs${query.size ? `?${query}` : ''}`
}

export function relationPayload(form: RelationForm, source: KnowledgeCard | null, target: KnowledgeCard | null) {
  const sourceCardId = cardId(form.sourceCardId), targetCardId = cardId(form.targetCardId)
  if (sourceCardId === targetCardId) throw new Error('不能将卡片与自身关联')
  if (!source || !target) throw new Error('请先明确读取并核对两端卡片及版本')
  if (source.id !== sourceCardId || target.id !== targetCardId) throw new Error('卡片 ID 已变化，请重新读取两端卡片')
  if (!Object.hasOwn(relationTypeLabels, form.type)) throw new Error('请选择关系类型')
  const evidenceText = form.evidenceText.trim()
  if (!evidenceText || evidenceText.length > 2000) throw new Error('请填写关系说明，最多 2000 字')
  return { sourceCardId, sourceCardVersion: source.version, targetCardId, targetCardVersion: target.version,
    type: form.type, evidenceText }
}

export function graphLayout(graph: KnowledgeGraph) {
  const columns = Math.max(1, Math.min(5, Math.ceil(Math.sqrt(graph.nodes.length))))
  const nodes = graph.nodes.map((card, index) => ({ ...card, x: 40 + (index % columns) * 240,
    y: 40 + Math.floor(index / columns) * 180 }))
  const byId = new Map(nodes.map(node => [node.id, node]))
  const edges = graph.edges.map(edge => {
    const source = byId.get(edge.sourceCardId)!, target = byId.get(edge.targetCardId)!
    const downward = target.y !== source.y
    const forward = downward ? target.y > source.y : target.x > source.x
    const x1 = source.x + (downward ? 95 : forward ? 190 : 0), y1 = source.y + (downward ? forward ? 80 : 0 : 40)
    const x2 = target.x + (downward ? 95 : forward ? 0 : 190), y2 = target.y + (downward ? forward ? 0 : 80 : 40)
    const bend = (edge.type === 'RELATED' ? 0 : edge.type === 'PREREQUISITE' ? 18 : -18)
    const d = downward ? `M ${x1} ${y1} C ${x1 + bend} ${(y1 + y2) / 2}, ${x2 + bend} ${(y1 + y2) / 2}, ${x2} ${y2}`
      : `M ${x1} ${y1} C ${(x1 + x2) / 2} ${y1 + bend}, ${(x1 + x2) / 2} ${y2 + bend}, ${x2} ${y2}`
    return { ...edge, d }
  })
  return { nodes, edges, width: Math.max(330, columns * 240 + 30), height: Math.max(160, Math.ceil(nodes.length / columns) * 180) }
}
