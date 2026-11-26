import { cpSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue(), {
    name: 'pdfjs-reader-assets',
    apply: 'build',
    writeBundle(output) {
      if (!output.dir) return
      for (const directory of ['cmaps', 'standard_fonts', 'wasm', 'iccs']) {
        cpSync(resolve('node_modules/pdfjs-dist', directory), resolve(output.dir, 'pdfjs', directory), { recursive: true })
      }
      cpSync(resolve('node_modules/pdfjs-dist/LICENSE'), resolve(output.dir, 'pdfjs/LICENSE'))
    },
  }],
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080' },
    },
  },
})
