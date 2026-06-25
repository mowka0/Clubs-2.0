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
        // msw 2.x pulls in `graphql`, whose CJS build trips vitest's ESM interop
        // ("does not provide an export named 'parse'") under certain module-load orders.
        // Inlining it forces a consistent transform so the full suite stays deterministic.
        inline: ['graphql'],
      },
    },
  },
})
