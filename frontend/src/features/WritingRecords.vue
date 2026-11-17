<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../lib/api'
import type { Journal, Screen, User, WritingDay } from '../lib/types'
import { useLocalDate } from '../lib/localDate'
import { activityStats, dateKey, offsetDate, sanitizeActivities } from '../lib/writingActivity'
import ActivityHeatmap from '../components/ActivityHeatmap.vue'
import Icon from '../components/Icon.vue'
import '../styles/activity.css'

const props = defineProps<{ user: User }>()
const emit = defineEmits<{ open: [id: number]; navigate: [screen: Screen] }>()
const today = useLocalDate(() => props.user.timeZone)
const activities = ref<WritingDay[]>([])
const coverage = ref<WritingDay[]>([])
const selectedEntries = ref<Journal[]>([])
const selectedDate = ref(today.value)
const month = ref(today.value.slice(0, 7))
const loading = ref(true)
const activityError = ref('')
const coverageLoading = ref(true)
const coverageError = ref('')
const selectedLoading = ref(false)
const selectedError = ref('')
const moreLoading = ref(false)
const moreAvailable = ref(false)
const pageSize = 20
let activityRequest = 0
let coverageRequest = 0
let selectedRequest = 0
const byDate = computed(() => sanitizeActivities(activities.value, today.value))
const coverageByDate = computed(() => new Map(coverage.value.map(day => [day.date, day.count])))
const streak = computed(() => activityStats(byDate.value, today.value))
const todayCount = computed(() => byDate.value.get(today.value) ?? 0)
const monthLabel = computed(() => Number(month.value.slice(0, 4)) + ' 年 ' + Number(month.value.slice(5)) + ' 月')
const monthEnd = computed(() => {
  const y = Number(month.value.slice(0, 4)); const m = Number(month.value.slice(5))
  return dateKey(y, m, new Date(Date.UTC(y, m, 0)).getUTCDate())
})
const monthCells = computed(() => {
  const first = month.value + '-01'
  const weekday = (new Date(first + 'T00:00:00Z').getUTCDay() + 6) % 7
  const count = Number(monthEnd.value.slice(-2))
  return [...Array.from({ length: weekday }, () => null), ...Array.from({ length: count }, (_, i) => offsetDate(first, i))]
})
const week = computed(() => {
  const index = (new Date(today.value + 'T00:00:00Z').getUTCDay() + 6) % 7
  return ['一','二','三','四','五','六','日'].map((label, i) => ({ label, date: offsetDate(today.value, i - index) }))
})
const selectedLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'UTC' }).format(new Date(selectedDate.value + 'T00:00:00Z')).replace('日', '日，').replace(/(\d+)月(\d+)日/, '$1 月 $2 日'))
const selectedTotal = computed(() => coverageByDate.value.get(selectedDate.value) ?? 0)

async function loadActivity() {
  const request = ++activityRequest
  loading.value = true
  activityError.value = ''
  try { const data = await api.activity(); if (request === activityRequest) activities.value = data }
  catch (e) { if (request === activityRequest) activityError.value = e instanceof Error ? e.message : '写作记录暂时无法载入，请重试。' }
  finally { if (request === activityRequest) loading.value = false }
}
async function loadCoverage() {
  const request = ++coverageRequest
  coverageLoading.value = true
  coverageError.value = ''
  coverage.value = []
  try { const data = await api.coverage(month.value + '-01', monthEnd.value); if (request === coverageRequest) coverage.value = data }
  catch (e) { if (request === coverageRequest) coverageError.value = e instanceof Error ? e.message : '这个月的日记日期未能载入。' }
  finally { if (request === coverageRequest) coverageLoading.value = false }
}
async function loadSelected(append = false) {
  const request = ++selectedRequest
  const date = selectedDate.value
  if (append) moreLoading.value = true
  else { selectedLoading.value = true; moreAvailable.value = false; selectedEntries.value = [] }
  selectedError.value = ''
  const offset = append ? selectedEntries.value.length : 0
  try {
    const data = await api.journals({ date, limit: pageSize, offset })
    if (request !== selectedRequest) return
    selectedEntries.value = append ? [...selectedEntries.value, ...data] : data
    moreAvailable.value = data.length === pageSize
  } catch (e) { if (request === selectedRequest) selectedError.value = e instanceof Error ? e.message : '这一天的日记未能载入。' }
  finally { if (request === selectedRequest) { selectedLoading.value = false; moreLoading.value = false } }
}
function selectDate(date: string) { selectedDate.value = date }
function shiftMonth(amount: number) {
  const date = new Date(Date.UTC(Number(month.value.slice(0, 4)), Number(month.value.slice(5)) - 1 + amount, 1))
  month.value = dateKey(date.getUTCFullYear(), date.getUTCMonth() + 1, 1).slice(0, 7)
  selectedDate.value = month.value === today.value.slice(0, 7) ? today.value : month.value + '-01'
}
function returnToday() { month.value = today.value.slice(0, 7); selectedDate.value = today.value }
watch(month, () => void loadCoverage())
watch(selectedDate, () => void loadSelected())
watch(today, (value, prior) => {
  if (selectedDate.value === prior) { month.value = value.slice(0, 7); selectedDate.value = value }
  void loadActivity()
  void loadCoverage()
})
onMounted(() => { void loadActivity(); void loadCoverage(); void loadSelected() })
onUnmounted(() => { activityRequest++; coverageRequest++; selectedRequest++ })
</script>

