<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../lib/api'
import type { Journal, Screen, User } from '../lib/types'
import Icon from '../components/Icon.vue'
import WorldHistory from '../components/WorldHistory.vue'
import WritingRecords from './WritingRecords.vue'
import { useLocalDate } from '../lib/localDate'
const props = defineProps<{ user: User; mode: 'calendar' | 'history' }>()
const emit = defineEmits<{ open: [id: number]; navigate: [screen: Screen] }>()
const localDate = useLocalDate(() => props.user.timeZone)
const entries = ref<Journal[]>([])
const loading = ref(true)
const error = ref('')
const moreHistory = ref(false)
const loadingMore = ref(false)
const moreError = ref('')
const historyPageSize = 50
let loadVersion = 0
async function loadHistoryPage(offset: number) {
  const date = localDate.value
  return api.journals({ monthDay: date.slice(5), to: (Number(date.slice(0, 4)) - 1) + '-12-31', sort: 'date-desc', limit: historyPageSize, offset })
}
async function loadMoreHistory() {
  const version = loadVersion
  loadingMore.value = true
  moreError.value = ''
  try { const data = await loadHistoryPage(entries.value.length); if (version !== loadVersion) return; entries.value.push(...data); moreHistory.value = data.length === historyPageSize }
  catch (e) { if (version === loadVersion) moreError.value = e instanceof Error ? e.message : '无法载入更多往年日记。' }
  finally { if (version === loadVersion) loadingMore.value = false }
}
async function load() {
  const version = ++loadVersion
  if (props.mode !== 'history') return
  loading.value = true; error.value = ''; loadingMore.value = false; moreError.value = ''; moreHistory.value = false; entries.value = []
  try { const data = await loadHistoryPage(0); if (version !== loadVersion) return; entries.value = data; moreHistory.value = data.length === historyPageSize }
  catch (e) { if (version === loadVersion) error.value = e instanceof Error ? e.message : '回顾记录未能载入。' }
  finally { if (version === loadVersion) loading.value = false }
}
watch(() => props.mode, () => void load())
watch(localDate, () => { if (props.mode === 'history') void load() })
onMounted(load)
onUnmounted(() => { loadVersion++ })
</script>
<template>
  <WritingRecords v-if="mode === 'calendar'" :user="user" @open="emit('open', $event)" @navigate="emit('navigate', $event)" />
  <section v-else>
    <header class="page-heading"><div><h1>历史上的今天</h1></div><button class="secondary-button" type="button" @click="emit('navigate', 'calendar')">查看日历与记录</button></header>
      <div class="history-columns">
        <section class="history-column">
          <div class="history-column-heading"><Icon name="book" :size="20" /><div><h2>我的往年今日</h2><p>按你所在时区的月日寻找过往记录。</p></div></div>
          <div v-if="loading" class="loading-line" role="status"><span class="spinner"></span>正在查找往年日记…</div>
          <div v-else-if="error" class="notice error" role="alert">{{ error }} <button class="text-button" type="button" @click="load">重试</button></div>
          <template v-else>
            <article v-for="entry in entries" :key="entry.id" class="history-entry">
              <time :datetime="entry.journalDate">{{ entry.journalDate }}</time>
              <button type="button" @click="emit('open', entry.id)"><strong>{{ entry.title }}</strong><span>{{ entry.plainText.slice(0, 120) || '图片日记' }}</span></button>
            </article>
            <p v-if="!entries.length" class="history-empty">往年这一天还没有日记。</p>
            <p v-if="moreError" class="notice error" role="alert">{{ moreError }}</p>
            <button v-if="moreHistory" class="text-button" type="button" :disabled="loadingMore" @click="loadMoreHistory">{{ loadingMore ? '正在载入…' : '加载更多日记' }}</button>
          </template>
        </section>
        <WorldHistory :time-zone="user.timeZone" />
      </div>

  </section>
</template>
