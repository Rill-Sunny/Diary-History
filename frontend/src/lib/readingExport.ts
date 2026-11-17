import type { Journal } from './types'

type NodeData = Record<string, unknown>
export type ImageQuality = 'original' | 'compressed'

function escapeHtml(value: string) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function dataUrl(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('图片读取失败。'))
    reader.readAsDataURL(blob)
  })
}

async function imageData(attrs: NodeData, quality: ImageQuality) {
  const id = Number(attrs.mediaId)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('日记中有无效的图片引用。')
  const response = await fetch('/api/media/' + id, { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw new Error('有图片暂时无法读取，导出已取消。')
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const image = new Image()
  try {
    image.src = url
    await image.decode()
    const rotation = (Number(attrs.rotation) || 0) % 360
    if (!attrs.cropped && !rotation && quality === 'original') return await dataUrl(blob)

    const sourceWidth = image.naturalWidth
    const sourceHeight = image.naturalHeight
    const width = attrs.cropped ? Math.max(1, Math.min(sourceWidth, Math.floor(sourceHeight * 16 / 9))) : sourceWidth
    const height = attrs.cropped ? Math.max(1, Math.min(sourceHeight, Math.floor(sourceWidth * 9 / 16))) : sourceHeight
    const angle = rotation * Math.PI / 180
    const sin = Math.abs(Math.sin(angle))
    const cos = Math.abs(Math.cos(angle))
    const rotatedWidth = width * cos + height * sin
    const rotatedHeight = width * sin + height * cos
    const maxSide = quality === 'compressed' ? 1400 : 8192
    const scale = Math.min(1, maxSide / Math.max(rotatedWidth, rotatedHeight))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.ceil(rotatedWidth * scale - 1e-9))
    canvas.height = Math.max(1, Math.ceil(rotatedHeight * scale - 1e-9))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('浏览器无法处理导出图片。')
    context.imageSmoothingQuality = 'high'
    context.translate(canvas.width / 2, canvas.height / 2)
    context.rotate(angle)
    context.scale(scale, scale)
    context.drawImage(image, Math.floor((sourceWidth - width) / 2), Math.floor((sourceHeight - height) / 2), width, height,
      -width / 2, -height / 2, width, height)
    const result = quality === 'compressed' ? canvas.toDataURL('image/webp', 0.76) : canvas.toDataURL('image/png')
    if (!result.startsWith('data:image/')) throw new Error('有图片无法完成转换，导出已取消。')
    return result
  } finally {
    URL.revokeObjectURL(url)
  }
}

function renderText(node: NodeData) {
  let text = escapeHtml(String(node.text ?? ''))
  for (const mark of Array.isArray(node.marks) ? node.marks as NodeData[] : []) {
    const tags = { bold: 'strong', italic: 'em', underline: 'u', strike: 's', code: 'code' } as Record<string, string>
    const type = String(mark.type)
    const attrs = mark.attrs as NodeData | undefined
    if (tags[type]) text = '<' + tags[type] + '>' + text + '</' + tags[type] + '>'
    if (type === 'link' && /^(https?:|mailto:)/i.test(String(attrs?.href ?? '')))
      text = '<a href="' + escapeHtml(String(attrs?.href)) + '">' + text + '</a>'
    if (type === 'textStyle') {
      const styles: string[] = []
      if (/^#[0-9a-f]{6}$/i.test(String(attrs?.color))) styles.push('color:' + attrs?.color)
      if (['16px', '18px', '22px', '28px'].includes(String(attrs?.fontSize))) styles.push('font-size:' + attrs?.fontSize)
      if (['serif', 'sans-serif', 'monospace'].includes(String(attrs?.fontFamily))) styles.push('font-family:' + attrs?.fontFamily)
      if (styles.length) text = '<span style="' + styles.join(';') + '">' + text + '</span>'
    }
  }
  return text
}

async function renderNode(node: NodeData, quality: ImageQuality): Promise<string> {
  const attrs = (node.attrs ?? {}) as NodeData
  if (node.type === 'text') return renderText(node)
  if (node.type === 'image') {
    const src = await imageData(attrs, quality)
    const width = Math.min(100, Math.max(30, Number(attrs.width) || 100))
    const caption = attrs.title ? '<figcaption>' + escapeHtml(String(attrs.title)) + '</figcaption>' : ''
    return '<figure><img src="' + src + '" alt="' + escapeHtml(String(attrs.alt ?? '')) + '" style="width:' + width + '%">' + caption + '</figure>'
  }
  if (node.type === 'horizontalRule') return '<hr>'
  if (node.type === 'hardBreak') return '<br>'
  const children: string[] = []
  for (const child of Array.isArray(node.content) ? node.content as NodeData[] : []) children.push(await renderNode(child, quality))
  const body = children.join('')
  if (node.type === 'codeBlock') return '<pre><code>' + body + '</code></pre>'
  if (node.type === 'orderedList') return '<ol start="' + (Number(attrs.start) || 1) + '">' + body + '</ol>'
  const tags = { paragraph: 'p', blockquote: 'blockquote', bulletList: 'ul', listItem: 'li' } as Record<string, string>
  const tag = node.type === 'heading' ? 'h' + Math.min(6, Math.max(1, Number(attrs.level) || 2)) : tags[String(node.type)]
  return tag ? '<' + tag + '>' + body + '</' + tag + '>' : body
}

export async function readingHtml(entries: Journal[], quality: ImageQuality) {
  const articles: string[] = []
  for (const entry of entries) {
    articles.push('<article><header><time datetime="' + escapeHtml(entry.journalDate) + '">' + escapeHtml(entry.journalDate) +
      '</time><h1>' + escapeHtml(entry.title) + '</h1></header>' + await renderNode(entry.content, quality) + '</article>')
  }
  return '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">' +
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data:; style-src \'unsafe-inline\'">' +
    '<title>留白日记导出</title><style>' +
    'body{max-width:760px;margin:48px auto;padding:0 24px;color:#252721;font:18px/1.9 "Noto Serif CJK SC","Songti SC","SimSun",serif;overflow-wrap:anywhere}' +
    'article{padding:24px 0 44px;border-bottom:1px solid #ddd}time{color:#666;font:13px sans-serif}header h1{font-size:32px;margin:8px 0 25px}' +
    'figure{margin:20px 0;text-align:center}img{display:block;margin:auto;max-width:100%;height:auto;object-fit:contain;border-radius:5px}' +
    'figcaption{margin-top:8px;color:#666;font-size:14px}blockquote{border-left:3px solid #798744;padding-left:18px;margin-left:0;color:#555}' +
    'pre{white-space:pre-wrap;padding:12px;background:#f4f3ed}a{color:#49592b}' +
    '@page{size:A4;margin:18mm}@media print{body{margin:0;padding:0;font-size:12pt}article{padding:0;border:0}article+article{break-before:page}' +
    'figure,header{break-inside:avoid}img{max-height:190mm}h1,h2,h3,h4,h5,h6{break-after:avoid}p,li{orphans:3;widows:3}}' +
    '</style></head><body>' + articles.join('') + '</body></html>'
}
