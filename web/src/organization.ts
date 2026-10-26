import type { CardFilters, KnowledgeCard } from './knowledge'
import type { Page } from './materials'

export interface LayoutItem { cardId: string; x: number; y: number; orderNo: number }
export interface CardLayout { layoutKey: string; layoutVersion: number; items: LayoutItem[] }

export async function loadOrganizationRange(read: (path: string) => Promise<Page<KnowledgeCard>>, filters: CardFilters) {
  const keyword = filters.keyword.trim(), subject = filters.subject.trim(), tag = filters.tag.trim()
  if (keyword.length > 100 || subject.length > 64 || tag.length > 64) throw new Error('关键词最多 100 字，学科和标签最多 64 字')
  const path = (page: number) => `/cards?${new URLSearchParams({ keyword, subject, tag,
    favoriteOnly: String(filters.favoriteOnly), page: String(page), pageSize: '100' })}`
  const first = await read(path(1))
  if (first.total > 200) throw new Error(`当前范围有 ${first.total} 张卡片，最多支持 200 张，请缩小筛选范围。`)
  const second = first.total > 100 ? await read(path(2)) : null
  if (second && second.total > 200) throw new Error(`当前范围超过 200 张，请缩小筛选范围。`)
  const cards = [...first.items, ...(second?.items || [])]
  if ((second && second.total !== first.total) || cards.length !== first.total || new Set(cards.map(card => card.id)).size !== cards.length) {
    throw new Error('读取期间卡片范围已变化，请重新读取。')
  }
  return cards
}

export function prepareLayout(cards: KnowledgeCard[], saved: LayoutItem[]) {
  const ids = new Set(cards.map(card => card.id)), byId = new Map(saved.map(item => [item.cardId, item]))
  const usedOrders = new Set(saved.filter(item => ids.has(item.cardId)).map(item => item.orderNo))
  let nextOrder = 0
  const items = cards.map((card, index) => {
    const savedItem = byId.get(card.id)
    if (savedItem) return { ...savedItem }
    while (usedOrders.has(nextOrder)) nextOrder++
    const item = { cardId: card.id, x: (index % 3) * 280, y: Math.floor(index / 3) * 170, orderNo: nextOrder++ }
    return item
  })
  return { items, unloadedIds: saved.filter(item => !ids.has(item.cardId)).map(item => item.cardId) }
}

export function layoutPayload(layoutVersion: number, items: LayoutItem[]) {
  if (!Number.isInteger(layoutVersion) || layoutVersion < 0) throw new Error('布局版本无效，请重新读取。')
  if (items.length > 200) throw new Error('画布最多支持 200 张卡片')
  if (new Set(items.map(item => item.cardId)).size !== items.length) throw new Error('画布卡片不能重复')
  if (new Set(items.map(item => item.orderNo)).size !== items.length) throw new Error('卡片顺序不能重复')
  for (const item of items) {
    if (!item.cardId || !Number.isFinite(item.x) || !Number.isFinite(item.y)
        || Math.abs(item.x) > 100000 || Math.abs(item.y) > 100000) throw new Error('坐标必须为 -100000 到 100000 之间的有限数字')
    if (!Number.isInteger(item.orderNo) || item.orderNo < 0 || item.orderNo > 2147483647) throw new Error('顺序必须为 0 到 2147483647 之间的整数')
  }
  return { layoutVersion, items: items.map(item => ({ ...item })) }
}

export function canvasBounds(items: LayoutItem[]) {
  const offsetX = 24 - Math.min(0, ...items.map(item => item.x)), offsetY = 24 - Math.min(0, ...items.map(item => item.y))
  return { offsetX, offsetY, width: Math.max(840, ...items.map(item => item.x + offsetX + 284)),
    height: Math.max(480, ...items.map(item => item.y + offsetY + 184)) }
}

export function hierarchyGraph(cards: KnowledgeCard[]) {
  const byId = new Map(cards.map(card => [card.id, card]))
  const depth = (id: string, ancestors = new Set<string>()): number => {
    const card = byId.get(id)!
    if (!card.parentCardId || !byId.has(card.parentCardId) || ancestors.has(id)) return 0
    return 1 + depth(card.parentCardId, new Set([...ancestors, id]))
  }
  const rows = new Map<number, number>()
  const nodes = cards.map(card => {
    const level = depth(card.id), row = rows.get(level) || 0
    rows.set(level, row + 1)
    return { ...card, depth: level, x: 24 + level * 300, y: 24 + row * 150, isRoot: card.parentCardId === null,
      externalParentId: card.parentCardId && !byId.has(card.parentCardId) ? card.parentCardId : null }
  })
  const edges = cards.filter(card => card.parentCardId && byId.has(card.parentCardId)).map(card => ({
    parentId: card.parentCardId!, childId: card.id,
  }))
  return { nodes, edges, width: Math.max(840, ...nodes.map(node => node.x + 284)),
    height: Math.max(420, ...nodes.map(node => node.y + 144)) }
}
