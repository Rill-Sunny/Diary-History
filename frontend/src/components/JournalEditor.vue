<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Extension, type Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Color } from '@tiptap/extension-color'
import Image from '@tiptap/extension-image'
import Link from '@tiptap/extension-link'
import { TextStyle } from '@tiptap/extension-text-style'
import Underline from '@tiptap/extension-underline'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import { Fragment } from '@tiptap/pm/model'
import { NodeSelection } from '@tiptap/pm/state'
import { cachedImage, findQueuedUpload, queueUpload, visitImages } from '../lib/offline'
import Icon from './Icon.vue'

const props = defineProps<{
  ownerId: number
  content: Record<string, unknown>
  offline?: boolean
  toolbarHidden?: boolean
}>()
const emit = defineEmits<{
  change: [value: Record<string, unknown>]
  toolbar: [hidden: boolean]
  imageError: [message: string]
}>()

const fileInput = ref<HTMLInputElement>()
const toolbarHidden = ref(Boolean(props.toolbarHidden))
const selectedImage = ref<{ title: string; alt: string; width: number; rotation: number; cropped: boolean } | null>(null)
const fontSize = ref('18px')
const fontFamily = ref('sans-serif')
const color = ref('#161616')
const uploadBusy = ref(false)
const imageError = ref('')
const localUrls = new Map<string, string>()
const previewDialog = ref<HTMLDialogElement>()
const previewImage = ref<{ src: string; caption: string; cropped: boolean; rotation: number }>()
let imageRender = 0
let composing = false

const FontSize = Extension.create({
  name: 'fontSize',
  addGlobalAttributes() {
    return [{
      types: ['textStyle'],
      attributes: {
        fontSize: {
          default: null,
          parseHTML: (element) => element.style.fontSize || null,
          renderHTML: (attributes) => attributes.fontSize ? { style: 'font-size: ' + attributes.fontSize } : {},
        },
        fontFamily: {
          default: null,
          parseHTML: (element) => ['serif', 'sans-serif', 'monospace'].includes(element.style.fontFamily) ? element.style.fontFamily : null,
          renderHTML: (attributes) => attributes.fontFamily ? { style: 'font-family: ' + attributes.fontFamily } : {},
        },
      },
    }]
  },
})

const DiaryImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      offlineId: { default: null },
      mediaId: { default: null },
      rotation: { default: 0, parseHTML: (el) => Number(el.getAttribute('data-rotation') || 0), renderHTML: (attrs) => ({ 'data-rotation': attrs.rotation }) },
      width: { default: 100, parseHTML: (el) => Number(el.getAttribute('data-width') || 100), renderHTML: (attrs) => ({ 'data-width': attrs.width }) },
      cropped: { default: false, parseHTML: (el) => el.getAttribute('data-cropped') === 'true', renderHTML: (attrs) => ({ 'data-cropped': attrs.cropped ? 'true' : 'false' }) },
      title: { default: null },
    }
  },
  renderHTML({ HTMLAttributes }) {
    const width = Math.min(100, Math.max(30, Number(HTMLAttributes['data-width'] || 100)))
    const rotation = Number(HTMLAttributes['data-rotation'] || 0)
    const cropped = HTMLAttributes['data-cropped'] === 'true'
    const style = [
      'width:' + width + '%',
      'max-width:100%',
      'aspect-ratio:' + (cropped ? '16 / 9' : 'auto'),
      'object-fit:' + (cropped ? 'cover' : 'contain'),
      'transform:rotate(' + rotation + 'deg)',
    ].join(';')
    const image = ['img', { ...HTMLAttributes, style }]
    return HTMLAttributes.title
      ? ['figure', { class: 'editor-image-figure' }, image, ['figcaption', {}, String(HTMLAttributes.title)]]
      : ['figure', { class: 'editor-image-figure' }, image]
  },
})

const editor = useEditor({
  content: props.content,
  extensions: [
    StarterKit.configure({ heading: { levels: [1, 2, 3, 4, 5, 6] }, link: false, underline: false }),
    TextStyle,
    FontSize,
    Color,
    Underline,
    Link.configure({ openOnClick: false, protocols: ['http', 'https', 'mailto'] }),
    DiaryImage.configure({ inline: false, allowBase64: false }),
  ],
  editorProps: {
    attributes: {
      class: 'diary-editor',
      'aria-label': '日记正文',
      role: 'textbox',
      'aria-multiline': 'true',
      spellcheck: 'true',
    },
    handleDOMEvents: {
      compositionstart() { composing = true; return false },
      compositionend() {
        composing = false
        // ProseMirror applies the final IME transaction after the DOM event.
        setTimeout(() => {
          if (editor.value && !editor.value.isDestroyed && !editor.value.view.composing) {
            emit('change', editor.value.getJSON() as Record<string, unknown>)
          }
        }, 35)
        return false
      },
    },
  },
  onUpdate({ editor: instance }) {
    if (!composing && !instance.view.composing) emit('change', instance.getJSON() as Record<string, unknown>)
  },
  onCreate() {
    void hydrateImages(props.content).catch(() => emit('imageError', '本机图片缓存无法读取。'))
  },
  onTransaction({ editor: instance }) {
    const attrs = instance.getAttributes('image')
    selectedImage.value = instance.isActive('image')
      ? {
          title: attrs.title ?? '',
          alt: attrs.alt ?? '',
          width: Number(attrs.width ?? 100),
          rotation: Number(attrs.rotation ?? 0),
          cropped: Boolean(attrs.cropped),
        }
      : null
  },
})

