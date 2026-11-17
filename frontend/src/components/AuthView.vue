<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../lib/api'
import type { User } from '../lib/types'
import Icon from './Icon.vue'

const emit = defineEmits<{ authenticated: [user: User] }>()
const mode = ref<'login' | 'register' | 'verify' | 'forgot' | 'reset' | 'changeEmail'>('login')
const identity = ref('')
const password = ref('')
const email = ref('')
const username = ref('')
const nickname = ref('')
const token = ref('')
const busy = ref(false)
const message = ref('')
const error = ref('')

async function submit() {
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    if (mode.value === 'login') {
      emit('authenticated', await api.login(identity.value.trim(), password.value))
    } else if (mode.value === 'register') {
      const result = await api.register(email.value.trim(), username.value.trim(), nickname.value.trim(), password.value)
      message.value = result.message
      mode.value = 'verify'
    } else if (mode.value === 'verify' || mode.value === 'changeEmail') {
      const result = mode.value === 'verify' ? await api.verifyEmail(token.value.trim()) : await api.verifyEmailChange(token.value.trim())
      message.value = result.message
      mode.value = 'login'
    } else if (mode.value === 'forgot') {
      const result = await api.forgotPassword(email.value.trim())
      message.value = result.message
    } else {
      const result = await api.resetPassword(token.value.trim(), password.value)
      password.value = ''
      token.value = ''
      message.value = result.message
      mode.value = 'login'
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '请求未完成，请稍后重试。'
  } finally {
    busy.value = false
  }
}

async function resendVerification() {
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    message.value = (await api.resendVerification(email.value.trim())).message
  } catch (e) {
    error.value = e instanceof Error ? e.message : '验证邮件未能发送。'
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  const query = new URLSearchParams(window.location.search)
  const verification = query.get('verify')
  const reset = query.get('reset')
  const changeEmail = query.get('changeEmail')
  if (verification || changeEmail) {
    mode.value = changeEmail ? 'changeEmail' : 'verify'
    token.value = changeEmail || verification || ''
    history.replaceState(null, '', window.location.pathname)
    void submit()
  } else if (reset) {
    mode.value = 'reset'
    token.value = reset
    history.replaceState(null, '', window.location.pathname)
  }
})
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel">
      <div class="auth-brand"><span class="auth-mark"><Icon name="leaf" :size="26" /></span>留白日记</div>
      <h1>{{ mode === 'register' ? '开始留下记录' : mode === 'verify' ? '验证邮箱' : mode === 'changeEmail' ? '确认新邮箱' : mode === 'forgot' ? '找回密码' : mode === 'reset' ? '设置新密码' : '写给未来的自己' }}</h1>
      <p class="auth-intro">日常不必特别，写下来就有了位置。</p>
      <form class="auth-form" @submit.prevent="submit">
        <template v-if="mode === 'login'">
          <label class="form-field">
            <span class="form-label">邮箱或用户名</span>
            <input v-model="identity" class="input" autocomplete="username" required placeholder="邮箱或用户名" />
          </label>
          <label class="form-field">
            <span class="form-label">密码</span>
            <input v-model="password" class="input" type="password" autocomplete="current-password" required placeholder="输入密码" />
          </label>
          <div class="forgot-row"><button type="button" @click="mode = 'forgot'; error = ''; message = ''">忘记密码</button></div>
        </template>
        <template v-else-if="mode === 'register'">
          <label class="form-field"><span class="form-label">邮箱</span><input v-model="email" class="input" type="email" autocomplete="email" required placeholder="用于验证与找回密码" /></label>
          <label class="form-field"><span class="form-label">用户名</span><input v-model="username" class="input" autocomplete="username" minlength="3" maxlength="24" required placeholder="3–24 个字符" /></label>
          <label class="form-field"><span class="form-label">昵称</span><input v-model="nickname" class="input" maxlength="40" required placeholder="日记里显示的名字" /></label>
          <label class="form-field"><span class="form-label">密码</span><input v-model="password" class="input" type="password" autocomplete="new-password" minlength="10" required placeholder="至少 10 位" /></label>
        </template>
        <template v-else-if="mode === 'verify' || mode === 'changeEmail'">
          <p class="muted">请打开验证邮件中的链接，或粘贴一次性验证码。</p>
          <label class="form-field"><span class="form-label">验证码</span><input v-model="token" class="input" required autocomplete="one-time-code" /></label>
        </template>
        <template v-else-if="mode === 'forgot'">
          <p class="muted">如果邮箱对应有效账号，系统会发送一次性重置链接。</p>
          <label class="form-field"><span class="form-label">邮箱</span><input v-model="email" class="input" type="email" autocomplete="email" required /></label>
        </template>
        <template v-else>
          <label class="form-field"><span class="form-label">新密码</span><input v-model="password" class="input" type="password" autocomplete="new-password" minlength="10" required placeholder="至少 10 位" /></label>
        </template>
        <div v-if="error" class="notice error" role="alert">{{ error }}</div>
        <div v-if="message" class="notice" role="status">{{ message }}</div>
        <button class="primary-button auth-submit" type="submit" :disabled="busy">
          {{ busy ? '请稍候…' : mode === 'register' ? '创建账号' : mode === 'verify' ? '验证邮箱' : mode === 'changeEmail' ? '确认新邮箱' : mode === 'forgot' ? '发送重置邮件' : mode === 'reset' ? '更新密码' : '登录' }}
        </button>
      </form>
      <div class="auth-switch">
        <template v-if="mode === 'login'">
          还没有账号？<button type="button" @click="mode = 'register'; error = ''; message = ''">注册</button>
          <button type="button" @click="mode = 'verify'; error = ''; message = ''">验证邮箱</button>
        </template>
        <template v-else>
          已有账号？<button type="button" @click="mode = 'login'; error = ''; message = ''">返回登录</button>
        </template>
      </div>
      <form v-if="mode === 'verify'" class="auth-form" @submit.prevent="resendVerification">
        <label class="form-field"><span class="form-label">重新发送至注册邮箱</span><input v-model="email" class="input" type="email" autocomplete="email" required /></label>
        <button class="secondary-button" type="submit" :disabled="busy">重新发送验证邮件</button>
      </form>
      <div class="auth-privacy"><span class="privacy-dot"></span>默认私密 · 只有你主动分享的版本才会进入审核</div>
    </section>
    <aside class="auth-aside">
      <img class="auth-landscape" src="/assets/demo-lake.png" alt="秋日湖畔，树木与远山倒映在水中" />
      <div class="auth-aside-copy">
        <p class="aside-quote">把生活里那些微小的时刻，<br />轻轻放在这里。</p>
        <p class="aside-caption">为长久书写而设计</p>
      </div>
    </aside>
  </main>
</template>
