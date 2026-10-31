import type { Material } from './materials'

export interface ContentReview {
  id: string
  materialId: string
  materialVersion: number
  ownerId: string
  title: string
  type: Material['type']
  accessScope: 'MATERIAL_CONTENT'
  triggerType: 'USER_SUBMITTED'
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'
  version: number
  reason: string
  decisionReason: string | null
  isCurrent: boolean
  createdAt: string
  supersededAt: string | null
}

export interface ReviewContent {
  reviewId: string
  material: Material
  fileAccessPath: string | null
  auditId: string
}

export const reviewStatus = (value: ContentReview['status']) => ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', SUPERSEDED: '已失效，需重新送审' })[value]
