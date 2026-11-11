<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTenantClient } from '@crane199709/sdk'
import { ElButton } from 'element-plus'
import { init, use, type EChartsType } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { BarConfig, WidgetData } from './widget-query'
import type { ScreenWidget } from './screen'
import { errorTip, jsonBody } from './data-source'
use([BarChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])
const props = defineProps<{
  widget: ScreenWidget & { type: 'BAR'; config: BarConfig }
  queryBase: string
}>()
const client = useTenantClient()
const surface = ref<HTMLElement>()
const result = ref<WidgetData>()
const message = ref('')
const loading = ref(false)
const ready = computed(
  () =>
    !!props.widget.config.datasetId &&
    (props.widget.config.query?.dimensions?.length ?? 0) > 0 &&
    (props.widget.config.query?.measures?.length ?? 0) > 0,
)
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
function render() {
  if (!surface.value || !result.value) {
    return
  }
  chart ??= init(surface.value, 'dark', { renderer: 'svg' })
  const dimensions = props.widget.config.query.dimensions.length
  chart.setOption(
    {
      backgroundColor: 'transparent',
      animation: false,
      grid: { left: 72, right: 32, top: 64, bottom: 64 },
      tooltip: { trigger: 'axis' },
      legend: { top: 16 },
      xAxis: {
        type: 'category',
        data: result.value.rows.map((row) =>
          row
            .slice(0, dimensions)
            .map((v) => v ?? '—')
            .join(' / '),
        ),
      },
      yAxis: { type: 'value' },
      series: props.widget.config.query.measures.map((_, i) => ({
        type: 'bar',
        name: result.value!.columns[dimensions + i]?.label ?? '',
        data: result.value!.rows.map((row) => row[dimensions + i]),
      })),
    },
    true,
  )
  chart.resize()
}
async function load() {
  const current = ++generation
  clearTimeout(timer)
  if (!ready.value) {
    return
  }
  loading.value = true
  try {
    const data = await client.api<WidgetData>(
      props.queryBase.endsWith('/draft')
        ? `${props.queryBase}/query`
        : `${props.queryBase}/widgets/${encodeURIComponent(props.widget.id)}/query`,
      props.queryBase.endsWith('/draft') ? jsonBody('POST', props.widget) : undefined,
    )
    if (current !== generation) {
      return
    }
    result.value = data
    message.value = ''
    await nextTick()
    render()
  } catch (cause) {
    if (current !== generation) {
      return
    }
    result.value = undefined
    message.value = errorTip(cause, '组件取数失败，请重试').message
    chart?.clear()
  } finally {
    if (current === generation) {
      loading.value = false
      const seconds = props.widget.config.refreshSeconds
      if (!props.queryBase.endsWith('/draft') && seconds >= 10) {
        timer = setTimeout(load, Math.min(seconds * 1000, 2147483647))
      }
    }
  }
}
watch(
  () =>
    JSON.stringify([
      props.queryBase,
      props.widget.id,
      props.widget.config.datasetId,
      props.widget.config.query,
      props.widget.config.refreshSeconds,
    ]),
  () => {
    ++generation
    clearTimeout(timer)
    loading.value = false
    result.value = undefined
    message.value = ''
    chart?.clear()
    if (ready.value) {
      timer = setTimeout(load, 150)
    }
  },
  { immediate: true },
)
onMounted(() => {
  observer = new ResizeObserver(() => chart?.resize())
  if (surface.value) {
    observer.observe(surface.value)
  }
})
onBeforeUnmount(() => {
  ++generation
  clearTimeout(timer)
  observer?.disconnect()
  chart?.dispose()
})
</script>
<template>
  <div class="bar-widget" role="img" aria-label="基础柱状图" :aria-busy="loading">
    <div ref="surface" class="chart-surface" />
    <div v-if="!ready || message || !result?.rows.length" class="chart-state">
      <span>{{
        !ready ? '选择数据集并拖入维度、度量' : message || (loading ? '正在取数…' : '暂无数据')
      }}</span>
      <el-button v-if="message" link @pointerdown.stop @click.stop="load">重试</el-button>
    </div>
    <span v-if="result?.truncated" class="row-limit">仅显示前 {{ result.maxRows }} 行</span>
  </div>
</template>
<style scoped>
.bar-widget {
  width: 100%;
  height: 100%;
  position: relative;
}
.chart-surface {
  width: 100%;
  height: 100%;
}
.chart-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--forge-space-2);
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  padding: var(--forge-space-3);
  font-size: var(--forge-font-size-md);
}
.row-limit {
  position: absolute;
  right: var(--forge-space-3);
  bottom: var(--forge-space-2);
  font-size: var(--forge-font-size-sm);
  color: var(--el-color-warning);
}
</style>
