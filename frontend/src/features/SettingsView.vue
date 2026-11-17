<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../lib/api'
import { clearOwnerCache } from '../lib/offline'
import { normalizeAccent } from '../lib/appearance'
import type { User } from '../lib/types'
import ReminderSettings from './ReminderSettings.vue'

const props = defineProps<{ user: User }>()
const emit = defineEmits<{ preferences: [theme: string, accent: string]; logout: []; deactivated: []; updated: [user: User] }>()
const nickname = ref(props.user.nickname)
const timeZone = ref(props.user.timeZone || 'Asia/Shanghai')
const theme = ref(localStorage.getItem('liubai:theme') ?? props.user.theme ?? 'light')
const accent = ref(normalizeAccent(localStorage.getItem('liubai:accent') ?? props.user.accent))
const saved = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const avatarError = ref('')
const avatarStatus = ref('')
const maxAvatarBytes = 2 * 1024 * 1024
const password = ref('')
const newEmail = ref('')
const emailPassword = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const passwordChanged = ref(false)
const timeZoneValues = (Intl as typeof Intl & { supportedValuesOf?: (key: 'timeZone') => string[] }).supportedValuesOf
const timeZones = Array.from(new Set([
  'Asia/Shanghai', 'Asia/Hong_Kong', 'UTC', 'Europe/London', 'America/Los_Angeles', timeZone.value,
  ...(timeZoneValues ? timeZoneValues('timeZone') : []),
]))
const labels: Record<string, string> = {
  'Asia/Shanghai': 'Asia/Shanghai（北京时间）',
  'Asia/Hong_Kong': 'Asia/Hong_Kong（香港时间）',
}

function updateTheme() {
  localStorage.setItem('liubai:theme', theme.value)
  localStorage.setItem('liubai:accent', accent.value)
  emit('preferences', theme.value, accent.value)
}

async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  saved.value = false
  try {
    const updatedUser = await api.updateSettings({ nickname: nickname.value.trim(), timeZone: timeZone.value, theme: theme.value, accent: accent.value })
    emit('updated', updatedUser)
    updateTheme()
    saved.value = true
    message.value = '设置已保存。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '设置未能保存。'
  } finally {
    busy.value = false
  }
}

async function accountAction(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '账号资料未能更新。'
  } finally {
    busy.value = false
  }
}

async function changeAvatar(action: () => Promise<User>, hasAvatar: boolean) {
  busy.value = true
  avatarError.value = ''
  avatarStatus.value = hasAvatar ? '正在上传头像…' : '正在移除头像…'
  try {
    const updated = await action()
    if (!updated || updated.hasAvatar !== hasAvatar) throw new Error('头像更新结果无法确认，请刷新后重试。')
    emit('updated', { ...updated, avatarRevision: hasAvatar ? Date.now() : undefined })
    avatarStatus.value = hasAvatar ? '头像已更新。' : '头像已移除。'
  } catch (e) {
    avatarStatus.value = ''
    avatarError.value = e instanceof Error ? e.message : '头像未能更新。'
  } finally {
    busy.value = false
  }
}

function uploadAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > maxAvatarBytes || file.size === 0) {
    avatarStatus.value = ''
    avatarError.value = file.size === 0 ? '图片文件为空，请重新选择。' : '图片超过 2 MB，请选择较小的图片。'
    return
  }
  void changeAvatar(() => api.uploadAvatar(file), true)
}

function deleteAvatar() {
  return changeAvatar(api.deleteAvatar, false)
}

function requestEmailChange() {
  return accountAction(async () => {
    message.value = (await api.requestEmailChange(newEmail.value.trim(), emailPassword.value)).message
    newEmail.value = ''
    emailPassword.value = ''
  })
}

function changePassword() {
  return accountAction(async () => {
    message.value = (await api.changePassword(currentPassword.value, newPassword.value)).message
    currentPassword.value = ''
    newPassword.value = ''
    passwordChanged.value = true
  })
}

function reloadForLogin() {
  window.location.reload()
}

async function deactivate() {
  error.value = ''
  message.value = ''
  if (!password.value) {
    error.value = '请先输入当前密码验证身份。'
    return
  }
  const confirmed = window.confirm('注销会立即停用账号、撤销分享、取消提醒并删除该账号的服务端私人数据。此操作不能撤销。要继续吗？')
  if (!confirmed) return
  busy.value = true
  try {
    await api.deactivate(password.value)
    await clearOwnerCache(props.user.id)
    localStorage.removeItem('liubai:last-user')
    emit('deactivated')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '账号未能注销。'
  } finally {
    busy.value = false
  }
}

onMounted(updateTheme)
</script>

