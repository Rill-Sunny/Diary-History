import { openDB, type DBSchema } from 'idb'
import { ApiError, api } from './api'
import type { Journal, JournalWrite, Notebook, Tag } from './types'

interface Draft {
  key: string
  ownerId: number
  journalId: number | null
  payload: JournalWrite
  savedAt: string
  pendingDelete?: boolean
  trashRequested?: boolean
  conflict?: boolean
}

interface OfflineSchema extends DBSchema {
  drafts: {
    key: string
    value: Draft
  }
  uploads: {
    key: string
    value: { key: string; ownerId: number; operationId: string; file: Blob; name: string; type: string; uploaded?: { id: number; url: string } }
  }
  journals: {
    key: string
    value: { key: string; ownerId: number; journal: Journal }
  }
  library: {
    key: number
    value: { ownerId: number; notebooks: Notebook[]; tags: Tag[] }
  }
  images: {
    key: string
    value: { key: string; ownerId: number; blob: Blob }
  }
}

const database = openDB<OfflineSchema>('liubai-offline', 2, {
  upgrade(db, oldVersion) {
    if (oldVersion < 1) {
      db.createObjectStore('drafts', { keyPath: 'key' })
      db.createObjectStore('uploads', { keyPath: 'key' })
    }
    db.createObjectStore('journals', { keyPath: 'key' })
    db.createObjectStore('library', { keyPath: 'ownerId' })
    db.createObjectStore('images', { keyPath: 'key' })
  },
})

export function draftKey(ownerId: number, journalId: number | null, clientId?: string) {
  return String(ownerId) + ':' + String(journalId ?? (clientId ? 'new:' + clientId : 'new'))
}

export async function saveDraft(ownerId: number, journalId: number | null, payload: JournalWrite,
  state: Pick<Draft, 'pendingDelete' | 'trashRequested' | 'conflict'> = {}) {
  // Journal bodies share the API's JSON format. Snapshot before awaiting the
  // database so reactive proxies and subsequent edits cannot enter this write.
  const snapshot = JSON.parse(JSON.stringify(payload)) as JournalWrite
  const savedAt = new Date().toISOString()
  const db = await database
  const key = draftKey(ownerId, journalId, payload.clientId)
  const draft = { key, ownerId, journalId, payload: snapshot, savedAt, ...state }
  await db.put('drafts', draft)
  return draft
}

export async function getDraft(ownerId: number, journalId: number | null) {
  if (journalId !== null) return (await database).get('drafts', draftKey(ownerId, journalId))
  return (await listDrafts(ownerId)).filter((draft) => draft.journalId === null && !draft.trashRequested)
    .sort((left, right) => right.savedAt.localeCompare(left.savedAt))[0]
}

export async function getDraftByKey(ownerId: number, key: string) {
  const draft = await (await database).get('drafts', key)
  return draft?.ownerId === ownerId ? draft : undefined
}

export async function removeDraft(ownerId: number, journalId: number | null, operationId?: string, clientId?: string) {
  return removeDraftByKey(draftKey(ownerId, journalId, clientId), operationId)
}

async function removeDraftByKey(key: string, operationId?: string) {
  const tx = (await database).transaction('drafts', 'readwrite')
  const current = await tx.store.get(key)
  if (current && (operationId === undefined || current.payload.operationId === operationId)) await tx.store.delete(key)
  await tx.done
}

export async function listDrafts(ownerId: number) {
  return (await database).getAll('drafts').then((rows) => rows.filter((row) => row.ownerId === ownerId))
}

export async function queueUpload(ownerId: number, operationId: string, file: File) {
  const db = await database
  const key = String(ownerId) + ':' + operationId
  await db.put('uploads', { key, ownerId, operationId, file, name: file.name, type: file.type })
  return key
}

export async function findQueuedUpload(ownerId: number, operationId: string) {
  return (await database).get('uploads', String(ownerId) + ':' + operationId)
}

export async function flushPendingDrafts(ownerId: number, activeKey?: string) {
  const pending = await listDrafts(ownerId)
  for (const draft of pending) {
    if (draft.key === activeKey || draft.conflict || (draft.pendingDelete && !draft.trashRequested)) continue
    if (!draft.payload.plainText.trim() && !draft.trashRequested) {
      let hasImages = false
      visitImages(draft.payload.content, () => { hasImages = true })
      if (!hasImages) continue
    }
    try {
      if (!draft.payload.operationId) {
        draft.payload.operationId = crypto.randomUUID()
        await (await database).put('drafts', draft)
      }
      await sendDraft(draft)
      await removeDraftByKey(draft.key, draft.payload.operationId)
    } catch (cause) {
      if (cause instanceof ApiError && (cause.status === 409 || (cause.status === 404 && draft.journalId !== null))) {
        const tx = (await database).transaction('drafts', 'readwrite')
        const row = await tx.store.get(draft.key)
        if (row?.payload.operationId === draft.payload.operationId) await tx.store.put({ ...row, conflict: true } as Draft)
        await tx.done
      }
      if (cause instanceof ApiError && (cause.status === 0 || cause.status === 401)) return
    }
  }
}

