import { createApp } from 'vue'
import App from './App.vue'
import './styles/tokens.css'
import './styles/app.css'
import './styles/write.css'
import './styles/auth.css'
import './styles/views.css'

createApp(App).mount('#app')

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js')
  })
}
