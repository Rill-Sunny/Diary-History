const CACHE_PREFIX = 'liubai-shell-'
const CACHE = CACHE_PREFIX + 'v2'

async function cacheShell(page) {
  const cache = await caches.open(CACHE)
  const response = await fetch('/asset-manifest.json', { cache: 'no-store' })
  if (!response.ok) throw new Error('Application manifest unavailable')
  const manifest = await response.json()
  const files = new Set(['/assets/demo-lake.png'])
  for (const item of Object.values(manifest)) {
    for (const path of [item.file, ...(item.css || []), ...(item.assets || [])]) {
      if (typeof path === 'string' && path.startsWith('assets/')) files.add('/' + path)
    }
  }
  const missing = await Promise.all([...files].map(async (path) => await cache.match(path) ? null : path))
  await cache.addAll(missing.filter(Boolean).map((path) => new Request(path, { cache: 'reload' })))
  if (page) await cache.put('/', page)
  else await cache.add(new Request('/', { cache: 'reload' }))
}

self.addEventListener('install', (event) => {
  event.waitUntil(cacheShell().then(() => self.skipWaiting()))
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((key) => key.startsWith(CACHE_PREFIX) && key !== CACHE).map((key) => caches.delete(key))),
    ).then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return
  if (event.request.mode === 'navigate') {
    const network = fetch(event.request)
    if (url.pathname === '/' && !url.search) {
      event.waitUntil(network.then((response) => {
        if (response.ok && response.headers.get('content-type')?.includes('text/html')) return cacheShell(response.clone())
      }).catch(() => undefined))
    }
    event.respondWith(network.catch(async () => (await caches.open(CACHE)).match('/')))
  } else if (url.pathname.startsWith('/assets/')) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE)
      const cached = await cache.match(event.request)
      if (cached) return cached
      const response = await fetch(event.request)
      if (response.ok && response.type === 'basic') await cache.put(event.request, response.clone())
      return response
    })())
  }
})

self.addEventListener('push', (event) => {
  let message = {}
  try {
    message = event.data?.json() || {}
  } catch {
    // The browser requires a visible notification even if the payload is malformed.
  }
  event.waitUntil(self.registration.showNotification(message.title || '留白日记', {
    body: message.body || '留一点时间给今天。',
    tag: 'liubai-writing-reminder',
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
    const openDiary = windows.find((window) => new URL(window.url).origin === self.location.origin)
    return openDiary ? openDiary.focus() : self.clients.openWindow('/')
  }))
})
