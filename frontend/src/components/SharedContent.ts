import { h, type VNode, type VNodeChild } from 'vue'

type NodeData = Record<string, unknown>

function childrenOf(node: NodeData): NodeData[] {
  return Array.isArray(node.content) ? node.content as NodeData[] : []
}

function markText(value: VNodeChild, mark: NodeData): VNode {
  const attrs = mark.attrs as Record<string, unknown> | undefined
  if (mark.type === 'bold') return h('strong', {}, [value])
  if (mark.type === 'italic') return h('em', {}, [value])
  if (mark.type === 'underline') return h('u', {}, [value])
  if (mark.type === 'strike') return h('s', {}, [value])
  if (mark.type === 'code') return h('code', {}, [value])
  if (mark.type === 'link' && /^(https?:|mailto:)/i.test(String(attrs?.href ?? ''))) return h('a', { href: String(attrs?.href), rel: 'noreferrer', target: '_blank' }, [value])
  if (mark.type === 'textStyle') {
    const style: Record<string, string> = {}
    if (typeof attrs?.color === 'string' && /^#[\da-f]{6}$/i.test(attrs.color)) style.color = attrs.color
    if (typeof attrs?.fontSize === 'string' && ['16px','18px','22px','28px'].includes(attrs.fontSize)) style.fontSize = attrs.fontSize
    if (typeof attrs?.fontFamily === 'string' && ['serif','sans-serif','monospace'].includes(attrs.fontFamily)) style.fontFamily = attrs.fontFamily
    return h('span', { style }, [value])
  }
  return h('span', {}, [value])
}

export function renderSharedNode(node: NodeData, key = ''): VNode | string {
  const children = childrenOf(node).map((child, index) => renderSharedNode(child, key + '-' + index))
  const attrs = node.attrs as Record<string, unknown> | undefined
  switch (node.type) {
    case 'text': {
      let value: VNodeChild = String(node.text ?? '')
      const marks = Array.isArray(node.marks) ? node.marks as NodeData[] : []
      for (const mark of marks) value = markText(value, mark)
      return h('span', { key }, value)
    }
    case 'doc': return h('div', { key, class: 'shared-document' }, children)
    case 'paragraph': return h('p', { key }, children)
    case 'heading': {
      const level = Math.max(1, Math.min(6, Number(attrs?.level ?? 2)))
      return h('h' + level, { key }, children)
    }
    case 'blockquote': return h('blockquote', { key }, children)
    case 'bulletList': return h('ul', { key }, children)
    case 'orderedList': return h('ol', { key, start: Number(attrs?.start ?? 1) }, children)
    case 'listItem': return h('li', { key }, children)
    case 'horizontalRule': return h('hr', { key })
    case 'hardBreak': return h('br', { key })
    case 'codeBlock': return h('pre', { key }, [h('code', children)])
    case 'image': {
      const image = h('img', {
        src: String(attrs?.src ?? ''),
        alt: String(attrs?.alt ?? ''),
        title: String(attrs?.title ?? ''),
        style: {
          width: Math.max(30, Math.min(100, Number(attrs?.width ?? 100))) + '%',
          transform: 'rotate(' + (Number(attrs?.rotation ?? 0) % 360) + 'deg)',
          aspectRatio: attrs?.cropped ? '16 / 9' : 'auto',
          height: 'auto',
          objectFit: attrs?.cropped ? 'cover' : 'contain',
        },
      })
      return h('figure', { key }, [image, attrs?.title ? h('figcaption', String(attrs.title)) : null])
    }
    default: return h('div', { key }, children)
  }
}