const activeImage = computed(() => selectedImage.value)

watch([() => props.content, () => props.offline, editor], ([content]) => {
  if (!editor.value || JSON.stringify(editor.value.getJSON()) === JSON.stringify(content) && !props.offline) return
  void hydrateImages(content).catch(() => emit('imageError', '本机图片缓存无法读取。'))
}, { flush: 'post' })

watch(() => props.toolbarHidden, (value) => {
  if (value !== undefined) toolbarHidden.value = value
})

function toggleToolbar() {
  toolbarHidden.value = !toolbarHidden.value
  emit('toolbar', toolbarHidden.value)
}

function format(action: string) {
  const instance = editor.value
  if (!instance) return
  const chain = instance.chain().focus()
  if (action === 'bold') chain.toggleBold().run()
  if (action === 'italic') chain.toggleItalic().run()
  if (action === 'underline') chain.toggleUnderline().run()
  if (action === 'quote') chain.toggleBlockquote().run()
  if (action === 'bulletList') chain.toggleBulletList().run()
  if (action === 'heading') chain.toggleHeading({ level: 2 }).run()
  if (action === 'undo') chain.undo().run()
  if (action === 'redo') chain.redo().run()
  if (action === 'rule') chain.setHorizontalRule().run()
  if (action === 'link') {
    const previous = instance.getAttributes('link').href as string | undefined
    const url = window.prompt('输入链接地址', previous ?? 'https://')
    if (url) chain.extendMarkRange('link').setLink({ href: url }).run()
    else if (url === '') chain.extendMarkRange('link').unsetLink().run()
  }
}

function setBlock(value: string) {
  if (!editor.value) return
  if (value === 'paragraph') editor.value.chain().focus().setParagraph().run()
  const level = Number(value)
  if (level >= 1 && level <= 6) editor.value.chain().focus().setHeading({ level: level as 1 | 2 | 3 | 4 | 5 | 6 }).run()
}

function setFontSize(value: string) {
  fontSize.value = value
  editor.value?.chain().focus().setMark('textStyle', { fontSize: value }).run()
}

function setFontFamily(value: string) {
  fontFamily.value = value
  editor.value?.chain().focus().setMark('textStyle', { fontFamily: value }).run()
}

function setColor(value: string) {
  color.value = value
  editor.value?.chain().focus().setColor(value).run()
}

async function addFiles(files: FileList | File[]) {
  const instance = editor.value
  if (!instance || !files.length) return
  uploadBusy.value = true
  imageError.value = ''
  const errors: string[] = []
  try {
    for (const file of Array.from(files)) {
      try {
        await insertImage(instance, file)
      } catch (cause) {
        errors.push(file.name + '：' + (cause instanceof Error ? cause.message : '图片未能插入。'))
      }
    }
  } finally {
    uploadBusy.value = false
  }
  if (errors.length) {
    imageError.value = errors.join(' ')
    emit('imageError', imageError.value)
  }
}

async function insertImage(instance: Editor, file: File) {
  const allowedType = ['image/png', 'image/jpeg', 'image/gif'].includes(file.type)
  if (!allowedType && (file.type || !/\.(png|jpe?g|gif)$/i.test(file.name))) throw new Error('仅支持 PNG、JPEG 或 GIF 图片。')
  if (!file.size || file.size > 12 * 1024 * 1024) throw new Error('单张图片大小应为 1 字节到 12 MB。')
  const offlineId = crypto.randomUUID()
  // Persist the bytes before changing the editor. Journal sync uploads only
  // the images referenced by its own saved snapshot.
  await queueUpload(props.ownerId, offlineId, file)
  if (instance.isDestroyed) return
  const url = URL.createObjectURL(file)
  localUrls.set('pending:' + offlineId, url)
  instance.chain().focus().setImage({ src: url, alt: file.name, title: '', offlineId, width: 100 } as never)
    .createParagraphNear().run()
}

