<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElTableColumn } from 'element-plus'
import { useAccessControl, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ListPage,
  PageHeader,
  PageToolbar,
  formatDateTime,
  type ErrorTip,
} from '@crane199709/ui'
import DataSourceDetailDrawer from './DataSourceDetailDrawer.vue'
import DataSourceFormDrawer from './DataSourceFormDrawer.vue'
import ResourceDeleteDialog from './ResourceDeleteDialog.vue'
import { errorTip, sourceAddress, typeLabels, type DataSource } from './data-source'

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
      browse: '浏览表',
      delete: '删除',
      empty: '暂无数据源',
      failed: '数据源加载失败，请稍后重试',
    },
  },
})

const client = useTenantClient()
const access = useAccessControl()
// 操作列是表格结构的一部分，v-perm 只能隐藏元素、去不掉列，因此按权限条件渲染；接口仍由后端校验权限。
const canView = computed(() => access.has('datasource:view'))
const canManage = computed(() => access.has('datasource:manage'))
const dataSources = ref<Page<DataSource> | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const deleting = ref<DataSource | null>(null)
const deleteOpen = ref(false)
const drawerOpen = ref(false)
const detailOpen = ref(false)
const viewing = ref<DataSource | null>(null)
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

function openDetails(row: DataSource) {
  viewing.value = row
  detailOpen.value = true
}

function openForm(row: DataSource | null) {
  editing.value = row
  drawerOpen.value = true
}

function remove(row: DataSource) {
  deleting.value = row
  deleteOpen.value = true
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
    <ListPage :page="dataSources" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <el-table-column :label="t('name')" min-width="160" show-overflow-tooltip prop="name" />
      <el-table-column :label="t('type')" width="110">
        <template #default="{ row }">{{
          typeLabels[row.type as DataSource['type']] ?? row.type
        }}</template>
      </el-table-column>
      <el-table-column :label="t('address')" min-width="210" show-overflow-tooltip>
        <template #default="{ row }">{{ sourceAddress(row as DataSource) || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('username')" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.username || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('updatedAt')" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column
        v-if="canView || canManage"
        :label="t('actions')"
        :width="canManage ? 190 : 90"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            v-if="canView && row.type !== 'API'"
            link
            type="primary"
            @click="openDetails(row as DataSource)"
            >{{ t('browse') }}</el-button
          >
          <el-button v-if="canManage" link type="primary" @click="openForm(row as DataSource)">{{
            t('edit')
          }}</el-button>
          <el-button v-if="canManage" link type="danger" @click="remove(row as DataSource)">{{
            t('delete')
          }}</el-button>
        </template>
      </el-table-column>
    </ListPage>
    <ResourceDeleteDialog
      v-model="deleteOpen"
      :resource="deleting"
      path="data-sources"
      label="数据源"
      @deleted="load()"
    />
    <DataSourceDetailDrawer v-model="detailOpen" :source="viewing" />
    <DataSourceFormDrawer v-model="drawerOpen" :editing="editing" @saved="load()" />
  </section>
</template>
