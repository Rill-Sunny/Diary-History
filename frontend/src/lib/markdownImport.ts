import { unzipSync } from 'fflate'

type Node = Record<string, unknown>
export interface HeadingDate { year?: number; month: number; day: number; label: string }
export interface MarkdownSection { date: HeadingDate; body: string }
export interface MarkdownSource { path: string; markdown: string; images: Map<string, Uint8Array> }
export interface ImportedMedia { id: number; url: string }

function parseHeadingDate(label: string): HeadingDate | null {
  const normalized = label.replace(/[０-９．／－]/g, (character) => String.fromCharCode(character.charCodeAt(0) - 0xfee0)).trim()
  const full = normalized.match(/^(\d{4})\s*[./-]\s*(\d{1,2})\s*[./-]\s*(\d{1,2})$/)
    ?? normalized.match(/^(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*[日号號]?$/)
  if (full) return { year: Number(full[1]), month: Number(full[2]), day: Number(full[3]), label }
  const partial = normalized.match(/^(\d{1,2})\s*[./-]\s*(\d{1,2})$/)
    ?? normalized.match(/^(\d{1,2})\s*月\s*(\d{1,2})\s*[日号號]?$/)
  return partial ? { month: Number(partial[1]), day: Number(partial[2]), label } : null
}

interface Block { type: string; value: string; source: string; level?: number }
function readBlocks(markdown: string): Block[] {
  const lines = markdown.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split('\n')
  const blocks: Block[] = []
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i] ?? ''
    const next = lines[i + 1] ?? ''
    const fence = line.match(/^ {0,3}(\x60{3,}|~{3,})[^\n]*$/)?.[1]
    if (fence) {
      const start = i
      const code: string[] = []
      const closing = new RegExp('^ {0,3}' + fence[0] + '{' + fence.length + ',}\\s*$')
      while (i + 1 < lines.length) {
        i++
        if (closing.test(lines[i] ?? '')) break
        code.push(lines[i] ?? '')
      }
      blocks.push({ type: 'codeBlock', value: code.join('\n'), source: lines.slice(start, i + 1).join('\n') })
      continue
    }
    if (/^( {4}|\t)/.test(line)) {
      const start = i
      while (/^( {4}|\t)/.test(lines[i + 1] ?? '')) i++
      const source = lines.slice(start, i + 1)
      blocks.push({ type: 'codeBlock', value: source.map((row) => row.replace(/^( {4}|\t)/, '')).join('\n'), source: source.join('\n') })
      continue
    }
    const atx = line.match(/^ {0,3}(#{1,6})(?:[ \t\u3000]+|$)(.*)$/)
    if (atx) {
      blocks.push({ type: 'heading', value: (atx[2] ?? '').replace(/[ \t]+#+[ \t]*$/, '').trim(), level: atx[1]?.length, source: line })
      continue
    }
    if (/^ {0,3}>/.test(line)) {
      blocks.push({ type: 'blockquote', value: line.replace(/^ {0,3}>[ \t]?/, ''), source: line })
      continue
    }
    if (/^ {0,3}((-[ \t]*){3,}|(\*[ \t]*){3,}|(_[ \t]*){3,})$/.test(line)) {
      blocks.push({ type: 'horizontalRule', value: '', source: line })
      continue
    }
    if (/^ {0,3}([-+*]|\d+[.)])[ \t]+/.test(line)) {
      const value = line.trim()
      blocks.push({ type: 'listItem', value, source: line })
      continue
    }
    if (line.trim() && /^ {0,3}(=+|-+)[ \t]*$/.test(next)) {
      blocks.push({ type: 'heading', value: line.trim(), level: next.trim()[0] === '=' ? 1 : 2, source: line + '\n' + next })
      i++
      continue
    }
    blocks.push({ type: 'paragraph', value: line, source: line })
  }
  return blocks
}

export function scanSections(markdown: string) {
  const sections: MarkdownSection[] = []
  const prelude: string[] = []
  let active: MarkdownSection | undefined
  for (const block of readBlocks(markdown)) {
    const date = block.type === 'heading' ? parseHeadingDate(block.value) : null
    if (date) {
      active = { date, body: '' }
      sections.push(active)
    } else if (active) active.body += block.source + '\n'
    else prelude.push(block.source)
  }
  const first = prelude.findIndex((block) => block.trim())
  if (first < 0) return { sections, prelude: '' }
  let last = prelude.length - 1
  while (last > first && !prelude[last]?.trim()) last--
  return { sections, prelude: prelude.slice(first, last + 1).join('\n') }
}

function normalizePath(path: string) {
  if (/^(?:[a-z]:|[/\\])|[\u0000-\u001f]/i.test(path)) return null
  const parts: string[] = []
  for (const part of path.replace(/\\/g, '/').split('/')) {
    if (!part || part === '.') continue
    if (part === '..') {
      if (!parts.length) return null
      parts.pop()
    } else parts.push(part)
  }
  return parts.join('/')
}

function resolvePath(markdownPath: string, target: string) {
  const raw = target.replace(/^<|>$/g, '')
  let decoded = raw
  try { decoded = decodeURIComponent(raw) } catch { /* Leave a literal percent in a file name. */ }
  if (/^(?:[a-z][a-z\d+.-]*:|[/\\])/i.test(decoded)) return null
  const parent = markdownPath.slice(0, markdownPath.lastIndexOf('/') + 1)
  return normalizePath(parent + decoded)?.toLowerCase() ?? null
}

function utf16ByteOrder(bytes: Uint8Array) {
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return 'utf-16le'
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return 'utf-16be'
  if (bytes.length % 2 !== 0) return null
  // BOM-less UTF-16 generally has NUL bytes on one side of ASCII text, such as date headings and line breaks.
  let even = 0
  let odd = 0
  const pairs = Math.min(bytes.length / 2, 4096)
  for (let i = 0; i < pairs; i++) {
    if (bytes[i * 2] === 0) even++
    if (bytes[i * 2 + 1] === 0) odd++
  }
  const minimum = Math.max(2, pairs / 5)
  if (odd >= minimum && odd > even * 4) return 'utf-16le'
  if (even >= minimum && even > odd * 4) return 'utf-16be'
  return null
}

function decodeMarkdown(bytes: Uint8Array) {
  const utf16 = utf16ByteOrder(bytes)
  const utf8Bom = bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf
  const encodings = utf16 ? [utf16] : utf8Bom ? ['utf-8'] : ['utf-8', 'gb18030']
  for (const encoding of encodings) {
    try {
      const text = new TextDecoder(encoding, { fatal: true }).decode(bytes)
      // Reject binary content even when its byte sequence decodes as text.
      if (!/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(text)) return text
    } catch { /* Try the next supported encoding when the file has no BOM. */ }
  }
  throw new Error('无法读取 Markdown 文本。请将文件另存为 UTF-8 编码后重试。')
}

export async function readSource(file: File): Promise<MarkdownSource> {
  if (file.size > 70 * 1024 * 1024) throw new Error('导入文件不能超过 70 MB。')
  if (!file.name.toLowerCase().endsWith('.zip')) {
    if (file.size > 12 * 1024 * 1024) throw new Error('Markdown 文件不能超过 12 MB。')
    return { path: file.name, markdown: decodeMarkdown(new Uint8Array(await file.arrayBuffer())), images: new Map() }
  }
  const seen = new Set<string>()
  let expanded = 0
  const bytes = new Uint8Array(await file.arrayBuffer())
  let archive: ReturnType<typeof unzipSync>
  try {
    archive = unzipSync(bytes, {
      filter: ({ name, originalSize, size }) => {
        const path = normalizePath(name)
        if (!path || /^(?:__proto__|constructor|prototype)$/i.test(name)) throw new Error('ZIP 中包含不安全的文件路径。')
        if (/[\\/]$/.test(name)) return false
        const key = path.toLowerCase()
        if (seen.has(key)) throw new Error('ZIP 中包含重复的文件路径。')
        seen.add(key)
        expanded += Math.max(originalSize, size)
        if (seen.size > 2000 || expanded > 160 * 1024 * 1024 || originalSize > 12 * 1024 * 1024)
          throw new Error('ZIP 展开后超过文件数量或大小限制。')
        return /\.(?:md|markdown|png|jpe?g|gif)$/i.test(path)
      },
    })
  } catch (error) {
    if (error instanceof RangeError || (error instanceof Error && 'code' in error && typeof error.code === 'number')) {
      throw new Error('无法打开 ZIP。请重新压缩 Markdown 文件和配图后再导入。')
    }
    throw error
  }
  const files = Object.entries(archive).map(([name, data]) => [normalizePath(name)!, data] as const)
  const markdowns = files.filter(([path]) => /\.(?:md|markdown)$/i.test(path))
  const main = markdowns[0]
  if (markdowns.length !== 1 || !main) throw new Error('ZIP 内需要且只能包含一个 Markdown 文件。')
  const images = new Map(files.filter(([path]) => /\.(?:png|jpe?g|gif)$/i.test(path)).map(([path, data]) => [path.toLowerCase(), data]))
  return { path: main[0], markdown: decodeMarkdown(main[1]), images }
}

export function parseBody(body: string, source: MarkdownSource, uploaded: Map<string, ImportedMedia>, textOnly: boolean) {
  const missing = new Set<string>()
  const paths = new Set<string>()
  const visibleText: string[] = []
  const blocks: Node[] = []
  for (const block of readBlocks(body)) {
    if (block.type === 'codeBlock') {
      if (block.value.trim()) visibleText.push(block.value)
      blocks.push({ type: 'codeBlock', content: block.value ? [{ type: 'text', text: block.value }] : [] })
      continue
    }
    if (block.type === 'horizontalRule') {
      blocks.push({ type: block.type })
      continue
    }
    if (!block.value.trim()) continue
    let content: Node[] = []
    const flush = () => {
      if (!content.length) return
      const paragraph = { type: 'paragraph', content }
      if (block.type === 'heading') blocks.push({ type: 'heading', attrs: { level: block.level }, content })
      else if (block.type === 'blockquote') blocks.push({ type: 'blockquote', content: [paragraph] })
      else if (block.type === 'listItem') {
        const ordered = /^\d/.test(block.value)
        const listType = ordered ? 'orderedList' : 'bulletList'
        const previous = blocks[blocks.length - 1]
        const listItem = { type: 'listItem', content: [paragraph] }
        if (previous?.type === listType) (previous.content as Node[]).push(listItem)
        else blocks.push({ type: listType, attrs: ordered ? { start: Number.parseInt(block.value, 10) } : undefined, content: [listItem] })
      } else blocks.push(paragraph)
      content = []
    }
    const appendText = (text: string, mark?: Node) => {
      if (!text) return
      visibleText.push(text)
      content.push(mark ? { type: 'text', text, marks: [mark] } : { type: 'text', text })
    }
    const value = block.type === 'listItem' ? block.value.replace(/^([-+*]|\d+[.)])[ \t]+/, '') : block.value
    const tokens = /\x60([^\x60]+)\x60|!\[([^\]]*)\]\((<[^>]*>|(?:[^()]|\([^()]*\))+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*|_([^_]+)_|\[([^\]]+)\]\((https?:\/\/(?:[^()]|\([^()]*\))+)\)/g
    let end = 0
    for (const match of value.matchAll(tokens)) {
      appendText(value.slice(end, match.index))
      end = (match.index ?? 0) + match[0].length
      if (match[1] !== undefined) appendText(match[1], { type: 'code' })
      else if (match[3] !== undefined) {
        const target = match[3].trim().replace(/\s+"[^"]*"$/, '')
        const path = resolvePath(source.path, target)
        if (!path || !source.images.has(path)) missing.add(target)
        else {
          paths.add(path)
          const image = uploaded.get(path)
          if (image && !textOnly) {
            flush()
            blocks.push({ type: 'image', attrs: { src: image.url, mediaId: image.id, alt: match[2] ?? '', title: match[2] ?? '', width: 100, rotation: 0, cropped: false } })
          }
        }
      } else if (match[4]) appendText(match[4], { type: 'bold' })
      else if (match[5] || match[6]) appendText(match[5] ?? match[6] ?? '', { type: 'italic' })
      else if (match[7]) appendText(match[7], { type: 'link', attrs: { href: match[8] } })
    }
    appendText(value.slice(end))
    flush()
  }
  return { content: { type: 'doc', content: blocks }, text: visibleText.join(' ').trim(), paths: [...paths], missing: [...missing] }
}

export function imageFile(path: string, data: Uint8Array) {
  const name = path.split('/').pop() ?? 'image'
  const suffix = name.split('.').pop()?.toLowerCase()
  const type = suffix === 'png' ? 'image/png' : suffix === 'gif' ? 'image/gif' : 'image/jpeg'
  const bytes = new Uint8Array(data)
  return new File([bytes.buffer], name, { type })
}