function snapshot<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

export async function cacheLibrary(ownerId: number, notebooks: Notebook[], tags: Tag[]) {
  await (await database).put('library', { ownerId, notebooks: snapshot(notebooks), tags: snapshot(tags) })
}

export async function cachedLibrary(ownerId: number) {
  return (await database).get('library', ownerId)
}

export async function cacheJournal(ownerId: number, journal: Journal) {
  const key = draftKey(ownerId, journal.id)
  await (await database).put('journals', { key, ownerId, journal: snapshot(journal) })
  const mediaIds = new Set<number>()
  visitImages(journal.content, (attrs) => {
    if (typeof attrs.mediaId === 'number') mediaIds.add(attrs.mediaId)
  })
  for (const id of mediaIds) {
    void cacheImage(ownerId, id).catch(() => undefined)
  }
}

async function cacheImage(ownerId: number, id: number) {
  const key = draftKey(ownerId, id)
  const db = await database
  if (await db.getKey('images', key)) return
  const response = await fetch('/api/media/' + id, { cache: 'no-store', credentials: 'same-origin' })
  if (!response.ok || !response.headers.get('content-type')?.startsWith('image/')) return
  await db.put('images', { key, ownerId, blob: await response.blob() })
}

export async function cachedImage(ownerId: number, mediaId: number) {
  return (await database).get('images', draftKey(ownerId, mediaId)).then((image) => image?.blob)
}

export async function cachedJournals(ownerId: number) {
  const rows = await (await database).getAll('journals')
  return rows.filter((row) => row.ownerId === ownerId).map((row) => row.journal)
}

export async function cachedJournal(ownerId: number, id: number) {
  return (await database).get('journals', draftKey(ownerId, id)).then((row) => row?.journal)
}

export async function removeCachedJournal(ownerId: number, id: number) {
  await (await database).delete('journals', draftKey(ownerId, id))
}

export function visitImages(node: Record<string, unknown>, visit: (attrs: Record<string, unknown>) => void) {
  const attrs = node.attrs as Record<string, unknown> | undefined
  if (node.type === 'image' && attrs) visit(attrs)
  if (Array.isArray(node.content)) {
    for (const child of node.content as Record<string, unknown>[]) visitImages(child, visit)
  }
}

export async function sendDraft(draft: Draft, conflictCopy = false) {
  const payload = snapshot(draft.payload)
  const images: Record<string, unknown>[] = []
  visitImages(payload.content, (attrs) => {
    if (typeof attrs.offlineId === 'string') images.push(attrs)
  })
  const uploaded = new Map<string, { id: number; url: string }>()
  for (const attrs of images) {
    const operationId = attrs.offlineId as string
    let image = uploaded.get(operationId)
    if (!image) {
      const row = await findQueuedUpload(draft.ownerId, operationId)
      if (!row) throw new Error('本机图片无法读取，请重新添加这张图片。')
      image = row.uploaded
      if (!image) {
        image = await api.uploadImage(new File([row.file], row.name, { type: row.type }), draft.ownerId)
        const db = await database
        const tx = db.transaction(['uploads', 'images'], 'readwrite')
        await tx.objectStore('uploads').put({ ...row, uploaded: image })
        const imageKey = draftKey(draft.ownerId, image.id)
        await tx.objectStore('images').put({ key: imageKey, ownerId: draft.ownerId, blob: row.file })
        await tx.done
      }
      uploaded.set(operationId, image)
    }
    attrs.mediaId = image.id
    attrs.src = image.url
    delete attrs.offlineId
  }
  if (draft.trashRequested) {
    await api.saveAndTrash(payload, draft.journalId, draft.ownerId)
    if (draft.journalId) await removeCachedJournal(draft.ownerId, draft.journalId)
    return null
  }
  const saved = conflictCopy ? await api.saveConflictCopy(payload, draft.ownerId) :
    await api.saveJournal(payload, draft.journalId ?? undefined, draft.ownerId)
  void cacheJournal(draft.ownerId, saved).catch(() => undefined)
  return saved
}

export async function clearOwnerCache(ownerId: number) {
  const db = await database
  const tx = db.transaction(['drafts', 'uploads', 'journals', 'images', 'library'], 'readwrite')
  for (const store of ['drafts', 'uploads', 'journals', 'images'] as const) {
    const cursor = await tx.objectStore(store).openKeyCursor(IDBKeyRange.bound(`${ownerId}:`, `${ownerId};`, false, true))
    let current = cursor
    while (current) {
      await tx.objectStore(store).delete(current.primaryKey)
      current = await current.continue()
    }
  }
  await tx.objectStore('library').delete(ownerId)
  await tx.done
}
