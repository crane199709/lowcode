import { defineConfig, mergeConfig } from 'vite'
import config from '../../vite.config.ts'

export default defineConfig(async (environment) =>
  mergeConfig(await (typeof config === 'function' ? config(environment) : config), {
    preview: {
      host: '127.0.0.1',
      port: 15176,
      strictPort: true,
      cors: { origin: ['http://127.0.0.1:15174'] },
    },
  }),
)
