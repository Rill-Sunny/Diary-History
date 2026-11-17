<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../lib/api'
import type { Notebook, Tag, User } from '../lib/types'
import Icon from '../components/Icon.vue'
import TagChip from '../components/TagChip.vue'

defineProps<{ user: User }>()
const notebooks = ref<Notebook[]>([])
const tags = ref<Tag[]>([])
const bookName = ref('')
const tagName = ref('')
const tagColor = ref('#0f62fe')
const tagIcon = ref('leaf')
const tagStyle = ref<Tag['style']>('soft')
const icons = [
  ['leaf', '叶子'], ['book', '书本'], ['heart', '爱心'], ['star', '星星'], ['tag', '标签'],
  ['clock', '时钟'], ['sun', '太阳'], ['moon', '月亮'], ['coffee', '咖啡'],
]
const error = ref('')
const loading = ref(true)
const failedCovers = ref(new Set<number>())

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [bookList, tagList] = await Promise.all([api.notebooks(), api.tags()])
    notebooks.value = bookList
    tags.value = tagList
  } catch (e) {
    error.value = e instanceof Error ? e.message : '内容未能载入。'
  } finally {
    loading.value = false
  }
}

async function addBook() {
  const name = bookName.value.trim()
  if (!name) return
  try {
    await api.createNotebook(name)
    bookName.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '日记本未能创建。'
  }
}

async function removeBook(book: Notebook) {
  if (book.isDefault) {
    error.value = '默认日记本始终保留。'
    return
  }
  if (!window.confirm('“' + book.name + '”中的 ' + book.entryCount + ' 篇日记将一起移入回收站。要继续吗？')) return
  try {
    await api.deleteNotebook(book.id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '日记本未能删除。'
  }
}

async function addTag() {
  const name = tagName.value.trim()
  if (!name) return
  try {
    await api.createTag(name, tagColor.value, tagIcon.value, tagStyle.value ?? 'soft')
    tagName.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '标签未能创建。'
  }
}

async function changeCover(book: Notebook, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    await api.setNotebookCover(book.id, file)
    failedCovers.value.delete(book.id)
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '日记本封面未能保存。'
  }
}

async function removeCover(book: Notebook) {
  try {
    await api.clearNotebookCover(book.id)
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '日记本封面未能移除。'
  }
}

async function submitIcon(tag: Tag, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    await api.submitTagIcon(tag.id, file)
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图标申请未能提交。'
  }
}

function iconStatus(tag: Tag) {
  if (tag.iconStatus === 'PENDING') return '图标待审'
  if (tag.iconStatus === 'REJECTED') return '图标未通过：' + (tag.iconReason || '可换一张重新提交')
  if (tag.iconStatus === 'APPROVED') return '私人图标已通过'
  return '使用内置图标'
}

async function removeTag(tag: Tag) {
  if (!window.confirm('删除“' + tag.name + '”只会解除日记关联，不会删除日记正文。要继续吗？')) return
  try {
    await api.deleteTag(tag.id)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '标签未能删除。'
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-heading">
      <div><h1>日记本与标签</h1><p>用日记本组织记录，用标签留下轻量线索。</p></div>
    </header>
    <div v-if="error" class="notice error library-error" role="alert">{{ error }}</div>
    <div v-if="loading" class="loading-line"><span class="spinner"></span>正在载入…</div>
    <div v-else class="library-grid">
      <section class="library-section">
        <div class="library-section-head">
          <div><h2>日记本</h2><p>删除日记本会将其中日记一并移入回收站。</p></div>
          <Icon name="book" :size="22" />
        </div>
        <form class="inline-create" @submit.prevent="addBook">
          <input v-model="bookName" class="input" maxlength="60" placeholder="新日记本名称" aria-label="新日记本名称" />
          <button class="primary-button" type="submit" :disabled="!bookName.trim()">创建</button>
        </form>
        <div class="library-list">
          <article v-for="book in notebooks" :key="book.id" class="library-row">
            <div class="book-cover" :class="{ 'default-cover': book.isDefault }">
              <img v-if="book.coverUrl && !failedCovers.has(book.id)" :src="book.coverUrl" alt="" loading="lazy" @error="failedCovers.add(book.id)" />
              <Icon v-else name="book" :size="23" />
            </div>
            <div class="library-row-main"><strong>{{ book.name }}</strong><span>{{ book.entryCount }} 篇日记</span></div>
            <div class="library-row-tools">
              <label class="secondary-button compact-action library-upload">{{ book.coverUrl ? '换封面' : '加封面' }}
                <input type="file" accept="image/png,image/jpeg,image/gif" :aria-label="'设置' + book.name + '的封面'" @change="changeCover(book, $event)" />
              </label>
              <button v-if="book.coverUrl" class="text-button" type="button" @click="removeCover(book)">移除封面</button>
            </div>
            <span v-if="book.isDefault" class="default-label">默认</span>
            <button v-else class="icon-button" type="button" :aria-label="'删除' + book.name" :title="'删除' + book.name" @click="removeBook(book)"><Icon name="trash" :size="16" /></button>
          </article>
          <div v-if="!notebooks.length" class="subtle-empty">暂无日记本。</div>
        </div>
      </section>
      <section class="library-section">
        <div class="library-section-head">
          <div><h2>标签</h2><p>标签只属于当前账号；删除标签不会删除日记内容。</p></div>
          <Icon name="tag" :size="22" />
        </div>
        <form class="inline-create tag-create" @submit.prevent="addTag">
          <input v-model="tagName" class="input" maxlength="40" placeholder="新标签名称" aria-label="新标签名称" />
          <label class="swatch-picker" title="标签颜色"><input v-model="tagColor" type="color" aria-label="标签颜色" /></label>
          <button class="primary-button" type="submit" :disabled="!tagName.trim()">创建</button>
          <div class="tag-create-options">
            <select v-model="tagIcon" class="select" aria-label="内置标签图标"><option v-for="[value, label] in icons" :key="value" :value="value">{{ label }}</option></select>
            <select v-model="tagStyle" class="select" aria-label="标签样式"><option value="soft">浅底</option><option value="outline">描边</option><option value="solid">实色</option></select>
            <TagChip :tag="{ id: 0, name: tagName || '预览', icon: tagIcon, color: tagColor, style: tagStyle }" />
          </div>
        </form>
        <div class="tag-library">
          <article v-for="tag in tags" :key="tag.id" class="tag-library-row">
            <div class="library-row-main"><TagChip :tag="tag" /><span>{{ iconStatus(tag) }}</span></div>
            <label class="secondary-button compact-action library-upload" title="PNG、JPEG 或 GIF，最大 1 MB">提交图标
              <input type="file" accept="image/png,image/jpeg,image/gif" :aria-label="'提交' + tag.name + '的私人图标'" @change="submitIcon(tag, $event)" />
            </label>
            <button class="icon-button" type="button" :aria-label="'删除标签' + tag.name" @click="removeTag(tag)"><Icon name="trash" :size="16" /></button>
          </article>
          <div v-if="!tags.length" class="subtle-empty">创建标签后，可以在编辑日记时快速归类。</div>
        </div>
      </section>
    </div>
  </section>
</template>
