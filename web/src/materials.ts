export interface Tag {
  id: string
  name: string
}

export interface Material {
  id: string
  title: string
  subject: string | null
  type: 'NOTE' | 'PDF' | 'IMAGE'
  summary: string | null
  noteText: string | null
  tags: Tag[]
  version: number
  status: 'ACTIVE' | 'ARCHIVED' | 'DELETED'
  accessState: 'NORMAL' | 'RESTRICTED'
  fileSize: number | null
  pageCount: number | null
  favorite: boolean
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
}
