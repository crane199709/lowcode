<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElMessageBox, ElTableColumn } from 'element-plus'
import { useAccessControl, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ListPage,
  PageHeader,
  PageToolbar,
  formatDateTime,
  type ErrorTip,
} from '@crane199709/ui'
import DatasetFormDrawer from './DatasetFormDrawer.vue'
import { errorTip } from './data-source'
import type { Dataset } from './dataset'

const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '数据集',
      create: '新增',
      name: '名称',
      source: '数据源',
      fieldCount: '字段数',
      updatedAt: '更新时间',
      actions: '操作',
      edit: '编辑',
      browse: '查看',
      delete: '删除',
      empty: '暂无数据集',
      failed: '数据集加载失败，请稍后重试',
      deleteTitle: '删除数据集',
      deleteConfirm: '删除「{name}」后，引用它的大屏将无法取数。',
      deleteFailed: '删除失败，请稍后重试',
      confirm: '删除',
      cancel: '取消',
    },
  },
})

const client = useTenantClient()
const access = useAccessControl()
// 操作列是表格结构的一部分，v-perm 只能隐藏元素、去不掉列，因此按权限条件渲染；接口仍由后端校验权限。
const canManage = computed(() => access.has('dataset:manage'))
const datasets = ref<Page<Dataset> | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const drawerOpen = ref(false)
const readonly = ref(false)
const editing = ref<Dataset | null>(null)

async function load(page = datasets.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    datasets.value = await client.page<Dataset>(`/lowcode/datasets?page=${page}&size=20`)
  } catch (cause) {
    datasets.value = null
    error.value = errorTip(cause, t('failed'))
  } finally {
    busy.value = false
  }
}

function openDetails(row: Dataset) {
  readonly.value = true
  editing.value = row
  drawerOpen.value = true
}

function openForm(row: Dataset | null) {
  readonly.value = false
  editing.value = row
  drawerOpen.value = true
}

async function remove(row: Dataset) {
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
    await client.api<void>(`/lowcode/datasets/${row.id}`, { method: 'DELETE' })
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
        <el-button v-if="canManage" type="primary" @click="openForm(null)">{{
          t('create')
        }}</el-button>
      </template>
    </PageToolbar>
    <ErrorAlert v-if="error" :tip="error" />
    <ListPage :page="datasets" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <el-table-column :label="t('name')" min-width="160" show-overflow-tooltip prop="name" />
      <el-table-column :label="t('source')" min-width="210" show-overflow-tooltip>
        <template #default="{ row }">{{ row.dataSourceName || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('fieldCount')" width="120" align="right">
        <template #default="{ row }">{{ row.fields.length }}</template>
      </el-table-column>
      <el-table-column :label="t('updatedAt')" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('actions')" :width="canManage ? 200 : 90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetails(row as Dataset)">{{
            t('browse')
          }}</el-button>
          <el-button v-if="canManage" link type="primary" @click="openForm(row as Dataset)">{{
            t('edit')
          }}</el-button>
          <el-button v-if="canManage" link type="danger" @click="remove(row as Dataset)">{{
            t('delete')
          }}</el-button>
        </template>
      </el-table-column>
    </ListPage>
    <DatasetFormDrawer
      v-model="drawerOpen"
      :editing="editing"
      :readonly="readonly"
      @saved="load()"
    />
  </section>
</template>
