import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Bind on 0.0.0.0 so the nginx container can reach the dev server via
    // host.docker.internal:5173. Default (127.0.0.1) blocks that path.
    host: true,
    // Vite ≥ 5.4 rejects requests whose Host header isn't whitelisted.
    // We accept localhost (the canonical dev origin) plus the bare
    // upstream name "vite" as a fallback in case nginx slips and forwards
    // its own upstream label. lvh.me / *.lvh.me : domaine public résolvant
    // vers 127.0.0.1, utilisé en dev pour tester le cookie SSO Superset
    // (Domain=.lvh.me, partagé entre lvh.me et analytics.lvh.me).
    allowedHosts: ['localhost', '127.0.0.1', 'vite', 'lvh.me', '.lvh.me'],
    proxy: {
      '/api': {
        target: process.env.CONSOLE_API_URL ?? 'http://localhost:8041',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
