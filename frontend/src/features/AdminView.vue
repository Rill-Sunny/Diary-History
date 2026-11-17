<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type TagIconReview } from '../lib/api'
import type { ShareRequest, ShareSnapshot } from '../lib/types'
import Icon from '../components/Icon.vue'
import SharedContent from '../components/SharedContent.vue'

const queue = ref<ShareRequest[]>([])
const selectedId = ref<number | null>(null)
const snapshot = ref<ShareSnapshot | null>(null)
const reason = ref('')
const busy = ref(false)
const error = ref('')
const message = ref('')
const icons = ref<TagIconReview[]>([])
const iconReasons = ref<Record<number, string>>({})
const iconError = ref('')
const iconsLoading = ref(false)
const selectedRequest = computed(() => queue.value.find((item) => item.id === selectedId.value) ?? null)

async function load() {
  try {
    queue.value = await api.reviewQueue()
    if (selectedId.value && queue.value.some((item) => item.id === selectedId.value)) await open(selectedId.value)
    else if (queue.value[0]) await open(queue.value[0].id)
    else {
      selectedId.value = null
      snapshot.value = null
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '审核队列未能载入。'
  }
}

async function loadIcons() {
  iconsLoading.value = true
  iconError.value = ''
  try { icons.value = await api.tagIconReviews() }
  catch (e) { iconError.value = e instanceof Error ? e.message : '图标审核队列未能载入。' }
  finally { iconsLoading.value = false }
}

async function decideIcon(id: number, decision: 'approve' | 'reject') {
  const reason = iconReasons.value[id]?.trim()
  if (decision === 'reject' && !reason) {
    iconError.value = '请填写拒绝原因。'
    return
  }
  busy.value = true
  iconError.value = ''
  try {
    await api.decideTagIcon(id, decision, reason)
    delete iconReasons.value[id]
    message.value = decision === 'approve' ? '私人标签图标已通过。' : '私人标签图标已拒绝。'
    await loadIcons()
  } catch (e) {
    iconError.value = e instanceof Error ? e.message : '图标审核未完成。'
  } finally { busy.value = false }
}

function refresh() { return Promise.all([load(), loadIcons()]) }

async function open(id: number) {
  selectedId.value = id
  snapshot.value = null
  error.value = ''
  try {
    const result = await api.reviewSnapshot(id)
    if (selectedId.value === id) snapshot.value = result
  } catch (e) {
    error.value = e instanceof Error ? e.message : '审核快照未能载入。'
  }
}

async function approve() {
  if (!selectedId.value || !window.confirm('批准的内容将成为分享读者可见的版本。确认检查了固定快照吗？')) return
  busy.value = true
  try {
    await api.approveShare(selectedId.value)
    message.value = '该提交版本已审核通过。'
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '审核未完成。'
  } finally {
    busy.value = false
  }
}

async function reject() {
  if (!selectedId.value || !reason.value.trim()) {
    error.value = '请填写简短的拒绝原因。'
    return
  }
  busy.value = true
  try {
    await api.rejectShare(selectedId.value, reason.value.trim())
    reason.value = ''
    message.value = '提交版本已拒绝，作者的私人日记不会被删除。'
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '审核未完成。'
  } finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <section>
    <header class="page-heading">
      <div><h1>审核台</h1><p>只处理作者主动提交的固定分享版本；普通私人日记不会进入审核。</p></div>
      <button class="secondary-button" type="button" :disabled="busy" @click="refresh">刷新队列</button>
    </header>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice success" role="status">{{ message }}</div>
    <div class="admin-layout">
      <aside class="review-queue">
        <div class="queue-heading"><h2>待审核版本</h2><span>{{ queue.length }}</span></div>
        <button v-for="item in queue" :key="item.id" class="review-queue-item" :class="{ selected: item.id === selectedId }" type="button" @click="open(item.id)">
          <span>{{ item.status === 'PENDING' ? '待审核' : '已处理' }}</span><strong>{{ item.journalTitle }}</strong><small>{{ new Date(item.submittedAt).toLocaleString('zh-CN') }}</small>
        </button>
        <div v-if="!queue.length" class="queue-empty">目前没有待审核的分享申请。</div>
        <div class="queue-note">队列只显示申请材料和审核记录，不提供未提交的日记或原图入口。</div>
      </aside>
      <section class="review-detail">
        <template v-if="selectedRequest && snapshot">
          <div class="review-detail-head"><div><span class="review-kicker">固定申请快照 · V{{ snapshot.versionNumber }}</span><h2>{{ snapshot.journalTitle }}</h2><p>{{ snapshot.journalDate }} · 提交于 {{ new Date(snapshot.submittedAt).toLocaleString('zh-CN') }}</p></div><span class="share-state state-pending">等待审核</span></div>
          <div class="review-snapshot">
            <SharedContent :node="snapshot.content" />
          </div>
          <label class="form-field reject-reason"><span class="form-label">拒绝原因（仅拒绝时必填）</span><textarea v-model="reason" class="textarea" maxlength="500" placeholder="说明需要修改的内容"></textarea></label>
          <div class="review-actions"><button class="danger-button" type="button" :disabled="busy" @click="reject">拒绝</button><button class="primary-button" type="button" :disabled="busy || selectedRequest.status !== 'PENDING'" @click="approve"><Icon name="check" :size="16" />批准并发布此版本</button></div>
        </template>
        <div v-else-if="selectedRequest" class="loading-line"><span class="spinner"></span>正在读取申请快照…</div>
        <div v-else class="empty-state"><Icon name="admin" :size="27" /><strong>没有待处理的申请</strong><p>作者主动提交的分享版本会显示在这里。</p></div>
      </section>
    </div>
    <section class="icon-review-note">
      <h2>私人标签图标审核</h2>
      <p v-if="iconError" class="notice error" role="alert">{{ iconError }}</p>
      <p v-if="iconsLoading" role="status">正在读取图标申请…</p>
      <p v-else-if="!icons.length && !iconError">目前没有图标审核申请。</p>
      <article v-for="item in icons" :key="item.id" class="tag-review-item">
        <img :src="item.imageUrl" :alt="item.tagName + ' 的申请图标'" width="72" height="72" />
        <div>
          <h3>{{ item.tagName }}</h3>
          <p>{{ item.submitter }} · {{ new Date(item.submittedAt).toLocaleString('zh-CN') }}</p>
          <p v-if="item.status !== 'PENDING'">{{ { APPROVED: '已通过', REJECTED: '已拒绝', SUPERSEDED: '已替换' }[item.status] ?? item.status }}<span v-if="item.reason"> · {{ item.reason }}</span></p>
          <template v-else>
            <label class="form-field"><span class="form-label">拒绝原因（仅拒绝时必填）</span><input v-model="iconReasons[item.id]" class="input" maxlength="500" /></label>
            <div class="review-actions"><button type="button" class="danger-button" :disabled="busy" @click="decideIcon(item.id, 'reject')">拒绝</button><button type="button" class="primary-button" :disabled="busy" @click="decideIcon(item.id, 'approve')">批准图标</button></div>
          </template>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped>
.tag-review-item { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 16px; padding-block: 16px; border-top: 1px solid var(--line); }
.tag-review-item img { object-fit: contain; border-radius: 8px; background: var(--surface); }
.tag-review-item h3 { margin: 0 0 6px; }
.tag-review-item label { margin-top: 12px; }
</style>
