import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [
        ElementPlusResolver({
          directives: true
        })
      ]
    }),
    Components({
      resolvers: [
        ElementPlusResolver()
      ]
    }),
    ...(process.env.NODE_ENV === 'development' ? [vueDevTools()] : []),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 用 127.0.0.1 而非 localhost：Node 17+ 把 localhost 解析为 IPv6 ::1，
        // 后端只监听 IPv4，走 localhost 会导致代理 ECONNREFUSED → 页面全部 500
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return
          }
          if (id.includes('element-plus')) {
            return 'element-plus'
          }
          if (id.includes('@element-plus/icons-vue')) {
            return 'element-plus-icons'
          }
          if (id.includes('/marked/')) {
            return 'markdown-core'
          }
          if (id.includes('highlight.js')) {
            return 'markdown-highlight'
          }
          if (id.includes('vue') || id.includes('vue-router')) {
            return 'vue-core'
          }
        }
      }
    }
  }
})
