/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Gateway address for the dev-server proxy. Read on the Node side only
// (not a VITE_ variable), so it never reaches the browser bundle.
const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Same single-origin shape as the nginx container (D13): only the admin API
    // and the health endpoint go to the gateway; nothing else is forwarded.
    proxy: {
      '/admin/': gatewayUrl,
      '/actuator/health': gatewayUrl,
    },
  },
  test: {
    environment: 'jsdom',
    include: ['test/**/*.test.{ts,tsx}'],
    setupFiles: './test/setup.ts',
    globals: false,
    css: false,
  },
})
