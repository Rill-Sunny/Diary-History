<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, api } from './lib/api'
import { clearOwnerCache, flushPendingDrafts, getDraft, listDrafts } from './lib/offline'
import { normalizeAccent } from './lib/appearance'
import type { Screen, User } from './lib/types'
import AppSidebar from './components/AppSidebar.vue'
import AuthView from './components/AuthView.vue'
import Icon from './components/Icon.vue'
const WriteView = defineAsyncComponent(() => import('./features/WriteView.vue'))
const JournalListView = defineAsyncComponent(() => import('./features/JournalListView.vue'))
const LibraryView = defineAsyncComponent(() => import('./features/LibraryView.vue'))
const RecordsView = defineAsyncComponent(() => import('./features/RecordsView.vue'))
const ShareView = defineAsyncComponent(() => import('./features/ShareView.vue'))
const DataView = defineAsyncComponent(() => import('./features/DataView.vue'))
const TrashView = defineAsyncComponent(() => import('./features/TrashView.vue'))
const SettingsView = defineAsyncComponent(() => import('./features/SettingsView.vue'))
const AdminView = defineAsyncComponent(() => import('./features/AdminView.vue'))
const SharedReadView = defineAsyncComponent(() => import('./components/SharedReadView.vue'))

const user = ref<User | null>(null)
const screen = ref<Screen>('write')
const selectedJournalId = ref<number | null>(null)
const selectedDraftKey = ref<string>()
const newEntryToken = ref(0)
const offline = ref(false)
const booting = ref(true)
const connectionMessage = ref('')
const navigationDialog = ref<HTMLDialogElement | null>(null)
const navigationOpen = ref(false)
const writer = ref<{ beforeLeave: () => Promise<boolean>; flushEdits: () => Promise<boolean>; activeKey: () => string } | null>(null)
const accountAction = ref(['verify', 'changeEmail', 'reset'].some((key) => new URLSearchParams(location.search).has(key)))
let switching = false
let backgroundSync: Promise<void> | null = null
const screenLabels: Record<Screen, string> = {
  write: '写日记', journals: '全部日记', calendar: '日历与记录', history: '历史上的今天',
  library: '日记本与标签', shares: '分享管理', data: '数据管理', trash: '回收站', settings: '设置', admin: '审核台',
}
const shareToken = computed(() => location.pathname.match(/^\/shared\/([a-f0-9]{64})\/?$/)?.[1] ?? '')

function openNavigation() {
  navigationDialog.value?.showModal()
  navigationOpen.value = true
}

function closeNavigation() {
  navigationDialog.value?.close()
  navigationOpen.value = false
}

function remember(userData: User) {
  user.value = userData
  localStorage.setItem('liubai:last-user', JSON.stringify(userData))
  localStorage.setItem('liubai:theme', userData.theme || 'light')
  localStorage.setItem('liubai:accent', normalizeAccent(userData.accent))
  applyPreferences(userData.theme, userData.accent)
  offline.value = false
  connectionMessage.value = ''
}

function restoreOfflineEntry(ownerId: number) {
  const remembered = Number(localStorage.getItem('liubai:open-journal:' + ownerId))
  selectedJournalId.value = Number.isSafeInteger(remembered) && remembered > 0 ? remembered : null
}

async function restoreInterruptedEntry(ownerId: number) {
  const remembered = Number(localStorage.getItem('liubai:open-journal:' + ownerId))
  if (!Number.isSafeInteger(remembered) || remembered <= 0) return
  const draft = await getDraft(ownerId, remembered).catch(() => undefined)
  if (draft && !draft.trashRequested && user.value?.id === ownerId) selectedJournalId.value = remembered
}

function rememberJournal(entry: { id: number } | null) {
  if (!user.value) return
  const key = 'liubai:open-journal:' + user.value.id
  if (entry) localStorage.setItem(key, String(entry.id))
  else localStorage.removeItem(key)
}

