import type { ChartConfig, DataWidgetType } from './widget-query'
export interface TextConfig {
  text: string
  fontSize: number
  color: string
  backgroundColor: string
  fontWeight: 'normal' | 'bold'
  textAlign: 'left' | 'center' | 'right'
}
export type ImageFill = 'contain' | 'cover' | 'fill'
export interface ImageConfig {
  objectId: string
  fill: ImageFill
}
interface WidgetLayout {
  id: string
  x: number
  y: number
  width: number
  height: number
  zIndex: number
}
export type ScreenWidget = WidgetLayout &
  (
    | { type: 'TEXT'; config: TextConfig }
    | { type: 'IMAGE'; config: ImageConfig }
    | { type: 'BAR' | 'LINE' | 'STACKED_BAR' | 'PIE'; config: ChartConfig }
    | { type: DataWidgetType; config: ChartConfig }
  )
export interface ScreenDraft {
  designWidth: number
  designHeight: number
  backgroundColor: string
  backgroundImageObjectId?: string | null
  backgroundImageFill?: ImageFill | null
  widgets: ScreenWidget[]
}
export interface Screen {
  id: string
  name: string
  draft?: ScreenDraft
  designWidth?: number
  designHeight?: number
  publishedAt?: string | null
  updatedAt: string | null
}
export const resolutions = ['1920×1080', '2560×1440', '3840×2160', '1366×768']
export function emptyDraft(): ScreenDraft {
  return { designWidth: 1920, designHeight: 1080, backgroundColor: '#101827', widgets: [] }
}
export function cloneDraft(draft: ScreenDraft): ScreenDraft {
  return JSON.parse(JSON.stringify(draft))
}

export interface PublishedScreen {
  id: string
  name: string
  definition: ScreenDraft
  publishedAt: string
}
