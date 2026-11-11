<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElEmpty, ElSkeleton, ElTable, ElTableColumn } from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, type ErrorTip } from '@crane199709/ui'
import { errorTip, type SourceColumn, type SourceTable } from './data-source'

const props = defineProps<{ sourceId: string }>()
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      tables: '表',
      schema: 'Schema',
      name: '表名',
      columns: '列',
      column: '列名',
      type: '类型',
      reload: '重新加载',
      noTables: '当前账号没有可浏览的表',
      chooseTable: '选择表以查看列',
      noColumns: '该表没有可浏览的列',
      tablesFailed: '表加载失败，请稍后重试',
      columnsFailed: '列加载失败，请稍后重试',
    },
  },
})
const client = useTenantClient()
const tables = ref<SourceTable[]>([])
const columns = ref<SourceColumn[]>([])
const selected = ref<SourceTable | null>(null)
const tableError = ref<ErrorTip | null>(null)
const columnError = ref<ErrorTip | null>(null)
const loadingTables = ref(false)
const loadingColumns = ref(false)
let tableRequest = 0
let columnRequest = 0

async function loadTables() {
  const request = ++tableRequest
  ++columnRequest
  tables.value = []
  columns.value = []
  selected.value = null
  tableError.value = null
  columnError.value = null
  loadingTables.value = true
  loadingColumns.value = false
  try {
    const result = await client.api<SourceTable[]>(`/lowcode/data-sources/${props.sourceId}/tables`)
    if (request === tableRequest) tables.value = result
  } catch (cause) {
    if (request === tableRequest) tableError.value = errorTip(cause, t('tablesFailed'))
  } finally {
    if (request === tableRequest) loadingTables.value = false
  }
}

function tableKey(table: SourceTable) {
  return JSON.stringify([table.catalog, table.schema, table.name])
}

async function selectTable(table: SourceTable) {
  selected.value = table
  columns.value = []
  columnError.value = null
  loadingColumns.value = true
  const request = ++columnRequest
  const query = new URLSearchParams({
    catalog: table.catalog,
    schema: table.schema,
    table: table.name,
  })
  try {
    const result = await client.api<SourceColumn[]>(
      `/lowcode/data-sources/${props.sourceId}/columns?${query}`,
    )
    if (request === columnRequest) columns.value = result
  } catch (cause) {
    if (request === columnRequest) columnError.value = errorTip(cause, t('columnsFailed'))
  } finally {
    if (request === columnRequest) loadingColumns.value = false
  }
}

watch(() => props.sourceId, loadTables, { immediate: true })
onUnmounted(() => {
  ++tableRequest
  ++columnRequest
})
</script>

<template>
  <section class="browser" :aria-label="t('tables')">
    <div class="heading">
      <h3>{{ t('tables') }}</h3>
      <el-button :loading="loadingTables" @click="loadTables">{{ t('reload') }}</el-button>
    </div>
    <ErrorAlert v-if="tableError" :tip="tableError" />
    <el-skeleton v-else-if="loadingTables" animated :rows="3" />
    <el-table
      v-else
      :data="tables"
      highlight-current-row
      :row-key="tableKey"
      :current-row-key="selected ? tableKey(selected) : undefined"
      :empty-text="t('noTables')"
      :max-height="'calc(var(--forge-space-5) * 10)'"
      @row-click="selectTable"
    >
      <el-table-column :label="t('schema')" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.schema || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('name')" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            class="table-name"
            :title="row.name"
            @click.stop="selectTable(row as SourceTable)"
            >{{ row.name }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <template v-if="tables.length">
      <div class="heading">
        <h3>{{ t('columns') }}</h3>
        <span
          v-if="selected"
          class="selected"
          :title="[selected.schema, selected.name].filter(Boolean).join('.')"
          >{{ [selected.schema, selected.name].filter(Boolean).join('.') }}</span
        >
      </div>
      <el-empty v-if="!selected" :description="t('chooseTable')" />
      <ErrorAlert v-else-if="columnError" :tip="columnError" />
      <el-skeleton v-else-if="loadingColumns" animated :rows="3" />
      <el-table v-else :data="columns" :empty-text="t('noColumns')">
        <el-table-column :label="t('column')" prop="name" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('type')" prop="type" min-width="160" show-overflow-tooltip />
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.browser {
  display: grid;
  gap: var(--forge-space-4);
}
.heading {
  display: flex;
  align-items: center;
  gap: var(--forge-space-3);
  min-width: 0;
}
h3 {
  margin: 0;
  font-size: var(--forge-font-size-md);
}
.heading .el-button {
  margin-left: auto;
}
.selected {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
}
.table-name {
  max-width: 100%;
}
.table-name :deep(span) {
  display: block;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
