export interface DatasetField {
  name: string
  displayName: string
  type: 'NUMBER' | 'STRING' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'OTHER'
  role: 'DIMENSION' | 'MEASURE'
  date: boolean
}
export interface Dataset {
  id: string
  name: string
  dataSourceId: string
  dataSourceName: string | null
  jsonPath?: string | null
  sql: string
  fields: DatasetField[]
  createdAt: string
  updatedAt: string
}
export interface DatasetPreview {
  fields: DatasetField[]
  rows: (string | number | boolean | null)[][]
  truncated: boolean
  maxRows: number
}
export interface DatasetSource {
  id: string
  name: string
  type: string
}