<template>
  <section>
    <header class="page-heading">
      <div><h1>设置</h1><p>管理个人资料、时区和阅读外观。</p></div>
    </header>
    <div v-if="error" class="notice error settings-notice" role="alert">{{ error }}</div>
    <div v-if="message" class="notice success settings-notice" role="status">{{ message }}</div>
    <div class="settings-layout">
      <section class="settings-section">
        <div class="settings-title"><h2>账号资料</h2><p>邮箱用于登录和找回账号；修改邮箱需要重新验证。</p></div>
        <label class="form-field"><span class="form-label">邮箱</span><input class="input" :value="user.email" disabled /></label>
        <label class="form-field"><span class="form-label">用户名</span><input class="input" :value="user.username" disabled /></label>
        <label class="form-field"><span class="form-label">昵称</span><input v-model="nickname" class="input" maxlength="40" required /></label>
        <div class="profile-avatar">
          <div class="avatar">
            <img v-if="user.hasAvatar" :src="'/api/settings/avatar?v=' + (user.avatarRevision || 0)" alt="当前头像" />
            <template v-else>{{ (user.nickname || user.username).slice(0, 1) }}</template>
          </div>
          <div class="form-field">
            <label class="form-label" for="profile-avatar-input">更换头像</label>
            <input id="profile-avatar-input" class="input" type="file" accept=".png,.jpg,.jpeg,image/png,image/jpeg"
              aria-describedby="avatar-requirements" :disabled="busy" @change="uploadAvatar" />
            <small id="avatar-requirements" class="muted">支持 PNG 或 JPEG，2 MB 以内，宽高各不超过 4096 像素。</small>
          </div>
          <button v-if="user.hasAvatar" class="secondary-button" type="button" :disabled="busy" @click="deleteAvatar">移除头像</button>
          <div v-if="avatarError" class="notice error avatar-feedback" role="alert">{{ avatarError }}</div>
          <div v-else-if="avatarStatus" class="avatar-feedback muted" role="status">{{ avatarStatus }}</div>
        </div>
      </section>
      <section class="settings-section">
        <div class="settings-title"><h2>时区</h2><p>日记日期、连续天数和提醒按所选时区计算。</p></div>
        <label class="form-field"><span class="form-label">时区</span>
          <select v-model="timeZone" class="select">
            <option v-for="zone in timeZones" :key="zone" :value="zone">{{ labels[zone] || zone }}</option>
          </select>
        </label>
      </section>
      <ReminderSettings />
      <section class="settings-section">
        <div class="settings-title"><h2>阅读外观</h2><p>全局仅提供浅色与深色两套主题，并可更换强调色。</p></div>
        <fieldset class="theme-options">
          <legend>主题</legend>
          <label><input v-model="theme" type="radio" value="light" @change="updateTheme" /><span class="theme-swatch light-swatch"></span><strong>浅色</strong></label>
          <label><input v-model="theme" type="radio" value="dark" @change="updateTheme" /><span class="theme-swatch dark-swatch"></span><strong>深色</strong></label>
        </fieldset>
        <fieldset class="accent-options">
          <legend>主题色</legend>
          <label v-for="item in [{ id: 'blue', name: '经典蓝', color: '#0f62fe' }, { id: 'rose', name: '玫瑰红', color: '#9f1853' }]" :key="item.id">
            <input v-model="accent" type="radio" :value="item.id" @change="updateTheme" /><i :style="{ background: item.color }"></i>{{ item.name }}
          </label>
        </fieldset>
      </section>
      <section class="settings-section">
        <div class="settings-title"><h2>更换邮箱</h2><p>确认新邮箱之前，仍可使用原邮箱登录。</p></div>
        <form class="settings-reminder-form" @submit.prevent="requestEmailChange">
          <label class="form-field"><span class="form-label">新邮箱</span><input v-model="newEmail" class="input" type="email" autocomplete="email" maxlength="254" required /></label>
          <label class="form-field"><span class="form-label">当前密码</span><input v-model="emailPassword" class="input" type="password" autocomplete="current-password" required /></label>
          <button class="secondary-button" type="submit" :disabled="busy">向新邮箱发送确认邮件</button>
        </form>
      </section>
      <section class="settings-section">
        <div class="settings-title"><h2>修改密码</h2><p>修改后，已登录的设备需要重新登录。</p></div>
        <button v-if="passwordChanged" class="primary-button settings-reminder-action" type="button" @click="reloadForLogin">使用新密码重新登录</button>
        <form v-else class="settings-reminder-form" @submit.prevent="changePassword">
          <label class="form-field"><span class="form-label">当前密码</span><input v-model="currentPassword" class="input" type="password" autocomplete="current-password" required /></label>
          <label class="form-field"><span class="form-label">新密码</span><input v-model="newPassword" class="input" type="password" autocomplete="new-password" minlength="10" maxlength="72" required /></label>
          <button class="secondary-button" type="submit" :disabled="busy">更新密码</button>
        </form>
      </section>
      <section class="settings-section danger-zone">
        <div class="settings-title"><h2>注销账号</h2><p>注销会立刻撤销该账号的分享与服务端会话，清理私人数据，不套用 15 天回收站期限。</p></div>
        <label class="form-field"><span class="form-label">输入当前密码以验证身份</span><input v-model="password" class="input" type="password" autocomplete="current-password" /></label>
        <button class="danger-button" type="button" :disabled="busy" @click="deactivate">立即注销账号</button>
      </section>
      <div class="settings-save">
        <span v-if="saved" class="save-confirm">设置已保存</span>
        <button class="primary-button" type="button" :disabled="busy" @click="save">{{ busy ? '正在保存…' : '保存设置' }}</button>
      </div>
    </div>
  </section>
</template>
