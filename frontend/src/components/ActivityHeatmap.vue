<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { buildYearGrid, yearSummary } from '../lib/writingActivity'

const props = defineProps<{ activities: Map<string, number>; today: string }>()
const year = ref(Number(props.today.slice(0, 4)))
const inspected = ref<string | null>(null)
const focusDate = ref(props.today)
const scroller = ref<HTMLElement | null>(null)
const years = computed(() => [...new Set([Number(props.today.slice(0, 4)), ...[...props.activities.keys()].map(d => Number(d.slice(0, 4)))])].sort((a, b) => b - a))
const grid = computed(() => buildYearGrid(year.value, props.today))
const summary = computed(() => yearSummary(props.activities, year.value))
const validDates = computed(() => grid.value.weeks.flat().filter(c => c.date && !c.future).map(c => c.date!))
const detail = computed(() => inspected.value ? inspected.value + ' · ' + (props.activities.get(inspected.value) ?? 0) + ' 次写作活动' : '按实际写作日期统计。')
function level(date: string | null) { return Math.min(4, date ? props.activities.get(date) ?? 0 : 0) }
function inspect(date: string) { inspected.value = date; focusDate.value = date }
function onKey(event: KeyboardEvent, date: string) {
  const index = validDates.value.indexOf(date)
  let target = index
  if (event.key === 'ArrowRight') target += 7
  else if (event.key === 'ArrowLeft') target -= 7
  else if (event.key === 'ArrowDown') target += 1
  else if (event.key === 'ArrowUp') target -= 1
  else if (event.key === 'Home') target = 0
  else if (event.key === 'End') target = validDates.value.length - 1
  else return
  event.preventDefault()
  const next = validDates.value[Math.max(0, Math.min(validDates.value.length - 1, target))]
  if (!next) return
  inspect(next)
  void nextTick(() => scroller.value?.querySelector<HTMLButtonElement>('[data-date="' + next + '"]')?.focus({ preventScroll: false }))
}
watch(year, async () => {
  inspected.value = null
  focusDate.value = validDates.value.includes(props.today) ? props.today : validDates.value[validDates.value.length - 1] ?? ''
  await nextTick()
  revealFocusDate()
})
function revealFocusDate() {
  const container = scroller.value
  const cell = container?.querySelector<HTMLElement>('[data-date="' + focusDate.value + '"]')
  if (!container || !cell) return
  const bounds = container.getBoundingClientRect()
  const item = cell.getBoundingClientRect()
  if (item.right > bounds.right - 14) container.scrollLeft += item.right - bounds.right + 14
  else if (item.left < bounds.left + 26) container.scrollLeft -= bounds.left + 26 - item.left
}
let resizeObserver: ResizeObserver | undefined
onMounted(() => {
  resizeObserver = new ResizeObserver(revealFocusDate)
  if (scroller.value) resizeObserver.observe(scroller.value)
})
onUnmounted(() => resizeObserver?.disconnect())
watch(() => props.today.slice(0, 4), (value, prior) => { if (year.value === Number(prior)) year.value = Number(value) })
</script>

<template>
  <section class="activity-panel" aria-labelledby="activity-title">
    <header class="activity-panel-heading">
      <div class="activity-heading-copy"><h2 id="activity-title">写作足迹</h2><p><strong>{{ summary.days }}</strong> 天写作<span aria-hidden="true"> · </span><strong>{{ summary.total }}</strong> 次活动</p></div>
      <label class="activity-year"><span class="sr-only">活动年份</span><select v-model.number="year" aria-label="活动年份"><option v-for="option in years" :key="option" :value="option">{{ option }} 年</option></select></label>
    </header>
    <div ref="scroller" class="activity-scroll" role="group" :aria-label="year + ' 年写作活动热力图，使用方向键查看日期'">
      <div class="activity-chart" :style="{ '--week-count': grid.weeks.length }">
        <div class="activity-months" aria-hidden="true"><span v-for="item in grid.months" :key="item.label" :style="{ gridColumn: item.column + 1 }">{{ item.label }}</span></div>
        <div class="activity-weekdays" aria-hidden="true"><span v-for="label in ['一','二','三','四','五','六','日']" :key="label">{{ label }}</span></div>
        <div class="activity-weeks">
          <div v-for="(week, index) in grid.weeks" :key="index" class="activity-week">
            <template v-for="(cell, row) in week" :key="cell.date ?? row">
              <span v-if="!cell.date || cell.future" class="activity-cell" :class="{ 'is-outside': !cell.date, 'is-future': cell.future }" :title="cell.date ? cell.date + ' · 尚未到来' : undefined" aria-hidden="true"></span>
              <button v-else class="activity-cell" :class="['activity-level-' + level(cell.date), { 'is-today': cell.date === today, 'is-inspected': cell.date === inspected }]" type="button" :data-date="cell.date" :tabindex="cell.date === focusDate ? 0 : -1" :aria-label="cell.date + '，' + (activities.get(cell.date) ?? 0) + ' 次写作活动'" :title="cell.date + ' · ' + (activities.get(cell.date) ?? 0) + ' 次写作活动'" @mouseenter="inspected = cell.date" @focus="inspect(cell.date)" @click="inspect(cell.date)" @keydown="onKey($event, cell.date)"></button>
            </template>
          </div>
        </div>
      </div>
    </div>
    <p class="activity-scroll-hint">左右滑动，查看全年记录</p>
    <footer class="activity-footer">
      <div class="activity-legend" aria-label="颜色由浅到深表示 0、1、2、3、4 次及以上活动"><span>较少</span><i v-for="n in 5" :key="n" :class="'activity-level-' + (n - 1)"></i><span>较多</span></div>
      <p class="activity-detail" role="status" aria-live="polite">{{ detail }}</p>
    </footer>
  </section>
</template>