<template>
  <section class="writing-records">
    <header class="writing-records-heading">
      <div><h1>写作记录</h1><p>每一次落笔，都在这里留下痕迹。</p></div>
      <button class="primary-button" type="button" @click="emit('navigate', 'write')">写今天的日记</button>
    </header>
    <div v-if="loading" class="activity-loading" role="status" aria-label="正在载入写作记录"><span></span><span></span><p>正在载入写作记录…</p></div>
    <div v-else-if="activityError" class="notice error" role="alert">{{ activityError }} <button class="text-button" type="button" @click="loadActivity">重试</button></div>
    <div v-else class="activity-overview">
      <ActivityHeatmap :activities="byDate" :today="today" />
      <section class="streak-panel" aria-labelledby="streak-heading">
        <header><Icon name="flame" :size="32" /><h2 id="streak-heading">当前连续</h2></header>
        <p class="streak-number"><strong>{{ streak.current }}</strong><span>天</span></p>
        <p class="streak-message">{{ todayCount ? '今天已留下记录。' : streak.current ? '今天还没写，接续昨天的记录。' : '从今天开始，留下一格记录。' }}</p>
        <div class="streak-longest"><span>最长连续</span><strong>{{ streak.longest }}</strong><span>天</span></div>
        <ol class="streak-week" aria-label="本周写作情况"><li v-for="day in week" :key="day.date" :class="{ 'has-activity': byDate.has(day.date), 'is-today': day.date === today, 'is-future': day.date > today }" :aria-label="day.date + '，' + (day.date > today ? '尚未到来' : byDate.has(day.date) ? '已写作' : '未写作')" :title="day.date + ' · ' + (byDate.get(day.date) ?? 0) + ' 次写作活动'">{{ day.label }}</li></ol>
      </section>
    </div>
    <section class="record-recall" aria-labelledby="recall-heading">
      <header class="recall-heading"><h2 id="recall-heading">按日期回看</h2><p>按日记日期查找，也包括补写的记录。</p></header>
      <div class="recall-layout">
        <section class="recall-calendar" :aria-label="monthLabel + '日记日期'" :aria-busy="coverageLoading">
          <div class="recall-calendar-heading"><button class="icon-button" type="button" aria-label="上个月" @click="shiftMonth(-1)"><Icon name="chevron-left" :size="18" /></button><h3>{{ monthLabel }}</h3><button class="icon-button" type="button" aria-label="下个月" :disabled="month >= today.slice(0, 7)" @click="shiftMonth(1)"><Icon name="chevron-right" :size="18" /></button><button class="text-button today-link" type="button" @click="returnToday">回到今天</button></div>
          <div v-if="coverageError" class="notice error" role="alert">{{ coverageError }} <button class="text-button" type="button" @click="loadCoverage">重试</button></div>
          <div v-if="coverageLoading" class="calendar-fetching" role="status">正在载入日期…</div>
          <div class="recall-weekdays" aria-hidden="true"><span v-for="label in ['一','二','三','四','五','六','日']" :key="label">{{ label }}</span></div>
          <div class="recall-days">
            <template v-for="(date, index) in monthCells" :key="date ?? index"><span v-if="!date" aria-hidden="true"></span><button v-else class="recall-day" :class="{ selected: date === selectedDate, 'has-entry': coverageByDate.has(date), 'is-today': date === today }" type="button" :aria-label="date + (coverageError || coverageLoading ? '' : '，' + (coverageByDate.get(date) ?? 0) + ' 篇日记')" :aria-pressed="date === selectedDate" :aria-current="date === today ? 'date' : undefined" @click="selectDate(date)"><span>{{ Number(date.slice(-2)) }}</span><i v-if="coverageByDate.has(date)" aria-hidden="true"></i></button></template>
          </div>
        </section>
        <section class="recall-entries" :aria-busy="selectedLoading">
          <header><h3>{{ selectedLabel }}</h3><p v-if="!coverageLoading && !coverageError">{{ selectedTotal }} 篇日记</p></header>
          <div v-if="selectedLoading" class="entry-loading" role="status">正在打开这一天…</div>
          <template v-else>
            <button v-for="entry in selectedEntries" :key="entry.id" class="recall-entry" type="button" @click="emit('open', entry.id)"><Icon name="file" :size="24" /><span class="recall-entry-copy"><strong>{{ entry.title }}</strong><span>{{ entry.plainText || '图片日记' }}</span></span><Icon name="chevron-right" :size="18" /></button>
            <div v-if="selectedError" class="notice error" role="alert">{{ selectedError }} <button class="text-button" type="button" @click="loadSelected(selectedEntries.length > 0)">重试</button></div>
            <div v-else-if="!selectedEntries.length" class="recall-empty"><Icon name="file" :size="28" /><h4>这一天还没有日记</h4><p>普通的一天，也有值得记下的片刻。</p><button class="text-button" type="button" @click="emit('navigate', 'write')">去写日记 <Icon name="chevron-right" :size="15" /></button></div>
            <button v-if="moreAvailable && !selectedError" class="text-button recall-more" type="button" :disabled="moreLoading" @click="loadSelected(true)">{{ moreLoading ? '正在载入…' : '加载更多日记' }}</button>
          </template>
          <p class="recall-footnote">选一个日期，回看那一天。</p>
        </section>
      </div>
    </section>
  </section>
</template>
