<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError, api } from '../lib/api'
import { cacheJournal, cacheLibrary, cachedJournal, cachedLibrary, draftKey, getDraft, getDraftByKey, removeCachedJournal, removeDraft, saveDraft, sendDraft } from '../lib/offline'
import type { Journal, JournalWrite, Notebook, Tag, User, WritingDay } from '../lib/types'
import Icon from '../components/Icon.vue'
import JournalEditor from '../components/JournalEditor.vue'
import TagChip from '../components/TagChip.vue'
import WorldHistory from '../components/WorldHistory.vue'

const props = defineProps<{
  user: User
  offline: boolean
  journalId: number | null
  newEntryToken: number
  localDraftKey?: string
}>()
const emit = defineEmits<{
  opened: [entry: Journal | null]
  changed: [entry: Journal]
  create: []
  navigate: [screen: 'history' | 'calendar']
  open: [id: number]
}>()

const entryId = ref<number | null>(props.journalId)
const version = ref<number | undefined>()
const title = ref('')
const journalDate = ref(todayFor(props.user.timeZone))
const content = ref<Record<string, unknown>>({ type: 'doc', content: [{ type: 'paragraph' }] })
const notebookId = ref<number | null>(null)
const tagIds = ref<number[]>([])
const notebooks = ref<Notebook[]>([])
const tags = ref<Tag[]>([])
const selectedTags = computed(() => tags.value.filter((tag) => tagIds.value.includes(tag.id)))
const busy = ref(true)
const unavailable = ref(false)
const message = ref('')
const error = ref('')
const saveStatus = ref<'idle' | 'local' | 'syncing' | 'synced' | 'failed' | 'conflict'>('idle')
const updatedAt = ref('')
const conflictJournal = ref<Journal | null>(null)
const conflictOffer = ref(false)
const hasPendingDelete = ref(false)
const toolbarHidden = ref(false)
const menuOpen = ref(false)
const shareBusy = ref(false)
const contextBusy = ref(true)
const contextError = ref('')
const monthEntries = ref<WritingDay[]>([])
const writingDays = ref<WritingDay[]>([])
const pastEntries = ref<Journal[]>([])
const timezone = computed(() => props.user.timeZone || 'Asia/Shanghai')
const initialContent = () => ({ type: 'doc', content: [{ type: 'paragraph' }] })
let saveTimer: ReturnType<typeof setTimeout> | undefined
let clientId: string = crypto.randomUUID()
let operationId: string = crypto.randomUUID()
let editRevision = 0
let loadRevision = 0
let lastValid: JournalWrite | null = null
let activityDates = new Set<string>()
let applying = false
let dirty = false
let loadedOnce = false
let queuedDeletion: Awaited<ReturnType<typeof getDraft>>
let sync: Promise<boolean> | null = null
let localSave: Promise<boolean> = Promise.resolve(true)

function todayFor(zone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const read = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return read('year') + '-' + read('month') + '-' + read('day')
}

function dateLabel(value: string) {
  const [year = 1970, month = 1, day = 1] = value.split('-').map(Number)
  return year + '年' + month + '月' + day + '日'
}

function textFrom(node: Record<string, unknown>): string {
  if (typeof node.text === 'string') return node.text
  const children = Array.isArray(node.content) ? node.content as Record<string, unknown>[] : []
  return children.map(textFrom).join(node.type === 'paragraph' || node.type === 'heading' ? '' : '\n')
}

function containsImage(node: Record<string, unknown>): boolean {
  if (node.type === 'image') return true
  return Array.isArray(node.content) && (node.content as Record<string, unknown>[]).some(containsImage)
}

function makePayload(): JournalWrite {
  return JSON.parse(JSON.stringify({
    journalDate: journalDate.value,
    title: title.value.trim(),
    automaticTitle: !title.value.trim(),
    content: content.value,
    plainText: textFrom(content.value).trim(),
    notebookId: notebookId.value ?? (notebooks.value.find((book) => book.isDefault)?.id ?? 0),
    tagIds: tagIds.value,
    expectedVersion: version.value,
    clientId,
    operationId,
    activityDates: [...activityDates],
  })) as JournalWrite
}

