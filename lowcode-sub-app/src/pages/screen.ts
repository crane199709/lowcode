export interface TextConfig {
  text: string
  fontSize: number
  color: string
  backgroundColor: string
  fontWeight: 'normal' | 'bold'
  textAlign: 'left' | 'center' | 'right'
}
export interface ScreenWidget {
  id: string
  type: 'TEXT'
  x: number
  y: number
  width: number
  height: number
  zIndex: number
  config: TextConfig
}
export interface ScreenDraft {
  designWidth: number
  designHeight: number
  backgroundColor: string
  widgets: ScreenWidget[]
}
export interface Screen {
  id: string
  name: string
  draft: ScreenDraft
  updatedAt: string | null
}
export const resolutions = ['1920×1080', '2560×1440', '3840×2160', '1366×768']
export function emptyDraft(): ScreenDraft {
  return { designWidth: 1920, designHeight: 1080, backgroundColor: '#101827', widgets: [] }
}
export function cloneDraft(draft: ScreenDraft): ScreenDraft {
  return JSON.parse(JSON.stringify(draft))
}
