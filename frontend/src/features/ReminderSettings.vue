<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type ReminderDelivery, type ReminderSettings, type ReminderStatus } from '../lib/api'

const week = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const settings = ref<ReminderSettings | null>(null)
const status = ref<ReminderStatus | null>(null)
const subscription = ref<PushSubscription | null>(null)
const subscriptionHash = ref('')
const busy = ref(false)
const error = ref('')
const message = ref('')
const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
const homeScreen = window.matchMedia('(display-mode: standalone)').matches ||
  (navigator as Navigator & { standalone?: boolean }).standalone === true
const supportsPush = window.isSecureContext && 'PushManager' in window && 'serviceWorker' in navigator && 'Notification' in window
const permission = ref(supportsPush ? Notification.permission : 'unsupported')
const registered = computed(() => !!subscriptionHash.value && status.value?.subscribedEndpoints.includes(subscriptionHash.value))
const pushState = computed(() => {
  if (!window.isSecureContext) return '需通过 HTTPS 或本机 localhost 访问。'
  if (ios && !homeScreen) return '需从主屏幕打开应用。'
  if (!supportsPush) return '当前浏览器不支持 Web Push。'
  if (status.value?.pushConfiguration === 'invalid') return '服务器推送配置无效。'
  if (status.value?.pushConfiguration !== 'configured') return '服务器 Web Push 未配置。'
  if (permission.value === 'denied') return '浏览器已拒绝。可在站点权限中重新启用。'
  if (registered.value) return '此设备已订阅。'
  if (permission.value === 'granted') return '已授权，此设备待订阅。'
  return '此设备尚未授权。'
})

function deliveryLabel(delivery?: ReminderDelivery) {
  if (!delivery) return ''
  const labels = {
    SENT: '服务已接收',
    SENDING: '正在发送',
    SKIPPED: '已跳过',
    FAILED: '发送失败',
    PARTIAL: '部分订阅发送失败',
    UNKNOWN: '投递结果未确认',
  }
  return `${delivery.localDate} · ${labels[delivery.status]}${delivery.message ? ' · ' + delivery.message : ''}`
}

async function updateSubscription(current: PushSubscription | null) {
  subscription.value = current
  subscriptionHash.value = current
    ? Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(current.endpoint))),
      byte => byte.toString(16).padStart(2, '0')).join('')
    : ''
}

async function perform(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提醒设置暂不可用。'
  } finally {
    busy.value = false
  }
}

function load() {
  return perform(async () => {
    const result = await api.reminderStatus()
    status.value = result
    settings.value = { ...result.settings }
    if (supportsPush) {
      const registration = await navigator.serviceWorker.getRegistration('/')
      await updateSubscription(await registration?.pushManager.getSubscription() ?? null)
    }
  })
}

function save() {
  return perform(async () => {
    if (!settings.value) return
    const result = await api.saveReminders(settings.value)
    status.value = result
    settings.value = { ...result.settings }
    message.value = '提醒设置已保存。'
  })
}

function subscribe() {
  return perform(async () => {
    const key = status.value?.pushPublicKey
    if (!key || !supportsPush) return
    permission.value = await Notification.requestPermission()
    if (permission.value !== 'granted') return
    await navigator.serviceWorker.register('/sw.js')
    const registration = await navigator.serviceWorker.ready
    let current = await registration.pushManager.getSubscription()
    if (current) {
      const existingKey = current.options.applicationServerKey
      if (existingKey && encode(new Uint8Array(existingKey)) !== key) {
        await api.unsubscribeReminders(current.endpoint)
        await current.unsubscribe()
        current = null
        await updateSubscription(null)
      }
    }
    current ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: decode(key) })
    const keys = current.toJSON().keys
    if (!keys?.p256dh || !keys.auth) throw new Error('浏览器未提供订阅密钥，请重试。')
    status.value = await api.subscribeReminders(current.endpoint, keys.p256dh, keys.auth)
    await updateSubscription(current)
    message.value = '已订阅此设备。可在上方开启浏览器通知提醒。'
  })
}

function unsubscribe() {
  return perform(async () => {
    if (!subscription.value) return
    status.value = await api.unsubscribeReminders(subscription.value.endpoint)
    const old = subscription.value
    await updateSubscription(null)
    await old.unsubscribe()
    message.value = '已取消此设备的订阅。'
  })
}

function encode(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function decode(value: string) {
  const encoded = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')
  return Uint8Array.from(atob(padded), char => char.charCodeAt(0))
}

onMounted(load)
</script>

<template>
  <section class="settings-section">
    <div class="settings-title">
      <h2>写作提醒</h2>
      <p>提醒按账号时区发送。当天的写作同步后，系统会跳过尚未发送的提醒。</p>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <button v-if="!settings && error" type="button" class="secondary-button settings-reminder-action" :disabled="busy" @click="load">重新载入</button>
    <p v-else-if="!settings" class="settings-reminder-action" role="status">正在载入提醒设置…</p>
    <template v-if="settings && status">
      <form class="settings-reminder-form" @submit.prevent="save">
        <label class="form-field"><span class="form-label">提醒时间</span><input v-model="settings.time" type="time" class="input" required /></label>
        <label class="form-field">
          <span class="form-label">频率</span>
          <select v-model="settings.type" class="select">
            <option value="DAILY">每天</option>
            <option value="WEEKDAYS">工作日（周一至周五）</option>
            <option value="CUSTOM">指定星期</option>
          </select>
        </label>
        <fieldset v-if="settings.type === 'CUSTOM'" class="reminder-days">
          <legend>提醒星期</legend>
          <label v-for="(day, index) in week" :key="day">
            <input v-model="settings.weekdays" type="checkbox" :value="index + 1" />{{ day }}
          </label>
        </fieldset>
        <div class="reminder-status">
          <div>
            <label><input v-model="settings.emailEnabled" type="checkbox" />邮件提醒</label>
            <span>{{ status.mailConfigured ? 'SMTP 已配置' : 'SMTP 未配置' }}</span>
            <small v-if="status.lastEmail">{{ deliveryLabel(status.lastEmail) }}</small>
          </div>
          <div>
            <label><input v-model="settings.pushEnabled" type="checkbox" />浏览器通知</label>
            <span>{{ pushState }}</span>
            <small v-if="status.lastPush">{{ deliveryLabel(status.lastPush) }}</small>
          </div>
        </div>
        <button class="primary-button" type="submit" :disabled="busy">保存提醒设置</button>
      </form>
      <button v-if="registered" class="secondary-button settings-reminder-action" type="button" :disabled="busy" @click="unsubscribe">取消此设备订阅</button>
      <button v-else-if="supportsPush && (!ios || homeScreen) && status.pushPublicKey && permission !== 'denied'"
        class="secondary-button settings-reminder-action" type="button" :disabled="busy" @click="subscribe">订阅此设备</button>
      <p v-if="ios && !homeScreen" class="settings-footnote">在 iOS 或 iPadOS 16.4 以上打开分享菜单，选择“添加到主屏幕”，再从主屏幕打开留白日记并订阅此设备。</p>
      <p class="settings-footnote">离线写作会在联网同步后计入当天活动。通知的接收时间受网络与系统设置影响。</p>
    </template>
  </section>
</template>