const isValid = computed(() => Boolean(textFrom(content.value).replace(/[\p{White_Space}\p{Cc}\p{Cf}]/gu, '')) || containsImage(content.value))

function statusLabel() {
  if (saveStatus.value === 'local') return '已在本机保存 · 待同步'
  if (saveStatus.value === 'syncing') return '正在同步'
  if (saveStatus.value === 'synced') return '已同步到服务器'
  if (saveStatus.value === 'failed') return '保存失败'
  if (saveStatus.value === 'conflict') return '发现同步冲突'
  if (props.offline || !navigator.onLine) return '离线编辑'
  return busy.value ? '正在载入' : '随写随存'
}

function showJournal(entry: Journal | null) {
  applying = true
  entryId.value = entry?.id ?? null
  version.value = entry?.version
  title.value = entry && !entry.automaticTitle ? entry.title : ''
  journalDate.value = entry?.journalDate ?? todayFor(timezone.value)
  content.value = entry?.content ?? initialContent()
  notebookId.value = entry?.notebookId ?? notebooks.value.find((book) => book.isDefault)?.id ?? null
  tagIds.value = entry?.tags.map((tag) => tag.id) ?? []
  updatedAt.value = entry?.updatedAt ?? ''
  clientId = crypto.randomUUID()
  operationId = crypto.randomUUID()
  activityDates = new Set()
  dirty = false
  hasPendingDelete.value = false
  conflictOffer.value = false
  conflictJournal.value = null
  lastValid = entry ? makePayload() : null
  queuedDeletion = undefined
  saveStatus.value = entry ? 'synced' : 'idle'
  applying = false
}

function showDraft(draft: NonNullable<Awaited<ReturnType<typeof getDraft>>>, server: Journal | null) {
  applying = true
  const legacyClear = !draft.payload.plainText.trim() && !containsImage(draft.payload.content) && Boolean(server)
  const payload = legacyClear && server ? { ...draft.payload, content: server.content, plainText: server.plainText } : draft.payload
  entryId.value = draft.journalId
  version.value = payload.expectedVersion
  clientId = payload.clientId ?? crypto.randomUUID()
  operationId = payload.operationId ?? crypto.randomUUID()
  activityDates = new Set(payload.activityDates ?? [])
  journalDate.value = payload.journalDate
  title.value = payload.automaticTitle ? '' : payload.title
  notebookId.value = payload.notebookId
  tagIds.value = [...payload.tagIds]
  lastValid = payload
  queuedDeletion = draft.trashRequested && !draft.conflict ? draft : undefined
  hasPendingDelete.value = Boolean(draft.pendingDelete || draft.trashRequested || legacyClear)
  content.value = hasPendingDelete.value ? initialContent() : payload.content
  dirty = true
  conflictOffer.value = Boolean(draft.conflict)
  conflictJournal.value = conflictOffer.value ? server : null
  saveStatus.value = conflictOffer.value ? 'conflict' : 'local'
  message.value = hasPendingDelete.value
    ? '已恢复上次清空的编辑状态。继续输入可保留日记，也可以恢复清空前的内容。'
    : '已恢复本机保存的编辑内容。'
  applying = false
}

