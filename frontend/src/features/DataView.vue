<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import { api, type ExportEntry, type ImportResult, type RestoreResult } from '../lib/api'
import { imageFile, parseBody, readSource, scanSections, type ImportedMedia, type MarkdownSource } from '../lib/markdownImport'
import { readingHtml, type ImageQuality } from '../lib/readingExport'
import type { Journal, JournalWrite, Notebook, User } from '../lib/types'
import Icon from '../components/Icon.vue'

const props = defineProps<{ user: User }>()
interface ImportedEntry {
  id: string
  sourceTitle: string
  sourceText: string
  journalDate: string
  notebookId: number
  title: string
  skip: boolean
  hasYear: boolean
  monthDay?: string
  dateEdited: boolean
}

const entries = ref<ExportEntry[]>([])
const notebooks = ref<Notebook[]>([])
const selectedIds = ref<number[]>([])
const from = ref('')
const to = ref('')
const notebookId = ref('')
const quality = ref<ImageQuality>('original')
const fileInput = ref<HTMLInputElement>()
const backupInput = ref<HTMLInputElement>()
const source = shallowRef<MarkdownSource | null>(null)
const defaultYear = ref('')
const preview = ref<ImportedEntry[]>([])
const prelude = ref('')
const preludeChoice = ref('')
const confirmCrossYear = ref(false)
const textOnly = ref(false)
const importPlan = shallowRef<{ result: ImportResult; writes: JournalWrite[]; operationId: string } | null>(null)
const importError = ref('')
const importMessage = ref('')
const importStage = ref<'reading' | 'checking' | 'importing' | ''>('')
const validationAttempted = ref(false)
const importValidation = ref<HTMLElement>()
const busy = ref(false)
const message = ref('')
const error = ref('')
const restoreSettings = ref(false)
const mergeNotebooks = ref(true)
const crossYearWarning = ref(false)
const backupPreview = shallowRef<{ fileName: string; data: unknown; result: RestoreResult } | null>(null)
const uploadedMedia = new Map<string, ImportedMedia>()

const rangedEntries = computed(() => entries.value.filter((entry) =>
  (!from.value || entry.journalDate >= from.value) &&
  (!to.value || entry.journalDate <= to.value) &&
  (!notebookId.value || entry.notebookId === Number(notebookId.value)),
))
const selectedInRange = computed(() => rangedEntries.value.filter((entry) => selectedIds.value.includes(entry.id)))
const candidates = computed(() => {
  const selectedSource = source.value
  if (!selectedSource) return []
  return preview.value.filter((item) => !item.skip).map((item, index) => {
    const body = index === 0 && preludeChoice.value === 'attach' ? prelude.value + '\n\n' + item.sourceText : item.sourceText
    return { item, body, ...parseBody(body, selectedSource, uploadedMedia, textOnly.value) }
  })
})
const candidatesById = computed(() => new Map(candidates.value.map((entry) => [entry.item.id, entry])))
const today = computed(() => todayFor(props.user.timeZone))
const isEmpty = (entry: (typeof candidates.value)[number]) => !entry.text && (textOnly.value || !entry.paths.length)
const importIssues = computed(() => {
  const issues: { message: string; target: string }[] = []
  const selected = candidates.value
  if (!selected.length) issues.push({ message: '所有日记都已跳过，请至少选择一篇。', target: 'import-entry-selection-' + preview.value[0]?.id })
  if (selected.length > 2000) issues.push({ message: '每次最多导入 2000 篇，请跳过部分日记或拆分文件。', target: 'import-entry-selection-' + selected[0]?.item.id })
  const invalidDates = selected.filter(({ item }) => !validDate(item.journalDate))
  if (invalidDates.length) {
    const first = invalidDates[0]!.item
    const needsYear = !first.hasYear && !first.dateEdited && !Number(defaultYear.value)
    issues.push({ message: needsYear
      ? `${invalidDates.length} 篇尚未填写完整日期，请填写默认年份，或逐篇选择日期。`
      : `${invalidDates.length} 篇的日期无效，请选择 1000 年至今天之间的真实日期。`,
    target: needsYear ? 'import-default-year' : 'import-entry-date-' + first.id })
  }
  const futureDates = selected.filter(({ item }) => validDate(item.journalDate) && item.journalDate > today.value)
  if (futureDates.length) issues.push({ message: `${futureDates.length} 篇使用了未来日期，请修改日期或跳过。`, target: 'import-entry-date-' + futureDates[0]!.item.id })
  const empty = selected.filter(isEmpty)
  if (empty.length) issues.push({ message: `${empty.length} 篇没有可导入的正文或图片，请补齐图片，或勾选“跳过此篇”。`, target: 'import-entry-selection-' + empty[0]!.item.id })
  if (selected.some((entry) => entry.missing.length) && !textOnly.value) issues.push({ message: '文档引用的图片不在文件中，请重新选择包含图片的 ZIP，或勾选“只导入文字”。', target: 'import-text-only' })
  if (prelude.value && !preludeChoice.value) issues.push({ message: '日期标题之前还有文字，请选择并入首篇或跳过这些文字。', target: 'import-prelude-choice' })
  if (crossYearWarning.value && !confirmCrossYear.value) issues.push({ message: '日期顺序可能跨年，请核对日期并勾选“我已检查日期顺序”。', target: 'import-cross-year' })
  return issues
})

