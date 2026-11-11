import { ApiError } from '@crane199709/sdk'
import type { ErrorTip } from '@crane199709/ui'

export type DataSourceType = 'POSTGRESQL'

/** 接口不回显密码；编辑表单的密码框始终为空，留空表示不修改。 */
export interface DataSource {
  id: string
  name: string
  type: DataSourceType
  host: string
  port: number
  database: string
  username: string
  params: Record<string, string>
  createdAt: string
  updatedAt: string
}

export interface DataSourceInput {
  name: string
  type: DataSourceType
  host: string
  port: number
  database: string
  username: string
  password: string
  params: Record<string, string>
}

export const typeLabels: Record<DataSourceType, string> = { POSTGRESQL: 'PostgreSQL' }

/** 失败提示优先用服务端按错误码翻译好的 userTip。 */
export function errorTip(cause: unknown, fallback: string): ErrorTip {
  return {
    message: cause instanceof ApiError && cause.userTip ? cause.userTip : fallback,
    traceId: cause instanceof ApiError ? cause.traceId : undefined,
  }
}

export function jsonBody(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}