async function loadEntry() {
  const run = ++loadRevision
  const ownerId = props.user.id
  const requestedId = props.journalId
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    let library = await cachedLibrary(ownerId).catch(() => undefined)
    let selected = requestedId ? await cachedJournal(ownerId, requestedId).catch(() => undefined) ?? null : null
    const pendingNew = !requestedId && (props.localDraftKey || props.newEntryToken === 0)
      ? await (props.localDraftKey ? getDraftByKey(ownerId, props.localDraftKey) : getDraft(ownerId, null)).catch(() => undefined)
      : undefined
    if (!props.offline && navigator.onLine) {
      try {
        const [bookList, tagList] = await Promise.all([api.notebooks(), api.tags()])
        library = { ownerId, notebooks: bookList, tags: tagList }
        void cacheLibrary(ownerId, bookList, tagList).catch(() => undefined)
      } catch (cause) {
        if (run === loadRevision) error.value = cause instanceof Error ? cause.message : '日记本与标签未能载入。'
      }
      try {
        if (requestedId) selected = await api.journal(requestedId)
        else if (!loadedOnce && props.newEntryToken === 0 && !pendingNew) {
          selected = (await api.journals({ date: todayFor(timezone.value), limit: 1 }))[0] ?? null
        }
        if (selected) void cacheJournal(ownerId, selected).catch(() => undefined)
      } catch (cause) {
        if (cause instanceof ApiError && cause.status === 404 && requestedId) {
          selected = null
          void removeCachedJournal(ownerId, requestedId).catch(() => undefined)
        }
        if (run === loadRevision) error.value = cause instanceof Error ? cause.message : '日记未能载入。'
      }
    }
    const local = (requestedId || selected?.id) ? await getDraft(ownerId, requestedId ?? selected!.id) : pendingNew
    if (run !== loadRevision || props.user.id !== ownerId) return
    notebooks.value = library?.notebooks ?? []
    tags.value = library?.tags ?? []
    showJournal(selected)
    if (local) {
      showDraft(local, selected)
      if (local.key === draftKey(ownerId, null) && local.journalId === null) {
        await persistLocal()
        await removeDraft(ownerId, null)
      }
    }
    else if (requestedId && !selected) {
      error.value ||= '这篇日记未在本机缓存，联网后才能打开。'
      unavailable.value = true
    }
    emit('opened', selected)
  } catch (cause) {
    if (run === loadRevision) error.value = cause instanceof Error ? cause.message : '本机内容未能载入。'
  } finally {
    if (run === loadRevision) {
      busy.value = false
      loadedOnce = true
      if (!props.offline && queuedDeletion) void resumeQueuedDeletion()
      else if (dirty && !hasPendingDelete.value && !conflictOffer.value && !props.offline) void saveToServer()
    }
  }
}

function persistLocal(trashRequested = false) {
  const payload = hasPendingDelete.value ? lastValid : isValid.value ? makePayload() : null
  if (!payload) return Promise.resolve(true)
  const revision = editRevision
  const write = saveDraft(props.user.id, entryId.value, payload, {
    pendingDelete: hasPendingDelete.value,
    trashRequested,
    conflict: conflictOffer.value,
  }).then(() => {
    if (revision === editRevision && saveStatus.value !== 'syncing' && !conflictOffer.value) saveStatus.value = 'local'
    return true
  }).catch(() => {
    saveStatus.value = 'failed'
    error.value = '本机空间不足或存储不可用。请保留此页并重试。'
    return false
  })
  localSave = write
  return write
}

function saveToServer(force = false): Promise<boolean> {
  if (sync) return sync
  if (!dirty || !isValid.value || hasPendingDelete.value || conflictOffer.value || (!force && props.offline) || !navigator.onLine) {
    return localSave
  }
  const ownerId = props.user.id
  const id = entryId.value
  const revision = editRevision
  const payload = makePayload()
  sync = (async () => {
    try {
      saveStatus.value = 'syncing'
      const draft = await saveDraft(ownerId, id, payload)
      const saved = await sendDraft(draft)
      if (!saved) return false
      if (props.user.id !== ownerId) return false
      entryId.value = saved.id
      version.value = saved.version
      updatedAt.value = saved.updatedAt
      if (revision === editRevision) {
        applying = true
        activityDates.clear()
        dirty = false
        lastValid = makePayload()
        applying = false
        saveStatus.value = 'synced'
      } else {
        if (lastValid) {
          lastValid.expectedVersion = saved.version
          if (hasPendingDelete.value) lastValid.operationId = undefined
        }
        // A save can finish while the user is still writing the first version.
        // Move the newer local edits to the newly assigned journal ID before removing the old key.
        await persistLocal()
      }
      await removeDraft(ownerId, id, id === null ? undefined : payload.operationId, payload.clientId)
      emit('changed', saved)
      void loadContext()
      return true
    } catch (cause) {
      if (cause instanceof ApiError && (cause.status === 409 || (cause.status === 404 && id !== null))) {
        const details = cause.details as { current?: Journal }
        conflictJournal.value = details.current ?? null
        conflictOffer.value = true
        await persistLocal()
        saveStatus.value = 'conflict'
        return true
      }
      const savedLocally = await persistLocal()
      error.value = cause instanceof Error ? cause.message : '同步失败。'
      saveStatus.value = 'failed'
      return savedLocally
    } finally {
      sync = null
      if (revision !== editRevision && dirty && !hasPendingDelete.value && !conflictOffer.value) scheduleFlush()
    }
  })()
  return sync
}

