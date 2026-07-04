import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    server: {
      deps: {
        // msw 2.x тянет `graphql`, чей CJS-билд ломает ESM-interop vitest
        // ("does not provide an export named 'parse'") при некоторых порядках загрузки модулей.
        // Inline форсирует единообразную трансформацию — весь suite остаётся детерминированным.
        inline: ['graphql'],
      },
    },
  },
})
