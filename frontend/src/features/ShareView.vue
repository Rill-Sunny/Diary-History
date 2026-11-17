<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type ExportEntry } from '../lib/api'
import type { ShareRequest, User } from '../lib/types'
import Icon from '../components/Icon.vue'

defineProps<{ user: User }>()
const entries = ref<ExportEntry[]>([])
const requests = ref<ShareRequest[]>([])
const selectedJournal = ref('')
const days = ref(30)
const loading = ref(true)
const busyId = ref<number | null>(null)
const submitting = ref(false)
const error = ref('')
const message = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [allEntries, allRequests] = await Promise.all([api.exportable(), api.shares()])
    entries.value = allEntries
    requests.value = allRequests
  } catch (e) {
    error.value = e instanceof Error ? e.message : '分享信息未能载入。'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!selectedJournal.value || submitting.value) return
  message.value = ''
  submitting.value = true
  try {
    await api.requestShare(Number(selectedJournal.value), days.value)
    message.value = '固定版本已提交审核。首次审核通过前不会生成可访问链接。'
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '申请未能提交。'
  } finally {
    submitting.value = false
  }
}

async function revoke(item: ShareRequest) {
  const prompt = item.status === 'PENDING' ? '要取消这条待审申请吗？' : '撤销后，此链接会立即失效。要继续吗？'
  if (!window.confirm(prompt)) return
  busyId.value = item.id
  try {
    await api.revokeShare(item.id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '链接未能撤销。'
  } finally {
    busyId.value = null
  }
}

function statusText(status: ShareRequest['status']) {
  return ({ PENDING: '等待审核', APPROVED: '已发布', REJECTED: '未通过', REVOKED: '已撤销', EXPIRED: '已到期', SUPERSEDED: '已更新' })[status]
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-heading">
      <div><h1>分享管理</h1><p>私人日记不会自动公开；读者只会看到最近一次审核通过的固定版本。</p></div>
    </header>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice success" role="status">{{ message }}</div>
    <section class="share-request-panel">
      <div class="section-copy"><h2>提交一个版本</h2><p>提交后继续修改不会改变审核快照或正在分享的旧版本。</p></div>
      <form class="share-form" @submit.prevent="submit">
        <label class="form-field"><span class="form-label">选择日记</span><select v-model="selectedJournal" class="select" required><option value="">选择一篇日记</option><option v-for="entry in entries" :key="entry.id" :value="String(entry.id)">{{ entry.journalDate }} · {{ entry.title }}</option></select></label>
        <label class="form-field"><span class="form-label">新链接有效期</span><select v-model.number="days" class="select"><option :value="1">1 天</option><option :value="7">7 天</option><option :value="30">30 天</option><option :value="90">90 天</option></select></label>
        <button class="primary-button" type="submit" :disabled="!selectedJournal || submitting"><Icon name="share" :size="16" />申请审核</button>
      </form>
    </section>
    <div v-if="loading" class="loading-line"><span class="spinner"></span>正在载入分享…</div>
    <div v-else-if="requests.length" class="share-list">
      <article v-for="item in requests" :key="item.id" class="share-row">
        <div class="share-row-main"><span class="share-state" :class="'state-' + item.status.toLowerCase()">{{ statusText(item.status) }}</span><h2>{{ item.journalTitle }}</h2><p>提交于 {{ new Date(item.submittedAt + 'Z').toLocaleString('zh-CN') }}<span v-if="item.expiresAt"> · 有效至 {{ new Date(item.expiresAt + 'Z').toLocaleString('zh-CN') }}</span></p><p v-if="item.status === 'REJECTED' && item.reviewReason">{{ item.reviewReason }}</p></div>
        <div class="share-row-actions">
          <a v-if="item.status === 'APPROVED' && item.shareUrl" class="secondary-button" :href="item.shareUrl" target="_blank" rel="noreferrer">打开阅读页</a>
          <button v-if="item.status === 'APPROVED' || item.status === 'PENDING'" class="danger-button" type="button" :disabled="busyId === item.id" @click="revoke(item)">{{ item.status === 'PENDING' ? '取消申请' : '撤销链接' }}</button>
        </div>
      </article>
    </div>
    <div v-else-if="!loading" class="empty-state"><Icon name="share" :size="27" /><strong>还没有分享申请</strong><p>只有你主动提交的版本才会进入审核。</p></div>
    <div class="notice share-privacy"><strong>读者权限说明</strong><p>读者需要登录，并持有有效链接。分享页只提供已审核版本的只读内容，不提供导出、备份或原图下载入口。</p></div>
  </section>
</template>
