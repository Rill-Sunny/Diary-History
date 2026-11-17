import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [vue()],
  cacheDir: resolve(tmpdir(), 'liubai-vite-cache'),
  build: { manifest: 'asset-manifest.json' },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: false } },
  },
})
