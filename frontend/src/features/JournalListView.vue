<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError, api } from '../lib/api'
import { cacheJournal, cacheLibrary, cachedJournals, cachedLibrary, listDrafts, removeCachedJournal } from '../lib/offline'
import type { Journal, Notebook, Tag, User } from '../lib/types'
import Icon from '../components/Icon.vue'
import TagChip from '../components/TagChip.vue'

const props = defineProps<{ user: User; offline?: boolean }>()
const emit = defineEmits<{ open: [id: number]; openLocal: [key: string]; create: [] }>()
const entries = ref<Journal[]>([])
const notebooks = ref<Notebook[]>([])
const tags = ref<Tag[]>([])
const query = ref('')
const from = ref('')
const to = ref('')
const notebookId = ref('')
const tagId = ref('')
const loading = ref(true)
const error = ref('')
const failedImages = ref(new Set<number>())
const pending = ref<Awaited<ReturnType<typeof listDrafts>>>([])
const hasMore = ref(false)
const pageSize = 40
const visibleEntries = computed(() => entries.value.map((entry) => ({ ...entry, thumbnail: firstImage(entry.content) })))
const hasFilters = computed(() => Boolean(query.value || from.value || to.value || notebookId.value || tagId.value))
let requestVersion = 0
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined

async function load(more = false) {
  const version = ++requestVersion
  loading.value = true
  error.value = ''
  try {
    pending.value = await listDrafts(props.user.id).catch(() => [])
    if (props.offline) {
      const search = query.value.trim().toLocaleLowerCase()
      const cached = await cachedJournals(props.user.id)
      if (version !== requestVersion || disposed) return
      entries.value = cached.filter((entry) => (!search || (entry.title + '\n' + entry.plainText).toLocaleLowerCase().includes(search)) &&
        (!from.value || entry.journalDate >= from.value) && (!to.value || entry.journalDate <= to.value) &&
        (!notebookId.value || entry.notebookId === Number(notebookId.value)) &&
        (!tagId.value || entry.tags.some((tag) => tag.id === Number(tagId.value))) &&
        !pending.value.some((draft) => draft.journalId === entry.id && draft.trashRequested))
        .sort((left, right) => right.journalDate.localeCompare(left.journalDate) || right.updatedAt.localeCompare(left.updatedAt))
      hasMore.value = false
      return
    }
    const result = await api.journals({
      q: query.value.trim(),
      from: from.value,
      to: to.value,
      notebookId: notebookId.value || undefined,
      tagId: tagId.value || undefined,
      limit: pageSize + 1,
      offset: more ? entries.value.length : 0,
    })
    if (version === requestVersion && !disposed) {
      const page = result.slice(0, pageSize)
      entries.value = more ? [...entries.value, ...page] : page
      hasMore.value = result.length > pageSize
      for (const entry of page) void cacheJournal(props.user.id, entry).catch(() => undefined)
    }
  } catch (e) {
    if (version !== requestVersion || disposed) return
    error.value = e instanceof Error ? e.message : '日记未能载入。'
    if (e instanceof ApiError && e.status === 401) error.value = '登录状态已失效，请重新登录。'
  } finally {
    if (version === requestVersion && !disposed) loading.value = false
  }
}

watch([query, from, to, notebookId, tagId, () => props.offline], () => {
  requestVersion += 1
  loading.value = true
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(), 260)
})

