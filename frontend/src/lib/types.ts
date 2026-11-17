export type Screen =
  | 'write'
  | 'journals'
  | 'calendar'
  | 'history'
  | 'library'
  | 'shares'
  | 'data'
  | 'trash'
  | 'settings'
  | 'admin'

export interface User {
  hasAvatar?: boolean
  avatarRevision?: number
  id: number
  email: string
  username: string
  nickname: string
  role: 'USER' | 'ADMIN'
  timeZone: string
  theme: 'light' | 'dark'
  accent: 'olive' | 'blue' | 'rose'
}

export interface Tag {
  id: number
  name: string
  color: string
  icon: string
  style?: 'soft' | 'outline' | 'solid'
  approvedIconMediaId?: number | null
  iconStatus?: 'BUILT_IN' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'
  iconReason?: string | null
}

export interface Notebook {
  id: number
  name: string
  coverUrl?: string
  isDefault: boolean
  entryCount: number
}

export interface Journal {
  id: number
  journalDate: string
  title: string
  automaticTitle: boolean
  content: Record<string, unknown>
  plainText: string
  version: number
  notebookId: number
  notebookName?: string
  tags: Tag[]
  createdAt: string
  updatedAt: string
  deletedAt?: string | null
}

export interface JournalWrite {
  journalDate: string
  title: string
  automaticTitle: boolean
  content: Record<string, unknown>
  plainText: string
  notebookId: number
  tagIds: number[]
  expectedVersion?: number
  clientId?: string
  operationId?: string
  activityDates?: string[]
}

export interface WritingDay {
  date: string
  count: number
}

export interface ShareRequest {
  id: number
  journalId: number
  journalTitle: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED' | 'EXPIRED' | 'SUPERSEDED'
  submittedAt: string
  expiresAt?: string
  shareUrl?: string
  reviewReason?: string
}

export interface ShareSnapshot {
  id: number
  journalTitle: string
  journalDate: string
  versionNumber: number
  submittedAt: string
  content: Record<string, unknown>
  plainText: string
  imageUrls: string[]
}
