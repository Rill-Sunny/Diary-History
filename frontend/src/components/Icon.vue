<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 19 })

const paths: Record<string, string> = {
  flame: '<path d="M12 3c1 4-2 5-2 8 0 1 .6 2 1.5 2.5C12 11 14 9 14 6c3 3 5 6.5 5 9a7 7 0 0 1-14 0c0-2.8 1.2-5.5 3-8-.2 3 1 3.5 1 3.5C9 7 11 6 12 3Z"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
  'chevron-left': '<path d="m14 6-6 6 6 6"/>',
  'chevron-right': '<path d="m10 6 6 6-6 6"/>',
  logout: '<path d="M10 4H4v16h6M14 8l4 4-4 4M8 12h12"/>',
  leaf: '<path d="M20 4c-8.5 0-14 4.2-14 10.2A5.8 5.8 0 0 0 11.8 20C17.8 20 20 12.5 20 4Z"/><path d="M4 21c2.8-4.8 6.2-7.8 11-10"/>',
  pen: '<path d="m15 5 4 4"/><path d="m4 20 4.2-.9L19 8.3a2.1 2.1 0 0 0-3-3L5.2 16.1 4 20Z"/>',
  book: '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22V5.5Z"/><path d="M4 5v15"/>',
  calendar: '<rect x="3" y="4.5" width="18" height="17" rx="2"/><path d="M7 2v5M17 2v5M3 9h18"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  tag: '<path d="M20 13 13 20 3 10V4h6l11 9Z"/><circle cx="7.5" cy="7.5" r=".7"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.7 10.7 6.6-4.4M8.7 13.3l6.6 4.4"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  trash: '<path d="M4 7h16M10 11v6m4-6v6M6 7l1 14h10l1-14M9 7V4h6v3"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="m19.4 15 .1.1 1.3 1-1.4 2.4-1.6-.6a8 8 0 0 1-1.6.9l-.3 1.7h-2.8l-.3-1.7a8 8 0 0 1-1.6-.9l-1.6.6-1.4-2.4 1.3-1a7 7 0 0 1 0-1.9l-1.3-1 1.4-2.4 1.6.6a8 8 0 0 1 1.6-.9l.3-1.7h2.8l.3 1.7a8 8 0 0 1 1.6.9l1.6-.6 1.4 2.4-1.3 1a7 7 0 0 1-.1 1.9Z" transform="translate(-1 -1)"/>',
  search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/>',
  moon: '<path d="M20.5 15.5A8.5 8.5 0 0 1 8.5 3.5 8.5 8.5 0 1 0 20.5 15.5Z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9" r="1.5"/><path d="m21 15-5-5L5 20"/>',
  bold: '<path d="M7 4h6a4 4 0 0 1 0 8H7zM7 12h7a4 4 0 0 1 0 8H7z"/>',
  italic: '<path d="M19 4h-9M14 20H5M15 4 9 20"/>',
  heading: '<path d="M4 5v14M4 12h10M14 5v14M20 8v8"/>',
  quote: '<path d="M10 11H5a4 4 0 0 1 4-4M19 11h-5a4 4 0 0 1 4-4M5 11v5h5v-5M14 11v5h5v-5"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.1 0l2-2A5 5 0 0 0 12 4l-1.1 1.1"/><path d="M14 11a5 5 0 0 0-7.1 0l-2 2A5 5 0 0 0 12 20l1.1-1.1"/>',
  list: '<path d="M9 6h12M9 12h12M9 18h12"/><path d="M4 6h.01M4 12h.01M4 18h.01"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10a6 6 0 0 1 0 12h-2"/>',
  redo: '<path d="m15 14 5-5-5-5"/><path d="M20 9H10a6 6 0 0 0 0 12h2"/>',
  close: '<path d="m18 6-12 12M6 6l12 12"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  admin: '<path d="M12 3 4 6v5c0 5.2 3.4 8.6 8 10 4.6-1.4 8-4.8 8-10V6l-8-3Z"/><path d="m9 12 2 2 4-4"/>',
  restore: '<path d="M3 11a9 9 0 1 1 2.6 6.4"/><path d="M3 4v7h7"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5"/><path d="M5 17v4h14v-4"/>',
  lock: '<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 1 1 8 0v3"/>',
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" v-html="paths[name] ?? paths.leaf" />
</template>
