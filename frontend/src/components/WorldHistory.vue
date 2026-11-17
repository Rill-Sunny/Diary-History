<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from './Icon.vue'
import snapshots from '../data/world-history.json'
import { useLocalDate } from '../lib/localDate'

const props = defineProps<{ timeZone: string; preview?: boolean }>()
const emit = defineEmits<{ open: [] }>()
const date = useLocalDate(() => props.timeZone)
const snapshot = computed(() => snapshots[date.value.slice(5) as keyof typeof snapshots])
const events = computed(() => props.preview ? snapshot.value?.events.slice(0, 2) : snapshot.value?.events)
const dateLabel = computed(() => `${Number(date.value.slice(5, 7))} 月 ${Number(date.value.slice(8))} 日`)
const source = computed(() => `https://zh.wikipedia.org/w/index.php?oldid=${snapshot.value?.revision}`)
const copyResult = ref<{ date: string; year: number; ok: boolean }>()

function yearLabel(year: number) {
  return year < 0 ? `公元前 ${-year} 年` : `${year} 年`
}

async function copyEvent(event: { year: number; text: string }) {
  const selectedDate = date.value
  try {
    await navigator.clipboard.writeText(`${yearLabel(event.year)} ${dateLabel.value}，${event.text}\n来源：中文维基百科 ${source.value}`)
    copyResult.value = { date: selectedDate, year: event.year, ok: true }
  } catch {
    copyResult.value = { date: selectedDate, year: event.year, ok: false }
  }
}
</script>

<template>
  <section :class="preview ? 'aside-section world-history-preview' : 'history-column'">
    <div v-if="preview" class="aside-section-head">
      <h2>历史上的今天</h2>
      <button class="text-button" type="button" @click="emit('open')">查看全部</button>
    </div>
    <div v-else class="history-column-heading">
      <Icon name="clock" :size="20" />
      <div><h2>世界历史上的今天</h2><p>{{ dateLabel }}的历史事件</p></div>
    </div>
    <ul v-if="events?.length" class="world-history-list">
      <li v-for="event in events" :key="event.year">
        <strong>{{ yearLabel(event.year) }}</strong>
        <div>
          <p>{{ event.text }}</p>
          <button v-if="!preview" class="text-button copy-event" type="button" :aria-label="`复制 ${yearLabel(event.year)}的事件引用`" @click="copyEvent(event)">复制引用</button>
          <span v-if="copyResult?.date === date && copyResult.year === event.year" class="copy-status" role="status">{{ copyResult.ok ? '已复制' : '请选中条目文字后手动复制' }}</span>
        </div>
      </li>
    </ul>
    <p v-else class="history-empty">暂无 {{ dateLabel }}的历史事件资料。</p>
    <p v-if="events?.length" class="world-source">
      来源 <a :href="source" target="_blank" rel="noopener noreferrer">中文维基百科</a>
      <span aria-hidden="true"> · </span>
      <a href="https://creativecommons.org/licenses/by-sa/4.0/deed.zh-hans" target="_blank" rel="noopener noreferrer">CC BY-SA 4.0</a>
    </p>
  </section>
</template>

<style scoped>
.world-history-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.world-history-list li {
  display: grid;
  grid-template-columns: minmax(4.5rem, auto) minmax(0, 1fr);
  gap: 0.75rem;
  padding: 0.85rem 0;
  border-bottom: 1px solid var(--line);
  font-size: 0.875rem;
  line-height: 1.65;
}

.world-history-list strong { color: var(--accent-strong); }
.world-history-list p { margin: 0; }
.copy-event { margin-top: 0.2rem; font-size: 0.75rem; }
.copy-status { margin-left: 0.5rem; color: var(--muted); font-size: 0.75rem; }
.world-history-preview li { font-size: 0.8125rem; }
.world-source { margin: 0.75rem 0 0; }
.world-source a { color: var(--accent-strong); text-underline-offset: 0.2em; }

@media (max-width: 1180px) {
  .world-history-preview { margin-bottom: 1rem; padding-bottom: 0.5rem; }
  .world-history-preview li { border-bottom: 0; }
  .world-history-preview li + li { display: none; }
  .world-history-preview .world-source { margin-top: 0; }
}
</style>
