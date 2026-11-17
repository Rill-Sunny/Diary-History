import type { Journal, JournalWrite, Notebook, ShareRequest, ShareSnapshot, Tag, User, WritingDay } from './types'

export interface ExportEntry { id: number; journalDate: string; title: string; notebookId: number; excerpt: string }
export interface ImportResult { imported: number; skipped: number; message: string }
export interface RestoreResult {
  added: number
  skipped: number
  conflicts: number
  expired: number
  trashAdded: number
  expiredTitles: string[]
  sameNotebooks: string[]
  changedTags: string[]
}
export interface TagIconReview {
  id: number
  tagName: string
  submitter: string
  submittedAt: string
  status: string
  reason?: string
  imageUrl: string
}

export interface ReminderSettings {
  emailEnabled: boolean
  pushEnabled: boolean
  time: string
  type: 'DAILY' | 'WEEKDAYS' | 'CUSTOM'
  weekdays: number[]
}

export interface ReminderDelivery {
  localDate: string
  status: 'SENT' | 'SENDING' | 'SKIPPED' | 'FAILED' | 'PARTIAL' | 'UNKNOWN'
  message?: string
}

export interface ReminderStatus {
  settings: ReminderSettings
  mailConfigured: boolean
  pushConfiguration: 'configured' | 'unconfigured' | 'invalid'
  pushPublicKey?: string
  subscribedEndpoints: string[]
  lastEmail?: ReminderDelivery
  lastPush?: ReminderDelivery
}

export class ApiError extends Error {
  status: number
  details: unknown

  constructor(status: number, message: string, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
  }
}

function statusMessage(status: number) {
  switch (status) {
    case 401: return '登录状态已失效，请重新登录。'
    case 403: return '服务器拒绝了此操作（HTTP 403），请刷新页面后重试。'
    case 404: return '此功能或内容暂不可用（HTTP 404）。请刷新页面后重试，若仍然失败，请联系网站管理员。'
    case 502:
    case 503:
    case 504: return '暂时无法连接服务（HTTP ' + status + '），请稍后重试。'
    default: return status >= 500
      ? '服务暂时无法处理此请求（HTTP ' + status + '），请稍后重试。'
      : '操作未完成（HTTP ' + status + '），请稍后重试。'
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const method = (init.method ?? 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    // Spring Security's CSRF endpoint returns the request token expected in
    // the header; the cookie contains a different, raw token value.
    const csrf = await request<{ token: string }>('/api/auth/csrf')
    headers.set('X-XSRF-TOKEN', csrf.token)
  }
  let response: Response
  try {
    response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, '网络暂不可用，请联网后重试。')
  }
  if (response.status === 204) return undefined as T
  const data: unknown = await response.json().catch(() => undefined)
  if (!response.ok) {
    const message = data && typeof data === 'object' && 'message' in data && typeof data.message === 'string'
      ? data.message.trim()
      : ''
    throw new ApiError(response.status, message || statusMessage(response.status), data)
  }
  if (!data || typeof data !== 'object') {
    throw new ApiError(502, '服务返回的数据不完整，请刷新后重试。',
      { responseStatus: response.status })
  }
  return data as T
}

function accountQuery(ownerId?: number) {
  return ownerId === undefined ? '' : '?account=' + ownerId
}