function scheduleFlush() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void saveToServer(), 900)
}

function scheduleSave(writing: boolean) {
  if (applying || !loadedOnce || busy.value || unavailable.value) return
  editRevision += 1
  queuedDeletion = undefined
  operationId = crypto.randomUUID()
  hasPendingDelete.value = !isValid.value && lastValid !== null
  if (isValid.value) {
    if (writing) activityDates.add(todayFor(timezone.value))
    lastValid = makePayload()
  }
  if (!isValid.value && !hasPendingDelete.value) return
  dirty = true
  void persistLocal()
  scheduleFlush()
}

function onContentChange(value: Record<string, unknown>) {
  if (JSON.stringify(content.value) === JSON.stringify(value)) return
  content.value = value
  scheduleSave(true)
}

watch(title, () => scheduleSave(true), { flush: 'sync' })
watch([journalDate, notebookId, tagIds], () => scheduleSave(false), { deep: true, flush: 'sync' })
watch([() => props.journalId, () => props.newEntryToken, () => props.user.id, () => props.localDraftKey], ([id, token, owner, localKey], [, oldToken, oldOwner, oldLocalKey]) => {
  if (id === entryId.value && token === oldToken && owner === oldOwner && localKey === oldLocalKey) return
  unavailable.value = false
  void loadEntry()
})
watch(() => props.offline, (offline) => {
  if (offline) return
  if (queuedDeletion) void resumeQueuedDeletion()
  else void saveToServer()
})

function resumeQueuedDeletion() {
  const draft = queuedDeletion
  if (!draft || conflictOffer.value || !navigator.onLine || sync) return
  queuedDeletion = undefined
  busy.value = true
  sync = (async () => {
    try {
      await sendDraft(draft)
      await removeDraft(draft.ownerId, draft.journalId, draft.payload.operationId, draft.payload.clientId)
      showJournal(null)
      emit('opened', null)
      message.value = '日记已移入回收站，15 天内可以恢复。'
    } catch (cause) {
      if (cause instanceof ApiError && (cause.status === 409 || cause.status === 404)) {
        conflictJournal.value = (cause.details as { current?: Journal }).current ?? null
        conflictOffer.value = true
        await persistLocal()
        saveStatus.value = 'conflict'
      } else {
        queuedDeletion = draft
        error.value = cause instanceof Error ? cause.message : '待同步的回收站操作未完成。'
      }
    } finally {
      busy.value = false
      sync = null
    }
    return true
  })()
}

async function beforeLeave() {
  clearTimeout(saveTimer)
  if (sync) await sync
  clearTimeout(saveTimer)
  if (queuedDeletion) return true
  if (hasPendingDelete.value && !conflictOffer.value) return moveToTrash(false)
  return flushEdits()
}

async function flushEdits() {
  if (!dirty) return true
  if (!(await persistLocal())) return false
  return saveToServer()
}

