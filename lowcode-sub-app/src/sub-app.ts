import type { RouteRecordRaw } from 'vue-router'
import DataSourceListPage from './pages/DataSourceListPage.vue'
import DatasetListPage from './pages/DatasetListPage.vue'
import ScreenListPage from './pages/ScreenListPage.vue'
import ScreenEditorPage from './pages/ScreenEditorPage.vue'

// Shell 把这些相对路由挂在 /apps/lowcode 下；路径与 Resource Manifest 菜单叶子的 path 一致，路由名以应用编码为前缀，保证全局唯一。
export const routes: RouteRecordRaw[] = [
  { path: 'datasources', name: 'lowcode:datasources', component: DataSourceListPage },
  { path: 'datasets', name: 'lowcode:datasets', component: DatasetListPage },
  { path: 'screens', name: 'lowcode:screens', component: ScreenListPage },
  { path: 'screens/:id/edit', name: 'lowcode:screen-editor', component: ScreenEditorPage },
]