watch([preview, preludeChoice, textOnly, confirmCrossYear], () => { importPlan.value = null }, { deep: true })
watch(defaultYear, () => {
  const year = Number(defaultYear.value)
  const validYear = Number.isInteger(year) && year >= 1000 && year <= 9999
  for (const item of preview.value) {
    if (!item.hasYear && !item.dateEdited && item.monthDay) item.journalDate = validYear ? year + '-' + item.monthDay : ''
  }
})

function focusImportIssue(target: string) {
  const element = document.getElementById(target)
  element?.scrollIntoView({ block: 'center' })
  element?.focus({ preventScroll: true })
}

function todayFor(timezone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const value = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return value('year') + '-' + value('month') + '-' + value('day')
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  if (Number(value.slice(0, 4)) < 1000) return false
  const date = new Date(value + 'T00:00:00Z')
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function buildPreview() {
  if (!source.value) return
  const scanned = scanSections(source.value.markdown)
  prelude.value = scanned.prelude
  const year = defaultYear.value ? Number(defaultYear.value) : null
  let priorMonthDay = 0
  crossYearWarning.value = false
  preview.value = scanned.sections.map((section, index) => {
    const date = section.date
    const numeric = date.month * 100 + date.day
    if (priorMonthDay && numeric < priorMonthDay && !date.year) crossYearWarning.value = true
    priorMonthDay = numeric
    const fullYear = date.year ?? year
    const journalDate = fullYear ? String(fullYear).padStart(4, '0') + '-' + String(date.month).padStart(2, '0') + '-' + String(date.day).padStart(2, '0') : ''
    return {
      id: String(index), sourceTitle: date.label, sourceText: section.body, journalDate,
      hasYear: date.year !== undefined, title: '', skip: false, dateEdited: false,
      monthDay: String(date.month).padStart(2, '0') + '-' + String(date.day).padStart(2, '0'),
      notebookId: notebooks.value.find((book) => book.isDefault)?.id ?? notebooks.value[0]?.id ?? 0,
    }
  })
  if (!scanned.sections.length && scanned.prelude) {
    preview.value = [{ id: 'manual', sourceTitle: '手动归属', sourceText: scanned.prelude, journalDate: '', title: '', skip: false, hasYear: true, dateEdited: false,
      notebookId: notebooks.value.find((book) => book.isDefault)?.id ?? notebooks.value[0]?.id ?? 0 }]
    prelude.value = ''
  }
  preludeChoice.value = ''
  confirmCrossYear.value = false
  textOnly.value = false
  importPlan.value = null
}

async function onMarkdownInput(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''
  busy.value = true
  importStage.value = 'reading'
  validationAttempted.value = false
  importError.value = ''
  importMessage.value = ''
  importPlan.value = null
  error.value = ''
  message.value = ''
  source.value = null
  preview.value = []
  prelude.value = ''
  defaultYear.value = ''
  uploadedMedia.clear()
  try {
    source.value = await readSource(file)
    buildPreview()
    if (!preview.value.length) importError.value = '文件中没有可预览的 Markdown 内容，请选择含有正文的 .md 文件。'
  } catch (e) {
    source.value = null
    preview.value = []
    importError.value = e instanceof Error ? e.message : '文件无法读取，请重新选择。'
  } finally {
    importStage.value = ''
    busy.value = false
  }
}

async function prepareImport() {
  if (busy.value || !source.value) return
  importError.value = ''
  importMessage.value = ''
  validationAttempted.value = true
  if (importIssues.value.length) {
    await nextTick()
    importValidation.value?.scrollIntoView({ block: 'center' })
    importValidation.value?.focus({ preventScroll: true })
    return
  }
  busy.value = true
  importStage.value = 'checking'
  error.value = ''
  message.value = ''
  try {
    const chosen = source.value
    const selected = candidates.value
    const withoutImages = textOnly.value
    if (!withoutImages) {
      const paths = new Set(selected.flatMap((entry) => entry.paths))
      for (const path of paths) {
        const data = chosen.images.get(path)
        if (!data || uploadedMedia.has(path)) continue
        try { uploadedMedia.set(path, await api.uploadImage(imageFile(path, data))) }
        catch (e) { throw new Error(path + ' · ' + (e instanceof Error ? e.message : '图片上传失败。')) }
      }
    }
    const writes: JournalWrite[] = selected.map(({ item, body }) => {
      const parsed = parseBody(body, chosen, uploadedMedia, withoutImages)
      return { journalDate: item.journalDate, title: item.title, automaticTitle: !item.title.trim(),
        content: parsed.content, plainText: parsed.text, notebookId: item.notebookId, tagIds: [] }
    })
    const operationId = crypto.randomUUID()
    const result = await api.importJournals(writes, operationId, true)
    importPlan.value = { result, writes, operationId }
  } catch (e) {
    importError.value = e instanceof Error ? e.message : '导入预览未完成，请重试。'
  } finally {
    importStage.value = ''
    busy.value = false
  }
}

async function importEntries() {
  const plan = importPlan.value
  if (!plan) return
  busy.value = true
  importStage.value = 'importing'
  importError.value = ''
  error.value = ''
  try {
    const result = await api.importJournals(plan.writes, plan.operationId)
    importMessage.value = result.message
    importPlan.value = null
    await loadEntries()
  } catch (e) {
    importError.value = e instanceof Error ? e.message : '导入未完成，请重试。'
  } finally {
    importStage.value = ''
    busy.value = false
  }
}

async function loadEntries() {
  const [allEntries, books] = await Promise.all([api.exportable(), api.notebooks()])
  entries.value = allEntries
  notebooks.value = books
}

function toggleAll() {
  const ids = new Set(rangedEntries.value.map((entry) => entry.id))
  selectedIds.value = selectedInRange.value.length === rangedEntries.value.length
    ? selectedIds.value.filter((id) => !ids.has(id))
    : [...new Set([...selectedIds.value, ...ids])]
}

function download(content: BlobPart, type: string, name: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function exportEntries(pdf: boolean) {
  const selected = [...selectedInRange.value]
  if (!selected.length) {
    error.value = '先选择要导出的日记。'
    return
  }
  const printWindow = pdf ? window.open('', '_blank') : null
  if (pdf && !printWindow) {
    error.value = '浏览器阻止了打印窗口，请允许此站点打开窗口后重试。'
    return
  }
  if (printWindow) printWindow.document.body.textContent = '正在准备日记和图片…'
  busy.value = true
  error.value = ''
  try {
    const journals: Journal[] = []
    for (const entry of selected) journals.push(await api.journal(entry.id))
    const html = await readingHtml(journals, quality.value)
    if (!printWindow) download(html, 'text/html;charset=utf-8', '留白日记-离线阅读.html')
    else {
      printWindow.document.open()
      printWindow.document.write(html)
      printWindow.document.close()
      await Promise.all(Array.from(printWindow.document.images, (image) => image.decode()))
      await printWindow.document.fonts.ready
      printWindow.focus()
      printWindow.print()
    }
  } catch (e) {
    printWindow?.close()
    error.value = e instanceof Error ? e.message : '导出未完成。'
  } finally {
    busy.value = false
  }
}

async function downloadBackup() {
  error.value = ''
  try {
    const response = await fetch('/api/data/backup', { credentials: 'same-origin', cache: 'no-store' })
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}))
      throw new Error(detail.message || '备份下载失败，请确认已登录并重试。')
    }
    download(await response.blob(), 'application/json', 'liubai-backup-' + today.value + '.json')
    message.value = '备份已下载。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '备份未能下载。'
  }
}