function syncOtherEntries() {
  if (!user.value || offline.value || !navigator.onLine || backgroundSync) return
  const ownerId = user.value.id
  if (screen.value === 'write' && !writer.value) return
  const activeKey = screen.value === 'write' ? writer.value?.activeKey() : undefined
  backgroundSync = flushPendingDrafts(ownerId, activeKey).catch(() => undefined).finally(() => {
    backgroundSync = null
    window.dispatchEvent(new Event('liubai:drafts-synced'))
  })
}

function cachedUser() {
  try {
    return JSON.parse(localStorage.getItem('liubai:last-user') ?? 'null') as User | null
  } catch {
    return null
  }
}

function applyPreferences(theme: string, accent: string) {
  document.documentElement.dataset.theme = theme === 'dark' ? 'dark' : 'light'
  document.documentElement.dataset.accent = normalizeAccent(accent)
}

async function refreshSession() {
  if (!navigator.onLine) {
    user.value = cachedUser()
    offline.value = Boolean(user.value)
    if (user.value && booting.value) restoreOfflineEntry(user.value.id)
    connectionMessage.value = user.value ? '离线时只显示此账号已缓存的内容。' : '当前离线，登录后才能使用此设备上的日记缓存。'
    return
  }
  try {
    const current = await api.me()
    if (localStorage.getItem('liubai:logout-pending') === String(current.id)) {
      await api.logout()
      localStorage.removeItem('liubai:logout-pending')
      user.value = null
      offline.value = false
      return
    }
    const prior = cachedUser()
    if (prior && prior.id !== current.id) {
      user.value = current
      selectedJournalId.value = null
      selectedDraftKey.value = undefined
      screen.value = 'write'
    } else {
      user.value = current
    }
    localStorage.setItem('liubai:last-user', JSON.stringify(current))
    localStorage.setItem('liubai:theme', current.theme || 'light')
    localStorage.setItem('liubai:accent', normalizeAccent(current.accent))
    applyPreferences(current.theme, current.accent)
    offline.value = false
    connectionMessage.value = ''
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      const prior = cachedUser()
      const details = error.details as { code?: string }
      if (prior && details?.code === 'account_deactivated') await removeAccountCache(prior.id)
      localStorage.removeItem('liubai:last-user')
      localStorage.removeItem('liubai:logout-pending')
      user.value = null
      offline.value = false
    } else {
      user.value = cachedUser()
      offline.value = Boolean(user.value)
      if (user.value && booting.value) restoreOfflineEntry(user.value.id)
      connectionMessage.value = user.value
        ? '服务器暂不可达；仅打开本机留存的编辑内容，尚未同步的数据会保留。'
        : '无法连接日记服务。请确认后端与数据库已启动。'
    }
  }
}

async function login(userData: User) {
  accountAction.value = false
  localStorage.removeItem('liubai:logout-pending')
  remember(userData)
  selectedJournalId.value = null
  selectedDraftKey.value = undefined
  screen.value = 'write'
  newEntryToken.value = 0
  syncOtherEntries()
}

async function leaveWriter(action: () => void) {
  if (switching) return
  switching = true
  try {
    if (screen.value === 'write' && writer.value && !(await writer.value.beforeLeave())) return
    if (backgroundSync) await backgroundSync
    action()
  } finally {
    switching = false
  }
}

function navigate(next: Screen) {
  closeNavigation()
  if (screen.value === next) return
  void leaveWriter(() => {
    screen.value = next
    if (next === 'write') {
      selectedJournalId.value = null
      selectedDraftKey.value = undefined
      newEntryToken.value = 0
      if (offline.value && user.value) restoreOfflineEntry(user.value.id)
    } else syncOtherEntries()
  })
}

function createEntry() {
  closeNavigation()
  void leaveWriter(() => {
    rememberJournal(null)
    selectedJournalId.value = null
    selectedDraftKey.value = undefined
    screen.value = 'write'
    newEntryToken.value += 1
  })
}

