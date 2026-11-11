import type { RouteRecordRaw } from 'vue-router'
import HomePage from './HomePage.vue'

// Shell 把这些相对路由挂在 /apps/lowcode 下；路由名以应用编码为前缀，保证全局唯一。
export const routes: RouteRecordRaw[] = [
  { path: 'home', name: 'lowcode:home', component: HomePage },
]