async function inspectBackup(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''
  error.value = ''
  backupPreview.value = null
  busy.value = true
  try {
    if (file.size > 150_000_000) throw new Error('备份文件超过 150 MB。')
    const data: unknown = JSON.parse(await file.text())
    const result = await api.restoreBackup(data, false, mergeNotebooks.value, true)
    backupPreview.value = { fileName: file.name, data, result }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '备份文件无法读取。'
  } finally {
    busy.value = false
  }
}

async function updateBackupPreview() {
  const pending = backupPreview.value
  if (!pending) return
  busy.value = true
  error.value = ''
  try {
    const result = await api.restoreBackup(pending.data, false, mergeNotebooks.value, true)
    backupPreview.value = { ...pending, result }
  } catch (e) {
    backupPreview.value = null
    error.value = e instanceof Error ? e.message : '备份预览无法更新。'
  } finally {
    busy.value = false
  }
}

async function restoreBackup() {
  if (!backupPreview.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await api.restoreBackup(backupPreview.value.data, restoreSettings.value, mergeNotebooks.value)
    message.value = '恢复完成：新增 ' + result.added + ' 篇（含回收站 ' + result.trashAdded + ' 条），重复跳过 ' + result.skipped +
      ' 篇，冲突副本 ' + result.conflicts + ' 篇，过期跳过 ' + result.expired + ' 条。' +
      (result.expiredTitles.length ? ' 过期条目：' + result.expiredTitles.join('、') : '')
    backupPreview.value = null
    await loadEntries()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '备份未能恢复。'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try { await loadEntries() } catch (e) { error.value = e instanceof Error ? e.message : '数据未能载入。' }
})
</script>
<template>
  <section>
    <header class="page-heading">
      <div><h1>数据管理</h1><p>导入 Markdown、导出离线可读文件，并创建可追加恢复的完整备份。</p></div>
    </header>
    <div v-if="error" class="notice error data-notice" role="alert">{{ error }}</div>
    <div v-if="message" class="notice success data-notice" role="status">{{ message }}</div>
    <div class="data-section-grid">
      <section class="data-panel">
        <div class="data-panel-heading"><Icon name="download" :size="19" /><div><h2>导出日记</h2><p>仅导出你当前选中的日记。离线 HTML 会包含图片。</p></div></div>
        <div class="export-filters">
          <label class="form-field"><span class="form-label">开始日期</span><input v-model="from" class="input" type="date" /></label>
          <label class="form-field"><span class="form-label">结束日期</span><input v-model="to" class="input" type="date" /></label>
          <label class="form-field"><span class="form-label">日记本</span><select v-model="notebookId" class="select"><option value="">全部日记本</option><option v-for="book in notebooks" :key="book.id" :value="String(book.id)">{{ book.name }}</option></select></label>
        </div>
        <div class="export-list-head"><label><input type="checkbox" :checked="rangedEntries.length > 0 && selectedInRange.length === rangedEntries.length" @change="toggleAll" />全选筛选结果</label><span>已选 {{ selectedInRange.length }} / {{ rangedEntries.length }} 篇</span></div>
        <div class="export-entry-list">
          <label v-for="entry in rangedEntries" :key="entry.id" class="export-entry">
            <input v-model="selectedIds" type="checkbox" :value="entry.id" />
            <span><strong>{{ entry.title }}</strong><small>{{ entry.journalDate }} · {{ entry.excerpt || '图片日记' }}</small></span>
          </label>
          <p v-if="!rangedEntries.length" class="subtle-empty">没有符合条件的日记。</p>
        </div>
        <label class="form-field"><span class="form-label">导出图片质量</span><select v-model="quality" class="select"><option value="original">高质量</option><option value="compressed">压缩</option></select></label>
        <div class="data-actions">
          <button class="secondary-button" type="button" :disabled="busy || !selectedInRange.length" @click="exportEntries(false)"><Icon name="download" :size="15" />导出离线 HTML</button>
          <button class="secondary-button" type="button" :disabled="busy || !selectedInRange.length" @click="exportEntries(true)">打印 / 保存为 PDF</button>
        </div>
      </section>

      <section class="data-panel import-panel">
        <div class="data-panel-heading"><Icon name="database" :size="19" /><div><h2>Markdown 导入</h2><p>扫描各级标题中的日期；代码块和引用中的日期文字不会拆分。</p></div></div>
        <input ref="fileInput" class="visually-hidden" type="file" aria-label="选择 Markdown 文档或 ZIP" :disabled="busy" accept=".md,.markdown,.zip,text/markdown,application/zip" @change="onMarkdownInput" />
        <button class="secondary-button import-picker" type="button" :disabled="busy" @click="fileInput?.click()">选择 .md 或图文 ZIP</button>
        <p class="muted">纯文字可直接选择 .md；含本地图片时，请将文档和图片目录一起打包为 ZIP。</p>
        <div v-if="importStage === 'reading'" class="loading-line" role="status"><span class="spinner"></span>正在读取文档并生成预览…</div>
        <div v-if="importError" class="notice error" role="alert">{{ importError }}</div>
        <div v-if="importMessage" class="notice success" role="status">{{ importMessage }}</div>
        <p v-if="source" class="source-file">{{ source.path }}</p>
        <p v-if="preview.length === 1 && preview[0]?.id === 'manual'" class="notice">未发现日期标题，已将全文作为一篇日记预览。请先选择这篇日记的日期。</p>
        <label v-if="preview.some((entry) => !entry.hasYear)" class="form-field year-field">
          <span class="form-label">为无年份标题选择默认年份</span>
          <input id="import-default-year" v-model="defaultYear" class="input" type="number" min="1000" max="9999" placeholder="例如 2024" :disabled="busy" />
        </label>
        <div v-if="prelude" class="notice import-warning"><strong>日期标题之前有未归属文字</strong><details><summary>查看待处理内容</summary><pre>{{ prelude }}</pre></details><label class="form-field"><span class="form-label">处理方式</span><select id="import-prelude-choice" v-model="preludeChoice" class="select" :disabled="busy"><option value="">请选择</option><option value="attach">并入首篇选中日记</option><option value="ignore">跳过未归属文字</option></select></label></div>
        <div v-if="crossYearWarning" class="notice import-warning"><strong>日期顺序可能跨年</strong><p>请核对每篇预览日期。</p><label class="check-line"><input id="import-cross-year" v-model="confirmCrossYear" type="checkbox" :disabled="busy" />我已检查日期顺序</label></div>
        <div v-if="preview.length" class="import-preview">
          <div class="preview-summary">已选 {{ candidates.length }} / {{ preview.length }} 篇 <span v-if="candidates.length > 2000">· 每批最多 2000 篇</span></div>
          <article v-for="item in preview" :key="item.id" class="preview-row">
            <label class="check-line"><input :id="'import-entry-selection-' + item.id" v-model="item.skip" type="checkbox" :disabled="busy" />跳过此篇</label>
            <div class="preview-date-row"><input :id="'import-entry-date-' + item.id" v-model="item.journalDate" class="input" type="date" min="1000-01-01" :max="today" :disabled="busy || item.skip" aria-label="导入日记日期" @input="item.dateEdited = true" /><select v-model.number="item.notebookId" class="select" :disabled="busy || item.skip" aria-label="目标日记本"><option v-for="book in notebooks" :key="book.id" :value="book.id">{{ book.name }}</option></select></div>
            <input v-model="item.title" class="input" maxlength="255" :disabled="busy || item.skip" aria-label="日记标题" placeholder="标题（留空按日期显示）" />
            <span>{{ item.sourceTitle }}</span>
            <p>{{ item.sourceText.replace(/\s+/g, ' ').slice(0, 105) || '（空内容）' }}</p>
            <template v-if="!item.skip">
              <small v-if="!validDate(item.journalDate)" class="preview-error">请选择有效年份和日期</small>
              <small v-else-if="item.journalDate > today" class="preview-error">未来日期不能导入</small>
              <small v-if="!textOnly && candidatesById.get(item.id)?.missing.length" class="preview-error">缺少图片：{{ candidatesById.get(item.id)?.missing.join('、') }}</small>
              <small v-if="candidatesById.get(item.id) && isEmpty(candidatesById.get(item.id)!)" class="preview-error">没有可导入的文字或图片，请跳过此篇或补齐图片。</small>
            </template>
          </article>
        </div>
        <label v-if="preview.length" class="check-line text-only-check"><input id="import-text-only" v-model="textOnly" type="checkbox" :disabled="busy" />只导入文字</label>
        <div v-if="validationAttempted && importIssues.length" ref="importValidation" class="notice error import-validation" role="alert" tabindex="-1">
          <strong>检查前还有 {{ importIssues.length }} 项需要处理</strong>
          <ul><li v-for="issue in importIssues" :key="issue.target + issue.message"><span>{{ issue.message }}</span><button class="text-button" type="button" @click="focusImportIssue(issue.target)">前往处理</button></li></ul>
        </div>
        <div v-if="importPlan" class="notice" role="status">新增 {{ importPlan.result.imported }} 篇，完全重复 {{ importPlan.result.skipped }} 篇将跳过。</div>
        <button v-if="importPlan" class="primary-button" type="button" :disabled="busy" @click="importEntries">{{ busy ? '正在导入…' : '确认导入' }}</button>
        <button v-else-if="preview.length" class="secondary-button" type="button" :disabled="busy" @click="prepareImport">{{ busy ? '正在检查…' : '检查重复并预览' }}</button>
      </section>
    </div>

    <section class="backup-panel">
      <div class="data-panel-heading"><Icon name="database" :size="19" /><div><h2>完整备份与追加恢复</h2><p>备份包含正文、日记本、标签、媒体、活动和回收站。恢复不会清空当前账号。</p></div></div>
      <div class="backup-actions">
        <button class="primary-button" type="button" :disabled="busy" @click="downloadBackup">下载完整备份</button>
        <input ref="backupInput" class="visually-hidden" type="file" accept=".json,application/json" @change="inspectBackup" />
        <button class="secondary-button" type="button" :disabled="busy" @click="backupInput?.click()">选择备份并预览恢复</button>
      </div>
      <label class="check-line backup-setting"><input v-model="restoreSettings" type="checkbox" />同时采用备份中的个人偏好设置</label>
      <label class="form-field"><span class="form-label">同名日记本</span><select v-model="mergeNotebooks" class="select" :disabled="busy" @change="updateBackupPreview"><option :value="true">合并到现有日记本</option><option :value="false">另建日记本</option></select></label>
      <div v-if="backupPreview" class="backup-preview notice">
        <strong>备份预览</strong>
        <p>{{ backupPreview.fileName }} · 新增 {{ backupPreview.result.added }} 篇（含回收站 {{ backupPreview.result.trashAdded }} 条）· 重复 {{ backupPreview.result.skipped }} 篇 · 冲突 {{ backupPreview.result.conflicts }} 篇 · 过期跳过 {{ backupPreview.result.expired }} 条</p>
        <p v-if="backupPreview.result.sameNotebooks.length">同名日记本：{{ backupPreview.result.sameNotebooks.join('、') }}</p>
        <p v-if="backupPreview.result.changedTags.length">以下同名标签有不同样式或图标，将创建副本：{{ backupPreview.result.changedTags.join('、') }}</p>
        <details v-if="backupPreview.result.expiredTitles.length"><summary>查看已过期的回收站条目</summary><ul><li v-for="(entry, index) in backupPreview.result.expiredTitles" :key="index">{{ entry }}</li></ul></details>
        <div class="backup-actions"><button class="primary-button" type="button" :disabled="busy" @click="restoreBackup">{{ busy ? '正在恢复…' : '确认追加恢复' }}</button><button class="secondary-button" type="button" @click="backupPreview = null">取消</button></div>
      </div>
      <p class="settings-footnote">恢复会按稳定身份去重；冲突会保留为副本。已过期的回收站条目会跳过，分享令牌和审核权限不会迁移。</p>
    </section>
  </section>
</template>

