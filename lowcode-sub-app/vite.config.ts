import { defineSubAppConfig } from '@crane199709/sub-app-kit/vite'

// 开发模式经 index.html 运行独立宿主；构建产物暴露 ./sub-app 的 remoteEntry.js，入口地址登记到 App Registry。
export default defineSubAppConfig({ appCode: 'lowcode', port: 5176, apiPrefixes: ['/lowcode'] })