async function moveToTrash(confirm = true): Promise<boolean> {
  if (confirm && !window.confirm('这篇日记会保留 15 天，可在回收站恢复。要继续吗？')) return false
  clearTimeout(saveTimer)
  if (sync) await sync
  const id = entryId.value
  if (!lastValid) return true
  const payload = { ...(isValid.value ? makePayload() : lastValid), operationId: crypto.randomUUID() }
  const ownerId = props.user.id
  let queued = false
  try {
    const draft = await saveDraft(ownerId, id, payload, { pendingDelete: true, trashRequested: true })
    queued = true
    if (!props.offline && navigator.onLine) {
      await sendDraft(draft)
      await removeDraft(ownerId, id, payload.operationId, payload.clientId)
      message.value = '日记已移入回收站，15 天内可以恢复。'
    } else {
      message.value = '已在本机保留删除操作，联网后将移入回收站。'
    }
    showJournal(null)
    emit('opened', null)
    menuOpen.value = false
    return true
  } catch (cause) {
    if (queued && cause instanceof ApiError && (cause.status === 0 || cause.status >= 500)) {
      message.value = '删除操作已在本机保存，服务恢复后会继续同步。'
      showJournal(null)
      emit('opened', null)
      menuOpen.value = false
      return true
    }
    if (cause instanceof ApiError && (cause.status === 409 || cause.status === 404)) {
      conflictOffer.value = true
      conflictJournal.value = (cause.details as { current?: Journal }).current ?? null
      await persistLocal()
      saveStatus.value = 'conflict'
    }
    error.value = cause instanceof Error ? cause.message : '日记未能移入回收站。'
    return false
  }
}

function restoreClearedContent() {
  if (!lastValid) return
  content.value = lastValid.content
  hasPendingDelete.value = false
  message.value = '已恢复清空前的内容。'
  scheduleSave(false)
}

async function makeConflictCopy() {
  if (!lastValid) return
  const previous = entryId.value
  const payload = { ...lastValid, expectedVersion: undefined, clientId: crypto.randomUUID(), operationId: crypto.randomUUID() }
  payload.title = ((payload.title || dateLabel(payload.journalDate)).slice(0, 240)) + ' · 冲突副本'
  payload.automaticTitle = false
  try {
    const draft = { key: '', ownerId: props.user.id, journalId: null, payload, savedAt: new Date().toISOString() }
    const saved = await sendDraft(draft, true)
    if (!saved) return
    await removeDraft(props.user.id, previous, undefined, clientId)
    showJournal(saved)
    message.value = '双方内容都已保留，本机内容已另存为一篇。'
    emit('changed', saved)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '冲突副本未能保存。'
  }
}

async function useServerVersion() {
  if (!conflictJournal.value || !window.confirm('确定保留服务器版本并丢弃这条本机编辑？')) return
  await removeDraft(props.user.id, entryId.value, undefined, clientId)
  showJournal(conflictJournal.value)
  message.value = '已载入服务器版本。'
}

defineExpose({ beforeLeave, flushEdits, activeKey: () => draftKey(props.user.id, entryId.value, clientId) })

onMounted(() => {
  void loadEntry()
  void loadContext()
})

onBeforeUnmount(() => {
  loadRevision += 1
  clearTimeout(saveTimer)
})

const displayTitle = computed(() => dateLabel(journalDate.value).split(' ')[0])
const currentMonth = computed(() => todayFor(timezone.value).slice(0, 7))
const monthLabel = computed(() => {
  const [year, month] = currentMonth.value.split('-')
  return year + '年 ' + Number(month) + '月'
})
const calendarDays = computed(() => {
  const [year = 1970, month = 1] = currentMonth.value.split('-').map(Number)
  const blanks = (new Date(Date.UTC(year, month - 1, 1)).getUTCDay() + 6) % 7
  const count = new Date(Date.UTC(year, month, 0)).getUTCDate()
  return [...Array.from({ length: blanks }, () => ''), ...Array.from({ length: count }, (_, i) => currentMonth.value + '-' + String(i + 1).padStart(2, '0'))]
})
const coveredDates = computed(() => new Set(monthEntries.value.map((entry) => entry.date)))
const monthWritingDays = computed(() => writingDays.value.filter((day) => day.count > 0 && day.date.startsWith(currentMonth.value)).length)
const wordCount = computed(() => Array.from(textFrom(content.value).replace(/\s/g, '')).length)

