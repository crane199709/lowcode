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
export interface BarConfig {
  datasetId: string
  query: WidgetQuery
  refreshSeconds: number
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
export function emptyBar(): BarConfig {
  return {
    datasetId: '',
    query: { dimensions: [], measures: [], filters: [], sorts: [] },
    refreshSeconds: 0,
  }
}