export const api = {
  me: () => request<User>('/api/auth/me'),
  login: (identity: string, password: string) =>
    request<User>('/api/auth/login', { method: 'POST', body: JSON.stringify({ identity, password }) }),
  register: (email: string, username: string, nickname: string, password: string) =>
    request<{ message: string }>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, username, nickname, password }),
    }),
  verifyEmail: (token: string) =>
    request<{ message: string }>('/api/auth/verify-email', { method: 'POST', body: JSON.stringify({ token }) }),
  resendVerification: (email: string) =>
    request<{ message: string }>('/api/auth/resend-verification', { method: 'POST', body: JSON.stringify({ email }) }),
  verifyEmailChange: (token: string) =>
    request<{ message: string }>('/api/auth/verify-email-change', { method: 'POST', body: JSON.stringify({ token }) }),
  requestEmailChange: (email: string, password: string) =>
    request<{ message: string }>('/api/auth/change-email', { method: 'POST', body: JSON.stringify({ email, password }) }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<{ message: string }>('/api/auth/change-password', { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) }),
  forgotPassword: (email: string) =>
    request<{ message: string }>('/api/auth/forgot-password', { method: 'POST', body: JSON.stringify({ email }) }),
  resetPassword: (token: string, password: string) =>
    request<{ message: string }>('/api/auth/reset-password', { method: 'POST', body: JSON.stringify({ token, password }) }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  journals: (params: Record<string, string | number | undefined> = {}) => {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== '') search.set(key, String(value))
    }
    return request<Journal[]>('/api/journals' + (search.size ? '?' + search.toString() : ''))
  },
  journal: (id: number) => request<Journal>('/api/journals/' + id),
  saveJournal: (entry: JournalWrite, id?: number, ownerId?: number) =>
    request<Journal>((id ? '/api/journals/' + id : '/api/journals') + accountQuery(ownerId), {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(entry),
    }),
  saveConflictCopy: (entry: JournalWrite, ownerId?: number) => request<Journal>('/api/journals/conflict-copy' + accountQuery(ownerId), {
    method: 'POST', body: JSON.stringify(entry),
  }),
  saveAndTrash: (entry: JournalWrite, id: number | null, ownerId: number) =>
    request<void>('/api/journals/save-and-trash?account=' + ownerId + (id === null ? '' : '&id=' + id), {
      method: 'POST', body: JSON.stringify(entry),
    }),
  trashJournal: (id: number, version?: number, ownerId?: number) => {
    const search = new URLSearchParams()
    if (version !== undefined) search.set('version', String(version))
    if (ownerId !== undefined) search.set('account', String(ownerId))
    return request<void>('/api/journals/' + id + (search.size ? '?' + search.toString() : ''), { method: 'DELETE' })
  },
  restoreJournal: (id: number) => request<Journal>('/api/journals/' + id + '/restore', { method: 'POST' }),
  deleteJournal: (id: number) => request<void>('/api/journals/' + id + '/permanent', { method: 'DELETE' }),
  notebooks: () => request<Notebook[]>('/api/library/notebooks'),
  createNotebook: (name: string) =>
    request<Notebook>('/api/library/notebooks', { method: 'POST', body: JSON.stringify({ name }) }),
  deleteNotebook: (id: number) => request<void>('/api/library/notebooks/' + id, { method: 'DELETE' }),
  setNotebookCover: (id: number, file: File) => {
    const body = new FormData()
    body.set('file', file)
    return request<void>('/api/library/notebooks/' + id + '/cover', { method: 'POST', body })
  },
  clearNotebookCover: (id: number) => request<void>('/api/library/notebooks/' + id + '/cover', { method: 'DELETE' }),
  tags: () => request<Tag[]>('/api/library/tags'),
  createTag: (name: string, color: string, icon: string, style: string) =>
    request<Tag>('/api/library/tags', { method: 'POST', body: JSON.stringify({ name, color, icon, style }) }),
  submitTagIcon: (id: number, file: File) => {
    const body = new FormData()
    body.set('file', file)
    return request<void>('/api/library/tags/' + id + '/icon', { method: 'POST', body })
  },
  deleteTag: (id: number) => request<void>('/api/library/tags/' + id, { method: 'DELETE' }),
  activity: () => request<WritingDay[]>('/api/stats/activity'),
  coverage: (from: string, to: string) => request<WritingDay[]>('/api/stats/coverage?' + new URLSearchParams({ from, to })),
  shares: () => request<ShareRequest[]>('/api/shares'),
  requestShare: (journalId: number, days: number) =>
    request<ShareRequest>('/api/shares/journals/' + journalId, {
      method: 'POST',
      body: JSON.stringify({ validityDays: days }),
    }),
  revokeShare: (id: number) => request<void>('/api/shares/' + id + '/revoke', { method: 'POST' }),
  reviewQueue: () => request<ShareRequest[]>('/api/admin/reviews'),
  reviewSnapshot: (id: number) => request<ShareSnapshot>('/api/admin/reviews/' + id),
  readShare: (token: string) => request<ShareSnapshot>('/api/shares/read/' + encodeURIComponent(token)),
  approveShare: (id: number) => request<void>('/api/admin/reviews/' + id + '/approve', { method: 'POST' }),
  rejectShare: (id: number, reason: string) =>
    request<void>('/api/admin/reviews/' + id + '/reject', { method: 'POST', body: JSON.stringify({ reason }) }),
  tagIconReviews: () => request<TagIconReview[]>('/api/admin/tag-icons'),
  decideTagIcon: (id: number, decision: 'approve' | 'reject', reason?: string) =>
    request<void>('/api/admin/tag-icons/' + id + '/' + decision, { method: 'POST', body: reason ? JSON.stringify({ reason }) : undefined }),
  updateSettings: (settings: { nickname: string; timeZone: string; theme: string; accent: string }) =>
    request<User>('/api/settings', { method: 'PUT', body: JSON.stringify(settings) }),
  uploadAvatar: (file: File) => {
    const body = new FormData()
    body.set('file', file)
    return request<User>('/api/settings/avatar', { method: 'POST', body })
  },
  deleteAvatar: () => request<User>('/api/settings/avatar', { method: 'DELETE' }),
  reminderStatus: () => request<ReminderStatus>('/api/reminders'),
  saveReminders: (settings: ReminderSettings) =>
    request<ReminderStatus>('/api/reminders', { method: 'PUT', body: JSON.stringify(settings) }),
  subscribeReminders: (endpoint: string, p256dh: string, auth: string) =>
    request<ReminderStatus>('/api/reminders/subscription', { method: 'POST', body: JSON.stringify({ endpoint, p256dh, auth }) }),
  unsubscribeReminders: (endpoint: string) =>
    request<ReminderStatus>('/api/reminders/subscription', { method: 'DELETE', body: JSON.stringify({ endpoint }) }),
  deactivate: (password: string) =>
    request<void>('/api/auth/deactivate', { method: 'POST', body: JSON.stringify({ password }) }),
  exportable: () => request<ExportEntry[]>('/api/data/exportable'),
  importJournals: (entries: JournalWrite[], operationId: string, preview = false) =>
    request<ImportResult>(preview ? '/api/data/import/preview' : '/api/data/import', {
      method: 'POST',
      body: JSON.stringify({ entries, operationId }),
    }),
  restoreBackup: (backup: unknown, restoreSettings: boolean, mergeNotebooks: boolean, preview = false) =>
    request<RestoreResult>(preview ? '/api/data/restore/preview' : '/api/data/restore', {
      method: 'POST',
      body: JSON.stringify({ backup, restoreSettings, mergeNotebooks }),
    }),
  uploadImage: (file: File, ownerId?: number) => {
    const body = new FormData()
    body.set('file', file)
    return request<{ id: number; url: string; originalName: string }>('/api/media' + accountQuery(ownerId), { method: 'POST', body })
  },
}