async function loadContext() {
  contextBusy.value = true
  contextError.value = ''
  if (props.offline) {
    contextError.value = '联网后显示日历与往年记录。'
    contextBusy.value = false
    return
  }
  try {
    const [year = 1970, month = 1] = currentMonth.value.split('-').map(Number)
    const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate()
    const today = todayFor(timezone.value)
    const [entries, activity, history] = await Promise.all([
      api.coverage(currentMonth.value + '-01', currentMonth.value + '-' + lastDay),
      api.activity(),
      api.journals({ monthDay: today.slice(5), to: (Number(today.slice(0, 4)) - 1) + '-12-31', sort: 'date-desc', limit: 3 }),
    ])
    monthEntries.value = entries
    writingDays.value = activity
    pastEntries.value = history
  } catch {
    contextError.value = '回顾记录暂时无法载入。'
  } finally {
    contextBusy.value = false
  }
}

async function requestShare() {
  if (!entryId.value || shareBusy.value) return
  shareBusy.value = true
  try {
    await saveToServer()
    if (dirty || hasPendingDelete.value || conflictOffer.value) {
      error.value = '请先完成同步，再提交当前版本。'
      return
    }
    await api.requestShare(entryId.value, 30)
    message.value = '分享申请已提交，审核通过后可在分享管理中获取链接。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '分享申请未能提交，请重试。'
  } finally {
    shareBusy.value = false
  }
}

watch(() => props.offline, loadContext)
</script>

