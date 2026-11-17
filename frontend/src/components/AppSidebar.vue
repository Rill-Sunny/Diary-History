<script setup lang="ts">
import type { Screen, User } from '../lib/types'
import Icon from './Icon.vue'

defineProps<{ current: Screen; user: User; mobile?: boolean }>()
const emit = defineEmits<{ navigate: [screen: Screen]; create: []; logout: [] }>()

const mainItems: { id: Screen; label: string; icon: string }[] = [
  { id: 'write', label: '写日记', icon: 'pen' },
  { id: 'journals', label: '全部日记', icon: 'book' },
  { id: 'calendar', label: '日历与记录', icon: 'calendar' },
  { id: 'history', label: '历史上的今天', icon: 'clock' },
]

const moreItems: { id: Screen; label: string; icon: string }[] = [
  { id: 'library', label: '日记本与标签', icon: 'tag' },
  { id: 'shares', label: '分享管理', icon: 'share' },
  { id: 'data', label: '数据管理', icon: 'database' },
  { id: 'trash', label: '回收站', icon: 'trash' },
  { id: 'settings', label: '设置', icon: 'settings' },
]
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar-mobile': mobile }">
    <div class="brand"><Icon class="brand-mark" name="leaf" :size="23" /><span>留白日记</span></div>
    <button class="new-entry" type="button" @click="emit('create')">
      <Icon name="plus" :size="18" />再写一篇
    </button>
    <nav aria-label="主导航" class="nav-group">
      <button v-for="item in mainItems" :key="item.id" class="nav-item" :class="{ active: current === item.id }" :aria-current="current === item.id ? 'page' : undefined" type="button" @click="emit('navigate', item.id)">
        <span class="nav-icon"><Icon :name="item.icon" :size="18" /></span>{{ item.label }}
      </button>
    </nav>
    <nav class="nav-group" aria-label="整理与设置">
      <div class="nav-label">整理与设置</div>
      <button v-for="item in moreItems" :key="item.id" class="nav-item" :class="{ active: current === item.id }" :aria-current="current === item.id ? 'page' : undefined" type="button" @click="emit('navigate', item.id)">
        <span class="nav-icon"><Icon :name="item.icon" :size="18" /></span>{{ item.label }}
      </button>
      <button v-if="user.role === 'ADMIN'" class="nav-item" :class="{ active: current === 'admin' }" :aria-current="current === 'admin' ? 'page' : undefined" type="button" @click="emit('navigate', 'admin')">
        <span class="nav-icon"><Icon name="admin" :size="18" /></span>审核台
      </button>
    </nav>
    <div class="sidebar-spacer"></div>
    <div class="account-chip">
      <div class="avatar">
        <img v-if="user.hasAvatar" :src="'/api/settings/avatar?v=' + (user.avatarRevision || 0)" alt="" />
        <template v-else>{{ (user.nickname || user.username).slice(0, 1) }}</template>
      </div>
      <div style="min-width:0">
        <div class="account-name">{{ user.nickname || user.username }}</div>
        <div class="account-email">{{ user.email }}</div>
      </div>
      <button class="icon-button sidebar-logout" type="button" title="退出登录" aria-label="退出登录" @click="emit('logout')">
        <Icon name="logout" :size="18" />
      </button>
    </div>
  </aside>
</template>
