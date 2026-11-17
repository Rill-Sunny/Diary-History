<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../lib/api'
import type { Journal, User } from '../lib/types'
import Icon from '../components/Icon.vue'

const props = defineProps<{ user: User }>()
const emit = defineEmits<{ open: [id: number] }>()
const entries = ref<Journal[]>([])
const loading = ref(true)
const error = ref('')
const busyId = ref<number | null>(null)
const hasMore = ref(false)
const pageSize = 40

async function load(more = false) {
  loading.value = true
  try {
    const found = await api.journals({ trash: 'true', limit: pageSize + 1, offset: more ? entries.value.length : 0 })
    entries.value = [...(more ? entries.value : []), ...found.slice(0, pageSize)]
    hasMore.value = found.length > pageSize
  } catch (e) {
    error.value = e instanceof Error ? e.message : '回收站未能载入。'
  } finally {
    loading.value = false
  }
}

function remaining(entry: Journal) {
  if (!entry.deletedAt) return '删除时间未知'
  const stamp = entry.deletedAt
  const deleted = new Date(/[Z+-]\d{2}:?\d{2}$|Z$/.test(stamp) ? stamp : stamp + 'Z').getTime()
  const left = deleted + 15 * 24 * 60 * 60 * 1000 - Date.now()
  if (left <= 0) return '已到期'
  const days = Math.ceil(left / (24 * 60 * 60 * 1000))
  return '还可保留 ' + days + ' 天 · ' + new Date(deleted + 15 * 24 * 60 * 60 * 1000).toLocaleString('zh-CN', { timeZone: props.user.timeZone })
}

async function restore(entry: Journal) {
  if (remaining(entry) === '已到期') {
    error.value = '这篇日记已超过 15 天保留期限，不能恢复。'
    return
  }
  busyId.value = entry.id
  try {
    await api.restoreJournal(entry.id)
    entries.value = entries.value.filter((item) => item.id !== entry.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '日记未能恢复。'
  } finally {
    busyId.value = null
  }
}

async function removePermanently(entry: Journal) {
  if (!window.confirm('彻底删除后无法恢复这篇日记。要继续吗？')) return
  busyId.value = entry.id
  try {
    await api.deleteJournal(entry.id)
    entries.value = entries.value.filter((item) => item.id !== entry.id)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '日记未能删除。'
  } finally {
    busyId.value = null
  }
}

onMounted(() => void load())
</script>

<template>
  <section>
    <header class="page-heading">
      <div><h1>回收站</h1><p>日记从移入时起保留 15×24 小时。备份恢复不会重置删除时间。</p></div>
    </header>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="loading" class="loading-line"><span class="spinner"></span>正在整理回收站…</div>
    <div v-else-if="entries.length" class="trash-list">
      <article v-for="entry in entries" :key="entry.id" class="trash-row">
        <div class="trash-date"><Icon name="trash" :size="17" /><time>{{ entry.journalDate }}</time></div>
        <div class="trash-main"><h2>{{ entry.title }}</h2><p>{{ entry.plainText.slice(0, 120) || '图片日记' }}</p><small>{{ remaining(entry) }}</small></div>
        <div class="trash-actions">
          <button class="secondary-button" type="button" :disabled="remaining(entry) === '已到期' || busyId === entry.id" @click="restore(entry)"><Icon name="restore" :size="15" />恢复</button>
          <button class="icon-button trash-delete" type="button" title="彻底删除" :aria-label="'彻底删除' + entry.title" :disabled="busyId === entry.id" @click="removePermanently(entry)"><Icon name="close" :size="16" /></button>
        </div>
      </article>
      <button v-if="hasMore" class="secondary-button" type="button" @click="load(true)">加载更多</button>
    </div>
    <div v-else class="empty-state"><Icon name="trash" :size="27" /><strong>回收站是空的</strong><p>移入回收站的日记会在这里保留 15 天，期间可以恢复。</p></div>
    <p class="retention-note">到期日记不能正常恢复。后台清理任务会在引用检查后删除正文、快照和不再使用的图片。</p>
  </section>
</template>
