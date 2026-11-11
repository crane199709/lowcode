<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElButton, ElPagination, ElTable, ElTableColumn } from 'element-plus'
import {
  chartHint,
  chartNames,
  dataWidgetReady,
  type DataWidgetType,
  type ChartConfig,
} from './widget-query'
import type { ScreenWidget } from './screen'
import { useWidgetData } from './use-widget-data'
const props = defineProps<{
  widget: ScreenWidget & { type: DataWidgetType; config: ChartConfig }
  queryBase: string
}>()
const ready = computed(() => dataWidgetReady(props.widget.type, props.widget.config))
const { result, message, loading, load } = useWidgetData(props, ready)
const page = ref(1)
const pageRows = computed(
  () => result.value?.rows.slice((page.value - 1) * 50, page.value * 50) ?? [],
)
watch(result, () => {
  page.value = 1
})
const indicator = computed(() => {
  const raw = result.value?.rows[0]?.[0]
  if (raw == null) return '—'
  if (typeof raw !== 'number') return String(raw)
  const format = props.widget.config.numberFormat
  const options =
    format === 'INTEGER'
      ? { maximumFractionDigits: 0 }
      : format === 'DECIMAL_2'
        ? { minimumFractionDigits: 2, maximumFractionDigits: 2 }
        : { maximumSignificantDigits: 21 }
  return new Intl.NumberFormat('zh-CN', options).format(raw)
})
// 表格位于设计画布中，字号与行高按设计像素渲染，跟随整个大屏缩放。
const cellStyle = { fontSize: '24px', height: '56px' }
</script>
<template>
  <div class="data-widget" role="region" :aria-label="chartNames[widget.type]" :aria-busy="loading">
    <div v-if="!ready || message || !result?.rows.length" class="data-state">
      <span>{{
        !ready ? chartHint(widget.type) : message || (loading ? '正在取数…' : '暂无数据')
      }}</span>
      <el-button v-if="message" link @click="load">重试</el-button>
    </div>
    <div v-else-if="widget.type === 'INDICATOR'" class="indicator">
      <div :style="{ fontSize: '32px' }">{{ widget.config.title || '指标卡' }}</div>
      <div class="indicator-value" :style="{ fontSize: '80px' }">
        {{ indicator }} <span :style="{ fontSize: '28px' }">{{ widget.config.unit }}</span>
      </div>
    </div>
    <template v-else>
      <el-table
        :data="pageRows"
        height="100%"
        :cell-style="cellStyle"
        :header-cell-style="cellStyle"
        :empty-text="loading ? '正在取数…' : '暂无数据'"
      >
        <el-table-column
          v-for="(column, index) in result.columns"
          :key="column.id"
          :label="column.label"
          :min-width="240"
          :align="column.type === 'NUMBER' ? 'right' : 'left'"
          show-overflow-tooltip
        >
          <template #default="scope">{{ scope.row[index] ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <div class="table-footer" :style="{ fontSize: '24px' }" @pointerdown.stop @click.stop>
        <span v-if="result.truncated" class="row-limit" role="status"
          >仅显示前 {{ result.maxRows }} 行{{
            widget.type === 'DETAIL_TABLE' ? '；已达明细读取上限' : ''
          }}</span
        >
        <span v-else>{{ result.rows.length }} 行</span>
        <el-pagination
          v-if="result.rows.length > 50"
          v-model:current-page="page"
          :page-size="50"
          :total="result.rows.length"
          layout="prev, pager, next"
          :pager-count="5"
          :style="{
            '--el-pagination-font-size': '24px',
            '--el-pagination-button-height': '48px',
            '--el-pagination-button-width': '48px',
          }"
        />
      </div>
    </template>
  </div>
</template>
<style scoped>
.data-widget {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  overflow: hidden;
}
.data-state,
.indicator {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--forge-space-3);
  padding: var(--forge-space-4);
}
.data-state {
  font-size: var(--forge-font-size-lg);
  color: var(--el-text-color-secondary);
}
.indicator-value {
  font-weight: bold;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--forge-space-3);
  padding: var(--forge-space-3);
  font-size: var(--forge-font-size-lg);
}
.row-limit {
  color: var(--el-color-warning);
}
.data-widget :deep(.el-table) {
  flex: 1;
  min-height: 0;
}
</style>
