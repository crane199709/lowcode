<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElMessageBox, ElTableColumn } from 'element-plus'
import { useAccessControl, useTenantClient, type Page } from '@crane199709/sdk'
import { ErrorAlert, ListPage, PageHeader, PageToolbar, formatDateTime, type ErrorTip } from '@crane199709/ui'
import DataSourceFormDrawer from './DataSourceFormDrawer.vue'
import { errorTip, typeLabels, type DataSource } from './data-source'

const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '数据源',
      create: '新增',
      name: '名称',
      type: '类型',
      address: '连接地址',
      username: '账号',
      updatedAt: '更新时间',
      actions: '操作',
      edit: '编辑',
      delete: '删除',
      empty: '暂无数据源',
      failed: '数据源加载失败，请稍后重试',
      deleteTitle: '删除数据源',
      deleteConfirm: '删除「{name}」后，基于它的数据集将无法取数。',
      deleteFailed: '删除失败，请稍后重试',
      confirm: '删除',
      cancel: '取消',
    },
  },
})

const client = useTenantClient()
const access = useAccessControl()
// 操作列是表格结构的一部分，v-perm 只能隐藏元素、去不掉列，因此按权限条件渲染；接口仍由后端校验权限。
const canManage = computed(() => access.has('datasource:manage'))
const dataSources = ref<Page<DataSource> | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const drawerOpen = ref(false)
const editing = ref<DataSource | null>(null)

async function load(page = dataSources.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    dataSources.value = await client.page<DataSource>(`/lowcode/data-sources?page=${page}&size=20`)
  } catch (cause) {
    dataSources.value = null
    error.value = errorTip(cause, t('failed'))
  } finally {
    busy.value = false
  }
}

function openForm(row: DataSource | null) {
  editing.value = row
  drawerOpen.value = true
}

async function remove(row: DataSource) {
  try {
    await ElMessageBox.confirm(t('deleteConfirm', { name: row.name }), t('deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('confirm'),
      cancelButtonText: t('cancel'),
    })
  } catch {
    return
  }
  error.value = null
  try {
    await client.api<void>(`/lowcode/data-sources/${row.id}`, { method: 'DELETE' })
    await load()
  } catch (cause) {
    error.value = errorTip(cause, t('deleteFailed'))
  }
}

onMounted(() => load())
</script>

<template>
  <section :aria-label="t('title')">
    <PageHeader :title="t('title')" />
    <PageToolbar>
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="openForm(null)">{{ t('create') }}</el-button>
      </template>
    </PageToolbar>
    <ErrorAlert v-if="error" :tip="error" />
    <ListPage :page="dataSources" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <el-table-column :label="t('name')" min-width="180" show-overflow-tooltip prop="name" />
      <el-table-column :label="t('type')" width="110">
        <template #default="{ row }">{{ typeLabels[row.type as DataSource['type']] ?? row.type }}</template>
      </el-table-column>
      <el-table-column :label="t('address')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ row.host }}:{{ row.port }}/{{ row.database }}</template>
      </el-table-column>
      <el-table-column :label="t('username')" min-width="120" show-overflow-tooltip prop="username" />
      <el-table-column :label="t('updatedAt')" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column v-if="canManage" :label="t('actions')" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openForm(row as DataSource)">{{ t('edit') }}</el-button>
          <el-button link type="danger" @click="remove(row as DataSource)">{{ t('delete') }}</el-button>
        </template>
      </el-table-column>
    </ListPage>
    <DataSourceFormDrawer v-model="drawerOpen" :editing="editing" @saved="load()" />
  </section>
</template>