onMounted(async () => {
  window.addEventListener('liubai:drafts-synced', onDraftsSynced)
  try {
    if (props.offline) {
      const library = await cachedLibrary(props.user.id)
      notebooks.value = library?.notebooks ?? []
      tags.value = library?.tags ?? []
    } else {
      const [books, labelList] = await Promise.all([api.notebooks(), api.tags()])
      notebooks.value = books
      tags.value = labelList
      void cacheLibrary(props.user.id, books, labelList).catch(() => undefined)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '筛选条件未能载入。'
  }
  if (!disposed) await load()
})

onBeforeUnmount(() => {
  window.removeEventListener('liubai:drafts-synced', onDraftsSynced)
  disposed = true
  requestVersion += 1
  if (timer) clearTimeout(timer)
})

function onDraftsSynced() {
  if (!disposed) void load()
}

function clearFilters() {
  query.value = ''
  from.value = ''
  to.value = ''
  notebookId.value = ''
  tagId.value = ''
}

function firstImage(node: Record<string, unknown> | undefined): string {
  if (!node) return ''
  const attrs = node.attrs as Record<string, unknown> | undefined
  if (node.type === 'image' && typeof attrs?.src === 'string' && /^\/(?:api\/media|assets)\//.test(attrs.src)) {
    return attrs.src
  }
  for (const child of Array.isArray(node.content) ? node.content : []) {
    const image = firstImage(child as Record<string, unknown>)
    if (image) return image
  }
  return ''
}

function excerpt(entry: Journal) {
  return entry.plainText.replace(/\s+/g, ' ').trim().slice(0, 140) || '图片日记'
}

async function moveToTrash(entry: Journal) {
  if (!window.confirm('这篇日记会在回收站保留 15 天。要移入回收站吗？')) return
  try {
    await api.trashJournal(entry.id, entry.version, props.user.id)
    await removeCachedJournal(props.user.id, entry.id)
    entries.value = entries.value.filter((item) => item.id !== entry.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作未完成。'
  }
}
</script>

<template>
  <section class="journal-browser">
    <header class="page-heading">
      <div><h1>全部日记</h1><p>搜索标题和正文，按日期、日记本与标签组合筛选。</p></div>
      <button class="primary-button" type="button" @click="emit('create')"><Icon name="plus" :size="17" />再写一篇</button>
    </header>
    <div class="list-toolbar journal-filters">
      <label class="search-field"><Icon name="search" :size="17" /><input v-model="query" class="input" type="search" placeholder="搜索标题或正文" aria-label="搜索标题或正文" /></label>
      <label class="filter-field"><span>从</span><input v-model="from" class="input" type="date" :max="to || undefined" aria-label="开始日期" /></label>
      <label class="filter-field"><span>到</span><input v-model="to" class="input" type="date" :min="from || undefined" aria-label="结束日期" /></label>
      <select v-model="notebookId" class="select filter-select" aria-label="筛选日记本">
        <option value="">全部日记本</option><option v-for="book in notebooks" :key="book.id" :value="String(book.id)">{{ book.name }}</option>
      </select>
      <select v-model="tagId" class="select filter-select" aria-label="筛选标签">
        <option value="">全部标签</option><option v-for="tag in tags" :key="tag.id" :value="String(tag.id)">{{ tag.name }}</option>
      </select>
      <button v-if="hasFilters" class="text-button filter-reset" type="button" @click="clearFilters">清除筛选</button>
    </div>
    <div v-if="error" class="notice error" role="alert">{{ error }} <button class="text-button" type="button" @click="load()">重试</button></div>
    <section v-if="pending.some((draft) => !draft.trashRequested)" class="offline-drafts" aria-label="本机编辑">
      <h2>本机编辑</h2>
      <button v-for="draft in pending.filter((item) => !item.trashRequested)" :key="draft.key" class="secondary-button" type="button"
        @click="draft.journalId ? emit('open', draft.journalId) : emit('openLocal', draft.key)">
        {{ draft.payload.title || draft.payload.journalDate }} · {{ draft.conflict ? '有冲突' : draft.pendingDelete ? '已清空' : '待同步' }}
      </button>
    </section>
    <p v-if="offline" class="notice">离线时只显示本机已载入的日记。</p>
    <div v-if="loading" class="loading-line"><span class="spinner"></span>正在检索你的日记…</div>
    <div v-else-if="entries.length" class="journal-list">
      <article v-for="entry in visibleEntries" :key="entry.id" class="journal-row">
        <time class="journal-date" :datetime="entry.journalDate" :aria-label="entry.journalDate"><strong>{{ entry.journalDate.slice(8) }}</strong><span>{{ entry.journalDate.slice(0, 4) }} / {{ entry.journalDate.slice(5, 7) }}</span></time>
        <button class="journal-row-main" type="button" @click="emit('open', entry.id)">
          <div class="journal-row-copy">
          <h2 class="journal-title">{{ entry.title }}</h2>
          <p class="journal-excerpt">{{ excerpt(entry) }}</p>
          <div class="journal-tags"><TagChip v-for="tag in entry.tags" :key="tag.id" :tag="tag" :offline="offline" /></div>
          </div>
          <img v-if="entry.thumbnail && !offline && !failedImages.has(entry.id)" class="journal-thumbnail" :src="entry.thumbnail" alt="" loading="lazy" @error="failedImages.add(entry.id)" />
        </button>
        <div class="journal-row-actions">
          <button class="secondary-button compact-action" type="button" @click="emit('open', entry.id)">编辑</button>
          <button class="icon-button" type="button" title="移入回收站" aria-label="移入回收站" :disabled="offline" @click="moveToTrash(entry)"><Icon name="trash" :size="16" /></button>
        </div>
      </article>
      <button v-if="hasMore" class="secondary-button" type="button" @click="load(true)">加载更多日记</button>
    </div>
    <section v-else-if="!error" class="empty-state">
      <Icon name="book" :size="27" />
      <strong>{{ query || from || to || notebookId || tagId ? '没有符合条件的日记' : '这里会收好你的每一篇日记' }}</strong>
      <p>{{ query || from || to || notebookId || tagId ? '试试放宽日期范围或清除部分筛选条件。' : '保存第一篇后，可以按标题、正文、日期、日记本和标签找回来。' }}</p>
      <button v-if="hasFilters" class="secondary-button" type="button" @click="clearFilters">清除筛选</button>
      <button v-else class="text-button" type="button" @click="emit('create')">开始写第一篇</button>
    </section>
  </section>
</template>