function onDrop(event: DragEvent) {
  const files = event.dataTransfer?.files
  if (!files?.length) return
  event.preventDefault()
  const instance = editor.value
  const target = instance?.view.posAtCoords({ left: event.clientX, top: event.clientY })
  if (target) instance?.commands.setTextSelection(target.pos)
  void addFiles(files)
}

function onPaste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.items ?? [])
    .filter((item) => item.type.startsWith('image/'))
    .map((item) => item.getAsFile())
    .filter((file): file is File => Boolean(file))
  if (!files.length) return
  event.preventDefault()
  void addFiles(files)
}

async function hydrateImages(content: Record<string, unknown>) {
  const renderId = ++imageRender
  const instance = editor.value
  if (!instance) return
  const hydrated = JSON.parse(JSON.stringify(content)) as Record<string, unknown>
  const images: Record<string, unknown>[] = []
  visitImages(hydrated, (attrs) => images.push(attrs))
  for (const attrs of images) {
    const offlineId = typeof attrs.offlineId === 'string' ? attrs.offlineId : null
    const mediaId = typeof attrs.mediaId === 'number' ? attrs.mediaId : null
    if (!offlineId && (!props.offline || !mediaId)) {
      if (mediaId) attrs.src = '/api/media/' + mediaId
      continue
    }
    const key = offlineId ? 'pending:' + offlineId : 'media:' + mediaId
    let url = localUrls.get(key)
    if (!url) {
      const blob = offlineId ? (await findQueuedUpload(props.ownerId, offlineId))?.file : await cachedImage(props.ownerId, mediaId!)
      if (renderId !== imageRender) return
      if (blob) {
        url = URL.createObjectURL(blob)
        localUrls.set(key, url)
      }
    }
    if (url) attrs.src = url
  }
  if (renderId !== imageRender || instance.isDestroyed || instance.view.composing) return
  if (JSON.stringify(instance.getJSON()) !== JSON.stringify(hydrated)) {
    instance.commands.setContent(hydrated as never, { emitUpdate: false })
  }
}

function moveImage(direction: -1 | 1) {
  const instance = editor.value
  if (!instance) return
  const { selection } = instance.state
  if (!(selection instanceof NodeSelection) || selection.node.type.name !== 'image') return
  const parent = selection.$from.parent
  const index = selection.$from.index()
  const sibling = parent.maybeChild(index + direction)
  if (!sibling) return
  const start = direction < 0 ? selection.from - sibling.nodeSize : selection.from
  const end = direction < 0 ? selection.to : selection.to + sibling.nodeSize
  const pair = direction < 0 ? [selection.node, sibling] : [sibling, selection.node]
  const transaction = instance.state.tr.replaceWith(start, end, Fragment.fromArray(pair))
  transaction.setSelection(NodeSelection.create(transaction.doc, start + (direction < 0 ? 0 : sibling.nodeSize)))
  instance.view.dispatch(transaction.scrollIntoView())
}
function updateImage(attributes: Record<string, unknown>) {
  editor.value?.commands.updateAttributes('image', attributes)
}

function showPreview() {
  const attrs = editor.value?.getAttributes('image')
  if (!attrs?.src) return
  previewImage.value = { src: String(attrs.src), caption: String(attrs.title || attrs.alt || '日记图片'),
    cropped: Boolean(attrs.cropped), rotation: Number(attrs.rotation ?? 0) }
  previewDialog.value?.showModal()
}

onMounted(() => {
  window.addEventListener('keydown', onShortcut)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  imageRender += 1
  for (const url of localUrls.values()) URL.revokeObjectURL(url)
  editor.value?.destroy()
})

function onShortcut(event: KeyboardEvent) {
  if (!composing && !event.isComposing && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    emit('change', (editor.value?.getJSON() ?? {}) as Record<string, unknown>)
  }
}
</script>