function openJournal(id: number) {
  void leaveWriter(() => {
    selectedJournalId.value = id
    selectedDraftKey.value = undefined
    screen.value = 'write'
  })
}

function openLocalDraft(key: string) {
  void leaveWriter(() => {
    selectedJournalId.value = null
    selectedDraftKey.value = key
    screen.value = 'write'
  })
}

async function logout() {
  closeNavigation()
  if (!user.value) return
  if (writer.value && !(await writer.value.flushEdits())) return
  const pending = await listDrafts(user.value.id).catch(() => [])
  const prompt = pending.length
    ? '本机还有 ' + pending.length + ' 条待同步编辑。退出后会保留这些内容，并按账号隔离；要继续退出吗？'
    : '要退出留白日记吗？'
  if (!window.confirm(prompt)) return
  const ownerId = user.value.id
  try {
    if (!offline.value) await api.logout()
    else localStorage.setItem('liubai:logout-pending', String(ownerId))
  } catch {
    localStorage.setItem('liubai:logout-pending', String(ownerId))
    connectionMessage.value = '退出请求未能送达服务端；本机仍会清除当前页面的账号状态。'
  }
  user.value = null
  offline.value = false
  localStorage.removeItem('liubai:last-user')
  screen.value = 'write'
}

async function removeAccountCache(ownerId: number) {
  await clearOwnerCache(ownerId).catch(() => undefined)
  localStorage.removeItem('liubai:open-journal:' + ownerId)
}

function onDeactivated() {
  if (user.value) void removeAccountCache(user.value.id)
  user.value = null
  offline.value = false
  localStorage.removeItem('liubai:last-user')
  screen.value = 'write'
}

function onOnline() {
  void refreshSession().then(syncOtherEntries)
}

function onOffline() {
  if (!user.value) return
  offline.value = true
  connectionMessage.value = '网络已断开。本机编辑和待同步内容会按账号分别保存。'
}

function onAccountStorage(event: StorageEvent) {
  if (event.key !== 'liubai:last-user') return
  const next = cachedUser()
  if (next?.id === user.value?.id) return
  user.value = null
  selectedJournalId.value = null
  selectedDraftKey.value = undefined
  if (next) void refreshSession().then(syncOtherEntries)
}

onMounted(async () => {
  applyPreferences(localStorage.getItem('liubai:theme') ?? 'light', normalizeAccent(localStorage.getItem('liubai:accent')))
  await refreshSession()
  if (user.value && !offline.value) await restoreInterruptedEntry(user.value.id)
  booting.value = false
  syncOtherEntries()
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  window.addEventListener('storage', onAccountStorage)
})

onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  window.removeEventListener('storage', onAccountStorage)
})
</script>

