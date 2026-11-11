import { ApiError } from '@crane199709/sdk'
import type { ErrorTip } from '@crane199709/ui'

export type DatabaseSourceType = 'POSTGRESQL' | 'MYSQL' | 'SQLSERVER' | 'ORACLE'
export type DataSourceType = DatabaseSourceType | 'API'
export type ApiMethod = 'GET' | 'POST'
export type ApiAuthType = 'NONE' | 'HEADER' | 'BASIC'

/** 接口不回显密码与请求头值；编辑表单的对应输入框始终为空，留空表示不修改。 */
export interface ApiConnection {
  url: string
  method: ApiMethod
  body: string
  authType: ApiAuthType
  headerName: string
}

export interface DataSource {
  id: string
  name: string
  type: DataSourceType
  host: string | null
  port: number | null
  database: string | null
  username: string | null
  params: Record<string, string> | null
  api: ApiConnection | null
  createdAt: string
  updatedAt: string
}

export interface SourceTable {
  catalog: string
  schema: string
  name: string
}

export interface SourceColumn {
  name: string
  type: string
}

export interface DatabaseConnectionInput {
  host: string
  port: number
  database: string
  username: string
  password: string
  params: Record<string, string>
}

export interface ApiConnectionInput {
  url: string
  method: ApiMethod
  body: string
  authType: ApiAuthType
  headerName: string
  headerValue: string
  username: string
  password: string
}

/** 连接配置按类型放在 database 或 api 中，另一项不出现；后端据此做字段级校验。 */
export interface DataSourceInput {
  name: string
  type: DataSourceType
  database?: DatabaseConnectionInput
  api?: ApiConnectionInput
}

export const typeLabels: Record<DataSourceType, string> = {
  POSTGRESQL: 'PostgreSQL',
  MYSQL: 'MySQL',
  SQLSERVER: 'SQL Server',
  ORACLE: 'Oracle',
  API: 'API',
}

export const dataSourceTypes = Object.keys(typeLabels) as DataSourceType[]

export const authTypeLabels: Record<ApiAuthType, string> = {
  NONE: '无认证',
  HEADER: '自定义请求头',
  BASIC: 'Basic',
}

/** 各数据库类型的默认端口与连接参数；参数名与取值须在后端 `DataSourceType` 的白名单内，否则保存会被拒绝。 */
export const typeDefaults: Record<
  DatabaseSourceType,
  { port: number; params: Record<string, string> }
> = {
  POSTGRESQL: { port: 5432, params: { sslmode: 'prefer' } },
  MYSQL: { port: 3306, params: { sslMode: 'PREFERRED', allowPublicKeyRetrieval: 'false' } },
  SQLSERVER: { port: 1433, params: { encrypt: 'true', trustServerCertificate: 'false' } },
  ORACLE: { port: 1521, params: {} },
}

export function isDatabaseType(type: DataSourceType): type is DatabaseSourceType {
  return type !== 'API'
}

/** 列表中的连接地址：数据库显示主机、端口与库名，API 显示请求地址。 */
export function sourceAddress(source: DataSource): string {
  if (source.type === 'API') {
    return source.api?.url ?? ''
  }
  return source.host ? `${source.host}:${source.port}/${source.database}` : ''
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