<template>
  <div class="write-layout">
    <WorldHistory :time-zone="timezone" preview @open="emit('navigate', 'history')" />
    <section class="write-main">
      <div class="write-topline">
        <label class="write-date"><Icon name="calendar" :size="16" /><span class="visually-hidden">日记日期</span><input v-model="journalDate" aria-label="日记日期" type="date" :max="todayFor(timezone)" required /></label>
        <div class="save-indicator" :class="'status-' + saveStatus" role="status">
          <span class="status-dot"></span>{{ statusLabel() }}
          <span v-if="updatedAt" class="saved-time">{{ new Date(updatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</span>
        </div>
      </div>

      <div v-if="message" class="notice write-notice" role="status">{{ message }}</div>
      <div v-if="error" class="notice error write-notice" role="alert">{{ error }} <button v-if="dirty && !conflictOffer" class="text-button" type="button" @click="queuedDeletion ? resumeQueuedDeletion() : saveToServer()">重试保存</button></div>
      <div v-if="hasPendingDelete" class="notice pending-delete" role="status">
        正文已清空。重新输入可保留这篇日记；离开当前编辑页时会将最后一次有效内容移入回收站。
        <button class="text-button" type="button" @click="restoreClearedContent">恢复正文</button>
      </div>
      <div v-if="conflictOffer" class="conflict-panel" role="alert">
        <div><strong>本机内容与服务器状态有冲突</strong><p>你可以将本机内容另存为一篇。</p><details v-if="conflictJournal"><summary>查看服务器版本摘要</summary><strong>{{ conflictJournal.title }}</strong><p>{{ conflictJournal.plainText.slice(0, 300) || '图片日记' }}</p></details></div>
        <div class="conflict-actions">
          <button v-if="conflictJournal" class="secondary-button" type="button" @click="useServerVersion">保留服务器版本</button>
          <button class="primary-button" type="button" @click="makeConflictCopy">另存本机副本</button>
        </div>
      </div>

      <div class="title-row">
        <textarea v-model="title" class="entry-title" rows="1" :placeholder="displayTitle" :disabled="busy || unavailable" aria-label="日记标题，留空使用日期" @keydown.enter.prevent></textarea>
        <div class="entry-menu-wrap" @keydown.esc="menuOpen = false">
          <button class="icon-button" type="button" title="更多日记操作" aria-label="更多日记操作" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Icon name="more" /></button>
          <div v-if="menuOpen" class="entry-menu" role="menu">
            <button type="button" role="menuitem" @click="emit('create'); menuOpen = false">再写一篇</button>
            <button v-if="entryId" type="button" role="menuitem" class="menu-danger" @click="moveToTrash()">移入回收站</button>
          </div>
        </div>
      </div>
      <div v-if="busy" class="loading-line"><span class="spinner"></span>正在载入日记…</div>
      <div v-else-if="!unavailable">
        <JournalEditor
          :key="props.newEntryToken"
          :owner-id="user.id"
          :offline="offline"
          :content="content"
          :toolbar-hidden="toolbarHidden"
          @change="onContentChange"
          @toolbar="toolbarHidden = $event"
          @image-error="error = $event"
        />
        <div class="entry-footer"><span>{{ wordCount }} 字</span><span><Icon name="lock" :size="13" />仅自己可见</span><button v-if="entryId" class="text-button" type="button" :disabled="shareBusy || offline" @click="requestShare"><Icon name="share" :size="14" />{{ shareBusy ? '正在提交…' : '申请分享' }}</button></div>
        <div class="write-meta">
          <label class="meta-field">
            <span>日记本</span>
            <select v-model.number="notebookId" class="select">
              <option v-for="book in notebooks" :key="book.id" :value="book.id">{{ book.name }}</option>
            </select>
          </label>
          <label class="meta-field">
            <span>标签</span>
            <select class="select" :value="''" @change="($event.target as HTMLSelectElement).value && tagIds.push(Number(($event.target as HTMLSelectElement).value))">
              <option value="">添加标签</option>
              <option v-for="tag in tags.filter((item) => !tagIds.includes(item.id))" :key="tag.id" :value="tag.id">{{ tag.name }}</option>
            </select>
          </label>
          <div class="selected-tags">
            <button v-for="tag in selectedTags" :key="tag.id" class="remove-tag-button" type="button" :aria-label="'移除标签' + tag.name" @click="tagIds = tagIds.filter((id) => id !== tag.id)">
              <TagChip :tag="tag" :offline="offline"><span aria-hidden="true">×</span></TagChip>
            </button>
          </div>
        </div>
      </div>
    </section>
    <aside class="write-aside" aria-label="日历与回顾">
      <section class="aside-calendar">
        <div class="aside-section-head"><h2>{{ monthLabel }}</h2><button class="icon-button" type="button" aria-label="查看日历与记录" @click="emit('navigate', 'calendar')"><Icon name="calendar" :size="17" /></button></div>
        <div class="mini-calendar" aria-label="本月日记日期覆盖">
          <span v-for="day in ['一', '二', '三', '四', '五', '六', '日']" :key="day" class="mini-weekday">{{ day }}</span>
          <span v-for="(date, index) in calendarDays" :key="date || index" class="mini-day" :class="{ 'is-today': date === todayFor(timezone), 'has-entry': coveredDates.has(date) }" :aria-label="date ? date + (coveredDates.has(date) ? '，有日记' : '') : undefined">{{ date ? Number(date.slice(-2)) : '' }}</span>
        </div>
        <p v-if="contextBusy" class="aside-prompt" role="status">正在载入写作记录…</p>
        <div v-else-if="contextError" class="aside-load-error"><p>{{ contextError }}</p><button v-if="!offline" class="text-button" type="button" @click="loadContext">重试</button></div>
        <div v-else class="aside-writing-summary"><span>本月写作 <strong>{{ monthWritingDays }}</strong> 天</span><span><i></i>有日记的日期</span></div>
      </section>
      <section class="aside-section">
        <div class="aside-section-head"><h2>往年今日</h2><button class="text-button" type="button" @click="emit('navigate', 'history')">查看全部</button></div>
        <template v-if="!contextBusy && !contextError">
          <button v-for="entry in pastEntries" :key="entry.id" class="aside-history-entry" type="button" @click="emit('open', entry.id)"><time>{{ entry.journalDate }}</time><strong>{{ entry.title }}</strong><span>{{ entry.plainText.slice(0, 58) || '图片日记' }}</span></button>
          <p v-if="!pastEntries.length" class="aside-prompt">往年这一天还没有记录。今天写下的日常，会成为未来的回忆。</p>
        </template>
        <p v-else class="aside-prompt">{{ contextBusy ? '正在寻找往年记录…' : '联网后，回看曾经的这一天。' }}</p>
      </section>
      <section class="aside-section writing-prompt"><h2>写给自己</h2><p>今天有什么小事，让你停下来多看了一眼？</p></section>
    </aside>
  </div>
</template>
