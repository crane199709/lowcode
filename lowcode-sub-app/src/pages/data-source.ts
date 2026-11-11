import { ApiError } from '@crane199709/sdk'
import type { ErrorTip } from '@crane199709/ui'

export type DataSourceType = 'POSTGRESQL' | 'MYSQL' | 'SQLSERVER' | 'ORACLE'

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

export const typeLabels: Record<DataSourceType, string> = {
  POSTGRESQL: 'PostgreSQL',
  MYSQL: 'MySQL',
  SQLSERVER: 'SQL Server',
  ORACLE: 'Oracle',
}

export const dataSourceTypes = Object.keys(typeLabels) as DataSourceType[]

/** 各类型的默认端口与连接参数；参数名与取值须在后端 `DataSourceType` 的白名单内，否则保存会被拒绝。 */
export const typeDefaults: Record<DataSourceType, { port: number; params: Record<string, string> }> = {
  POSTGRESQL: { port: 5432, params: { sslmode: 'prefer' } },
  MYSQL: { port: 3306, params: { sslMode: 'PREFERRED', allowPublicKeyRetrieval: 'false' } },
  SQLSERVER: { port: 1433, params: { encrypt: 'true', trustServerCertificate: 'false' } },
  ORACLE: { port: 1521, params: {} },
}

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
