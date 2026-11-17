<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api } from '../lib/api'
import type { ShareSnapshot } from '../lib/types'
import Icon from './Icon.vue'
import SharedContent from './SharedContent.vue'

const props = defineProps<{ token: string }>()
const snapshot = ref<ShareSnapshot | null>(null)
const busy = ref(true)
const error = ref('')

async function load() {
  busy.value = true
  error.value = ''
  snapshot.value = null
  try {
    snapshot.value = await api.readShare(props.token)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '分享内容暂不可用。'
  } finally {
    busy.value = false
  }
}

watch(() => props.token, load)
onMounted(load)
</script>

<template>
  <main class="shared-page">
    <header class="shared-brand"><Icon name="leaf" :size="20" /><span>留白日记</span><span class="shared-label">审核通过的只读版本</span></header>
    <div v-if="busy" class="loading-line"><span class="spinner"></span>正在读取分享内容…</div>
    <section v-else-if="error" class="empty-state"><Icon name="lock" :size="27" /><strong>分享链接暂不可用</strong><p>{{ error }}</p><p>链接可能已到期、撤销或需要登录。</p></section>
    <article v-else-if="snapshot" class="shared-article">
      <time>{{ snapshot.journalDate }}</time>
      <h1>{{ snapshot.journalTitle }}</h1>
      <SharedContent :node="snapshot.content" />
      <footer>此页面展示作者提交并通过审核的固定版本。</footer>
    </article>
  </main>
</template>
