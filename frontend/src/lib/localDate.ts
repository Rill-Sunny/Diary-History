import { computed, onMounted, onUnmounted, ref } from 'vue'

export function localDateAt(timeZone: string, instant: Date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(instant)
  const part = (type: string) => parts.find((entry) => entry.type === type)!.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function useLocalDate(timeZone: () => string) {
  const now = ref(new Date())
  const update = () => { now.value = new Date() }
  let timer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    timer = setInterval(update, 30_000)
    window.addEventListener('focus', update)
  })
  onUnmounted(() => {
    clearInterval(timer)
    window.removeEventListener('focus', update)
  })
  return computed(() => localDateAt(timeZone() || 'Asia/Shanghai', now.value))
}
