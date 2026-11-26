import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const proxy = { '/api': `http://127.0.0.1:${process.env.API_PORT || '8080'}` }

export default defineConfig({
  plugins: [vue()],
  server: {host:'127.0.0.1', proxy},
  preview: {host:'127.0.0.1', proxy},
})