<template>
  <div v-if="booting" class="boot-screen" role="status"><span class="spinner"></span>正在打开留白日记</div>
  <AuthView v-else-if="!user || accountAction" @authenticated="login" />
  <SharedReadView v-else-if="shareToken" :token="shareToken" />
  <div v-else class="app-shell">
    <a class="skip-link" href="#main-content">跳到正文</a>
    <AppSidebar :current="screen" :user="user" @navigate="navigate" @create="createEntry" @logout="logout" />
    <div id="main-content" class="workspace" tabindex="-1">
      <header class="workspace-topbar">
        <div class="workspace-location"><span>我的空间</span><span aria-hidden="true">/</span><strong>{{ screenLabels[screen] }}</strong></div>
        <span class="privacy-label"><Icon name="lock" :size="14" />默认私密</span>
      </header>
      <header class="mobile-topbar">
        <button class="icon-button" type="button" aria-label="打开完整导航" :aria-expanded="navigationOpen" aria-controls="mobile-navigation" @click="openNavigation"><Icon name="menu" /></button>
        <div class="brand"><Icon class="brand-mark" name="leaf" :size="21" /><span>留白日记</span></div>
        <div class="mobile-topbar-actions">
          <button class="icon-button" type="button" aria-label="设置" title="设置" @click="navigate('settings')"><Icon name="settings" /></button>
        </div>
      </header>
      <div v-if="offline || connectionMessage" class="offline-banner" role="status">
        <span class="status-dot"></span>{{ connectionMessage || '离线状态；服务器数据尚未同步。' }}
        <button v-if="offline" class="text-button" type="button" @click="onOnline">检查连接</button>
      </div>
      <main v-if="screen === 'write'" class="page page-write">
        <WriteView
          ref="writer"
          :key="user.id"
          :user="user"
          :offline="offline"
          :journal-id="selectedJournalId"
          :new-entry-token="newEntryToken"
          :local-draft-key="selectedDraftKey"
          @opened="rememberJournal($event); syncOtherEntries()"
          @changed="rememberJournal"
          @create="createEntry"
          @navigate="navigate"
          @open="openJournal"
        />
      </main>
      <main v-else-if="screen === 'journals'" class="page">
        <JournalListView :user="user" :offline="offline" @open="openJournal" @open-local="openLocalDraft" @create="createEntry" />
      </main>
      <main v-else-if="screen === 'library'" class="page">
        <LibraryView :user="user" />
      </main>
      <main v-else-if="screen === 'calendar' || screen === 'history'" class="page">
        <RecordsView :user="user" :mode="screen" @open="openJournal" @navigate="navigate" />
      </main>
      <main v-else-if="screen === 'shares'" class="page">
        <ShareView :user="user" />
      </main>
      <main v-else-if="screen === 'data'" class="page">
        <DataView :user="user" />
      </main>
      <main v-else-if="screen === 'trash'" class="page">
        <TrashView :user="user" @open="openJournal" />
      </main>
      <main v-else-if="screen === 'settings'" class="page">
        <SettingsView :user="user" @preferences="applyPreferences" @logout="logout" @deactivated="onDeactivated" @updated="remember" />
      </main>
      <main v-else-if="screen === 'admin' && user.role === 'ADMIN'" class="page">
        <AdminView />
      </main>
      <main v-else class="page">
        <section class="empty-state"><strong>当前账号没有此页面权限</strong><p>审核台仅对经管理员初始化的账号开放。</p><button class="secondary-button" type="button" @click="navigate('write')">回到写日记</button></section>
      </main>
      <nav class="mobile-nav" aria-label="移动端导航">
        <button type="button" :class="{ active: screen === 'write' }" :aria-current="screen === 'write' ? 'page' : undefined" @click="navigate('write')"><Icon name="pen" :size="20" />写日记</button>
        <button type="button" :class="{ active: screen === 'journals' }" :aria-current="screen === 'journals' ? 'page' : undefined" @click="navigate('journals')"><Icon name="book" :size="20" />日记</button>
        <button class="mobile-plus" type="button" aria-label="再写一篇" @click="createEntry"><Icon name="plus" :size="25" /></button>
        <button type="button" :class="{ active: screen === 'calendar' || screen === 'history' }" @click="navigate('calendar')"><Icon name="clock" :size="20" />回顾</button>
        <button type="button" :class="{ active: ['library', 'shares', 'data', 'trash', 'settings', 'admin'].includes(screen) }" :aria-expanded="navigationOpen" aria-controls="mobile-navigation" @click="openNavigation"><Icon name="menu" :size="20" />更多</button>
      </nav>
    </div>
    <dialog id="mobile-navigation" ref="navigationDialog" class="navigation-dialog" aria-label="完整导航" @close="navigationOpen = false" @click="($event.target === navigationDialog) && closeNavigation()">
      <div class="navigation-sheet">
        <button class="icon-button navigation-close" type="button" aria-label="关闭导航" @click="closeNavigation"><Icon name="close" /></button>
        <AppSidebar mobile :current="screen" :user="user" @navigate="navigate" @create="createEntry" @logout="logout" />
      </div>
    </dialog>
  </div>
</template>
