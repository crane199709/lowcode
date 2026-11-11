import type { DatasetField } from './dataset'
export type Aggregation = 'SUM' | 'AVG' | 'COUNT' | 'COUNT_DISTINCT' | 'MAX' | 'MIN'
export type DateGrouping = 'DAY' | 'MONTH' | 'YEAR'
export interface WidgetQuery {
  dimensions: { field: string; dateGrouping?: DateGrouping }[]
  measures: { field: string; aggregation: Aggregation }[]
  filters: {
    field: string
    operator: 'EQ' | 'IN' | 'BETWEEN'
    values: (string | number | boolean | null)[]
  }[]
  sorts: { column: number; direction: 'ASC' | 'DESC' }[]
  topN?: number | null
}
export type ChartType = 'BAR' | 'LINE' | 'STACKED_BAR' | 'PIE'
export const chartNames: Record<ChartType, string> = {
  BAR: '基础柱状图',
  LINE: '基础折线图',
  STACKED_BAR: '堆叠柱状图',
  PIE: '饼图',
}
export interface ChartConfig {
  datasetId: string
  query: WidgetQuery
  refreshSeconds: number
  donut?: boolean
}
export interface BindingDataset {
  id: string
  name: string
  fields: DatasetField[]
}
export interface WidgetData {
  columns: { id: string; label: string; type: string }[]
  rows: (string | number | boolean | null)[][]
  truncated: boolean
  maxRows: number
}
export const aggregations: Record<Aggregation, string> = {
  SUM: '求和',
  AVG: '平均',
  COUNT: '计数',
  COUNT_DISTINCT: '去重计数',
  MAX: '最大',
  MIN: '最小',
}
export function emptyChart(type: ChartType = 'BAR'): ChartConfig {
  return {
    ...(type === 'PIE' ? { donut: false } : {}),
    datasetId: '',
    query: { dimensions: [], measures: [], filters: [], sorts: [] },
    refreshSeconds: 0,
  }
}

export function chartHint(type: ChartType) {
  return type === 'PIE'
    ? '需要 1 个维度和 1 个度量'
    : type === 'STACKED_BAR'
      ? '需要 2 个维度（类别、堆叠）和 1 个度量'
      : type === 'LINE'
        ? '需要 1 个维度和 1–16 个度量'
        : '需要 1–8 个维度和 1–16 个度量'
}
export function dimensionLimit(type: ChartType) {
  return type === 'STACKED_BAR' ? 2 : type === 'LINE' || type === 'PIE' ? 1 : 8
}
export function measureLimit(type: ChartType) {
  return type === 'STACKED_BAR' || type === 'PIE' ? 1 : 16
}
export function chartReady(type: ChartType, config: ChartConfig) {
  const dimensions = config.query?.dimensions?.length ?? 0
  const measures = config.query?.measures?.length ?? 0
  return (
    !!config.datasetId &&
    dimensions >= (type === 'STACKED_BAR' ? 2 : 1) &&
    dimensions <= dimensionLimit(type) &&
    measures >= 1 &&
    measures <= measureLimit(type)
  )
}
