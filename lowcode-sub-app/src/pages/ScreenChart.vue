<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import { init, use, type EChartsType } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { chartHint, chartNames, chartReady, type ChartConfig, type ChartType } from './widget-query'
import type { ScreenWidget } from './screen'
import { useWidgetData } from './use-widget-data'
use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, SVGRenderer])
const props = defineProps<{
  widget: ScreenWidget & { type: ChartType; config: ChartConfig }
  queryBase: string
}>()
const surface = ref<HTMLElement>()
const ready = computed(() => chartReady(props.widget.type, props.widget.config))
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined
const { result, message, loading, load } = useWidgetData(props, ready)
function render() {
  if (!surface.value || !result.value) {
    return
  }
  chart ??= init(surface.value, 'dark', { renderer: 'svg' })
  const dimensions = props.widget.config.query.dimensions.length
  let categories = result.value.rows.map((row) =>
    row
      .slice(0, dimensions)
      .map((v) => v ?? '—')
      .join(' / '),
  )
  let series: object[] = props.widget.config.query.measures.map((_, i) => ({
    type: props.widget.type === 'LINE' ? 'line' : 'bar',
    name: result.value!.columns[dimensions + i]?.label ?? '',
    data: result.value!.rows.map((row) => row[dimensions + i]),
  }))
  if (props.widget.type === 'STACKED_BAR') {
    // 两个维度已由服务端聚合；转为系列时保留缺失组合及聚合结果中的 NULL，不补零或再次求和。
    const keys = [...new Set(result.value.rows.map((row) => row[0]))]
    const stacks = [...new Set(result.value.rows.map((row) => row[1]))]
    const cells = new Map(
      result.value.rows.map((row) => [JSON.stringify([row[0], row[1]]), row[2]]),
    )
    categories = keys.map((key) => String(key ?? '—'))
    series = stacks.map((stack) => ({
      type: 'bar',
      stack: 'total',
      name: String(stack ?? '—'),
      data: keys.map((key) => cells.get(JSON.stringify([key, stack])) ?? null),
    }))
  }
  chart.setOption(
    props.widget.type === 'PIE'
      ? {
          backgroundColor: 'transparent',
          animation: false,
          tooltip: { trigger: 'item' },
          legend: { top: 16 },
          series: [
            {
              type: 'pie',
              stillShowZeroSum: false,
              name: result.value.columns[1]?.label ?? '',
              radius: [props.widget.config.donut ? '45%' : '0%', '68%'],
              label: { formatter: '{b}: {c}' },
              data: result.value.rows.map((row) => ({
                name: String(row[0] ?? '—'),
                value: row[1],
              })),
            },
          ],
        }
      : {
          backgroundColor: 'transparent',
          animation: false,
          grid: { left: 72, right: 32, top: 64, bottom: 64 },
          tooltip: { trigger: 'axis' },
          legend: { top: 16 },
          xAxis: { type: 'category', data: categories },
          yAxis: { type: 'value' },
          series,
        },
    true,
  )
  chart.resize()
}
watch(result, async () => {
  if (!result.value) {
    chart?.clear()
    return
  }
  await nextTick()
  render()
})
watch(() => props.widget.config.donut, render)
onMounted(() => {
  observer = new ResizeObserver(() => chart?.resize())
  if (surface.value) {
    observer.observe(surface.value)
  }
})
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>
<template>
  <div class="chart-widget" role="img" :aria-label="chartNames[widget.type]" :aria-busy="loading">
    <div ref="surface" class="chart-surface" />
    <div v-if="!ready || message || !result?.rows.length" class="chart-state">
      <span>{{
        !ready ? chartHint(widget.type) : message || (loading ? '正在取数…' : '暂无数据')
      }}</span>
      <el-button v-if="message" link @pointerdown.stop @click.stop="load">重试</el-button>
    </div>
    <span v-if="result?.truncated" class="row-limit">仅显示前 {{ result.maxRows }} 行</span>
  </div>
</template>
<style scoped>
.chart-widget {
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