<template>
  <div class="editor-tools-wrap">
    <div class="editor-tools-head">
      <span class="toolbar-caption">正文工具</span>
      <button class="toolbar-toggle" type="button" :aria-expanded="!toolbarHidden" @click="toggleToolbar">
        {{ toolbarHidden ? '显示工具栏' : '隐藏工具栏' }}
      </button>
    </div>
    <div v-if="!toolbarHidden" class="editor-toolbar" role="toolbar" aria-label="正文格式工具">
      <select aria-label="文字样式" class="tool-select" @change="setBlock(($event.target as HTMLSelectElement).value)">
        <option value="paragraph">正文</option>
        <option v-for="level in 6" :key="level" :value="level">标题 {{ level }}</option>
      </select>
      <span class="tool-divider"></span>
      <button class="tool-button" type="button" title="加粗" aria-label="加粗" :class="{ selected: editor?.isActive('bold') }" @click="format('bold')"><Icon name="bold" :size="17" /></button>
      <button class="tool-button" type="button" title="斜体" aria-label="斜体" :class="{ selected: editor?.isActive('italic') }" @click="format('italic')"><Icon name="italic" :size="17" /></button>
      <button class="tool-button text-tool" type="button" title="下划线" aria-label="下划线" :class="{ selected: editor?.isActive('underline') }" @click="format('underline')"><span>U</span></button>
      <select class="tool-select compact-select" aria-label="字号" :value="fontSize" @change="setFontSize(($event.target as HTMLSelectElement).value)">
        <option value="16px">小</option><option value="18px">标准</option><option value="22px">大</option><option value="28px">特大</option>
      </select>
      <select class="tool-select" aria-label="字体" :value="fontFamily" @change="setFontFamily(($event.target as HTMLSelectElement).value)">
        <option value="sans-serif">无衬线</option><option value="serif">衬线</option><option value="monospace">等宽</option>
      </select>
      <label class="color-tool" title="文字颜色"><span>A</span><input v-model="color" type="color" aria-label="文字颜色" @input="setColor(color)" /></label>
      <span class="tool-divider"></span>
      <button class="tool-button" type="button" title="引用" aria-label="引用" @click="format('quote')"><Icon name="quote" :size="18" /></button>
      <button class="tool-button" type="button" title="无序列表" aria-label="无序列表" @click="format('bulletList')"><Icon name="list" :size="18" /></button>
      <button class="tool-button" type="button" title="链接" aria-label="链接" @click="format('link')"><Icon name="link" :size="17" /></button>
      <button class="tool-button" type="button" title="分隔线" aria-label="插入分隔线" @click="format('rule')">―</button>
      <button class="tool-button" type="button" title="添加图片" aria-label="添加图片" :disabled="uploadBusy" @click="fileInput?.click()"><Icon name="image" :size="18" /></button>
      <input ref="fileInput" class="visually-hidden" type="file" accept="image/png,image/jpeg,image/gif" multiple @change="($event.target as HTMLInputElement).files && addFiles(($event.target as HTMLInputElement).files!)" />
      <span class="tool-divider"></span>
      <button class="tool-button" type="button" title="撤销" aria-label="撤销" @click="format('undo')"><Icon name="undo" :size="17" /></button>
      <button class="tool-button" type="button" title="重做" aria-label="重做" @click="format('redo')"><Icon name="redo" :size="17" /></button>
    </div>
    <div v-if="activeImage" class="image-editor-panel">
      <span class="image-panel-title">图片调整</span>
      <label>说明<input class="input" :value="activeImage.title" placeholder="为图片添加说明" @input="updateImage({ title: ($event.target as HTMLInputElement).value })" /></label>
      <label>显示宽度<input type="range" min="40" max="100" :value="activeImage.width" @input="updateImage({ width: Number(($event.target as HTMLInputElement).value) })" /></label>
      <button class="secondary-button" type="button" @click="updateImage({ rotation: (activeImage.rotation + 90) % 360 })">旋转 90°</button>
      <button class="secondary-button" type="button" @click="updateImage({ cropped: !activeImage.cropped })">{{ activeImage.cropped ? '还原完整图片' : '居中裁剪 16:9' }}</button>
      <button class="secondary-button" type="button" @click="moveImage(-1)">向前移动</button>
      <button class="secondary-button" type="button" @click="moveImage(1)">向后移动</button>
      <button class="secondary-button" type="button" @click="showPreview">查看大图</button>
    </div>
    <dialog ref="previewDialog" class="journal-image-preview" aria-label="图片预览">
      <button class="secondary-button" type="button" @click="previewDialog?.close()">关闭预览</button>
      <figure v-if="previewImage">
        <img :src="previewImage.src" :alt="previewImage.caption" :class="{ cropped: previewImage.cropped }" :style="{ transform: 'rotate(' + previewImage.rotation + 'deg)' }" />
        <figcaption>{{ previewImage.caption }}</figcaption>
      </figure>
    </dialog>
    <div v-if="imageError" class="notice error image-notice" role="alert">{{ imageError }}</div>
    <div v-if="uploadBusy" class="image-uploading" role="status">正在处理图片…</div>
    <div class="editor-content-area" @drop="onDrop" @paste="onPaste"><span v-if="editor?.isEmpty" class="editor-placeholder" aria-hidden="true">把今天留在这里……</span>
      <EditorContent :editor="editor" />
    </div>
    <p class="editor-hint">支持拖入、粘贴或批量选择图片。正文和说明会随日记一起保存。</p>
  </div>
</template>
