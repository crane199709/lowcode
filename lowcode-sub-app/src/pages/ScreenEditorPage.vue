<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElCheckbox,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus'
import { useAccessControl, useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, PageToolbar, type ErrorTip } from '@crane199709/ui'
import { errorTip, jsonBody } from './data-source'
import { cloneDraft, emptyDraft, resolutions, type ScreenDraft, type ScreenWidget } from './screen'

const route = useRoute()
const router = useRouter()
const client = useTenantClient()
const access = useAccessControl()
const canEdit = computed(() => access.has('screen:edit'))
const draft = ref<ScreenDraft>(emptyDraft())
const loaded = ref(false)
const saving = ref(false)
const error = ref<ErrorTip | null>(null)
const saved = ref(false)
const selectedId = ref<string | null>(null)
const selected = computed(() =>
  draft.value.widgets.find((widget) => widget.id === selectedId.value),
)
const history = ref<ScreenDraft[]>([])
const cursor = ref(0)
const grid = ref(true)
const viewport = ref<HTMLElement>()
const scale = ref(1)
const preset = ref('1920×1080')
let observer: ResizeObserver | undefined
let activeGesture: {
  id: string
  pointerId: number
  startX: number
  startY: number
  widget: ScreenWidget
  resize: boolean
} | null = null
function fit() {
  if (viewport.value)
    scale.value = Math.min(
      1,
      viewport.value.clientWidth / draft.value.designWidth,
      viewport.value.clientHeight / draft.value.designHeight,
    )
}
function commit() {
  const snapshot = cloneDraft(draft.value)
  if (JSON.stringify(history.value[cursor.value]) === JSON.stringify(snapshot)) return
  history.value = [...history.value.slice(0, cursor.value + 1), snapshot].slice(-100)
  cursor.value = history.value.length - 1
  saved.value = false
  fit()
}
function restore(offset: number) {
  cursor.value += offset
  draft.value = cloneDraft(history.value[cursor.value]!)
  selectedId.value = selected.value?.id ?? null
  preset.value = resolutions.includes(`${draft.value.designWidth}×${draft.value.designHeight}`)
    ? `${draft.value.designWidth}×${draft.value.designHeight}`
    : '自定义'
  saved.value = false
  fit()
}
function resolution(value: string) {
  if (value === '自定义') return
  const [width, height] = value.split('×').map(Number)
  draft.value.designWidth = width!
  draft.value.designHeight = height!
  commit()
}
function addText() {
  if (draft.value.widgets.length >= 200) return
  const widget: ScreenWidget = {
    id: crypto.randomUUID(),
    type: 'TEXT',
    x: 80,
    y: 80,
    width: 320,
    height: 100,
    zIndex: draft.value.widgets.length,
    config: {
      text: '文本卡',
      fontSize: 32,
      color: '#ffffff',
      backgroundColor: '#18243a',
      fontWeight: 'normal',
      textAlign: 'left',
    },
  }
  draft.value.widgets.push(widget)
  selectedId.value = widget.id
  commit()
}
function normalizeLayers() {
  draft.value.widgets
    .sort((a, b) => a.zIndex - b.zIndex)
    .forEach((widget, index) => {
      widget.zIndex = index
    })
}
function copyWidget() {
  if (!selected.value || draft.value.widgets.length >= 200) return
  const widget = JSON.parse(JSON.stringify(selected.value)) as ScreenWidget
  widget.id = crypto.randomUUID()
  widget.x = Math.min(7680, widget.x + 20)
  widget.y = Math.min(7680, widget.y + 20)
  widget.zIndex = draft.value.widgets.length
  draft.value.widgets.push(widget)
  selectedId.value = widget.id
  commit()
}
function removeWidget() {
  draft.value.widgets = draft.value.widgets.filter((widget) => widget.id !== selectedId.value)
  selectedId.value = null
  normalizeLayers()
  commit()
}
function layer(direction: number) {
  if (!selected.value) return
  normalizeLayers()
  const index = draft.value.widgets.findIndex((widget) => widget.id === selectedId.value)
  const other = draft.value.widgets[index + direction]
  if (!other) return
  other.zIndex = index
  selected.value.zIndex = index + direction
  normalizeLayers()
  commit()
}
function gesture(event: PointerEvent, widget: ScreenWidget, resize = false) {
  if (event.button !== 0) return
  event.preventDefault()
  selectedId.value = widget.id
  activeGesture = {
    id: widget.id,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    widget: JSON.parse(JSON.stringify(widget)),
    resize,
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
function move(event: PointerEvent) {
  if (!activeGesture || event.pointerId !== activeGesture.pointerId) return
  const widget = draft.value.widgets.find((item) => item.id === activeGesture!.id)
  if (!widget) return
  const dx = (event.clientX - activeGesture.startX) / scale.value
  const dy = (event.clientY - activeGesture.startY) / scale.value
  const snap = (value: number, min: number) =>
    Math.min(7680, Math.max(min, Math.round(value / (grid.value ? 10 : 1)) * (grid.value ? 10 : 1)))
  if (activeGesture.resize) {
    widget.width = snap(activeGesture.widget.width + dx, 20)
    widget.height = snap(activeGesture.widget.height + dy, 20)
  } else {
    widget.x = snap(activeGesture.widget.x + dx, 0)
    widget.y = snap(activeGesture.widget.y + dy, 0)
  }
}
function finish() {
  if (!activeGesture) return
  activeGesture = null
  commit()
}
function widgetStyle(widget: ScreenWidget) {
  return {
    left: `${widget.x}px`,
    top: `${widget.y}px`,
    width: `${widget.width}px`,
    height: `${widget.height}px`,
    zIndex: widget.zIndex,
    color: widget.config.color,
    backgroundColor: widget.config.backgroundColor,
    fontSize: `${widget.config.fontSize}px`,
    fontWeight: widget.config.fontWeight,
    textAlign: widget.config.textAlign,
  }
}
async function load() {
  error.value = null
  try {
    draft.value = await client.api<ScreenDraft>(`/lowcode/screens/${route.params.id}/draft`)
    history.value = [cloneDraft(draft.value)]
    cursor.value = 0
    preset.value = resolutions.includes(`${draft.value.designWidth}×${draft.value.designHeight}`)
      ? `${draft.value.designWidth}×${draft.value.designHeight}`
      : '自定义'
    loaded.value = true
    await nextTick()
    observer = new ResizeObserver(fit)
    if (viewport.value) observer.observe(viewport.value)
    fit()
  } catch (cause) {
    error.value = errorTip(cause, '草稿加载失败，请重试')
  }
}
async function save() {
  saving.value = true
  error.value = null
  try {
    const snapshot = cloneDraft(draft.value)
    await client.api<ScreenDraft>(
      `/lowcode/screens/${route.params.id}/draft`,
      jsonBody('PUT', snapshot),
    )
    saved.value = JSON.stringify(draft.value) === JSON.stringify(snapshot)
  } catch (cause) {
    error.value = errorTip(cause, '草稿保存失败，请重试')
  } finally {
    saving.value = false
  }
}
onMounted(() => {
  if (canEdit.value) load()
})
onBeforeUnmount(() => observer?.disconnect())
</script>
<template>
  <section aria-label="大屏编辑器">
    <PageHeader title="大屏编辑器"
      ><template #actions
        ><el-button @click="router.push({ name: 'lowcode:screens' })">返回列表</el-button></template
      ></PageHeader
    >
    <el-empty v-if="!canEdit" description="没有编辑大屏的权限" />
    <template v-else>
      <ErrorAlert v-if="error" :tip="error" />
      <el-button v-if="!loaded && error" @click="load">重试</el-button>
      <template v-if="loaded">
        <PageToolbar>
          <div class="editor-actions">
            <el-button :disabled="draft.widgets.length >= 200" @click="addText"
              >添加文本卡</el-button
            >
            <el-button :disabled="cursor === 0" @click="restore(-1)">撤销</el-button>
            <el-button :disabled="cursor === history.length - 1" @click="restore(1)"
              >重做</el-button
            >
            <el-checkbox v-model="grid">网格吸附（10 px）</el-checkbox>
          </div>
          <template #actions
            ><el-tag v-if="saved" type="success">草稿已保存</el-tag
            ><el-button type="primary" :loading="saving" @click="save"
              >保存草稿</el-button
            ></template
          >
        </PageToolbar>
        <div class="editor-layout">
          <div class="canvas-panel">
            <div class="canvas-meta">
              <span>{{ draft.designWidth }}×{{ draft.designHeight }}</span
              ><span>{{ Math.round(scale * 100) }}% · {{ draft.widgets.length }} 个组件</span>
            </div>
            <div ref="viewport" class="canvas-viewport">
              <div
                class="canvas-frame"
                :style="{
                  width: `${draft.designWidth * scale}px`,
                  height: `${draft.designHeight * scale}px`,
                }"
              >
                <div
                  class="screen-canvas"
                  role="region"
                  aria-label="大屏画布"
                  :style="{
                    width: `${draft.designWidth}px`,
                    height: `${draft.designHeight}px`,
                    backgroundColor: draft.backgroundColor,
                    transform: `scale(${scale})`,
                  }"
                  @pointerdown.self="selectedId = null"
                >
                  <div
                    v-for="widget in draft.widgets"
                    :key="widget.id"
                    class="screen-widget"
                    :class="{ selected: selectedId === widget.id }"
                    data-testid="screen-widget"
                    :style="widgetStyle(widget)"
                    @pointerdown.stop="gesture($event, widget)"
                    @pointermove="move"
                    @pointerup="finish"
                    @pointercancel="finish"
                    @lostpointercapture="finish"
                  >
                    {{ widget.config.text }}
                    <span
                      v-if="selectedId === widget.id"
                      class="resize-handle"
                      data-testid="widget-resize"
                      @pointerdown.stop="gesture($event, widget, true)"
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>
          <aside class="properties" aria-label="属性面板">
            <h3>画布</h3>
            <el-form label-position="top" size="small" @submit.prevent>
              <el-form-item label="设计分辨率"
                ><el-select v-model="preset" aria-label="设计分辨率" @change="resolution"
                  ><el-option
                    v-for="value in [...resolutions, '自定义']"
                    :key="value"
                    :label="value"
                    :value="value" /></el-select
              ></el-form-item>
              <div v-if="preset === '自定义'" class="property-pair">
                <el-form-item label="宽度"
                  ><el-input-number
                    v-model="draft.designWidth"
                    aria-label="画布宽度"
                    :min="640"
                    :max="7680"
                    :precision="0"
                    :controls="false"
                    @change="commit"
                /></el-form-item>
                <el-form-item label="高度"
                  ><el-input-number
                    v-model="draft.designHeight"
                    aria-label="画布高度"
                    :min="640"
                    :max="7680"
                    :precision="0"
                    :controls="false"
                    @change="commit"
                /></el-form-item>
              </div>
              <el-form-item label="背景色"
                ><input
                  v-model="draft.backgroundColor"
                  aria-label="画布背景色"
                  type="color"
                  @change="commit"
              /></el-form-item>
              <template v-if="selected">
                <h3>文本卡</h3>
                <el-form-item label="文字"
                  ><el-input
                    v-model="selected.config.text"
                    aria-label="文字内容"
                    type="textarea"
                    :rows="2"
                    maxlength="20000"
                    @change="commit"
                /></el-form-item>
                <div class="property-pair">
                  <el-form-item label="X"
                    ><el-input-number
                      v-model="selected.x"
                      aria-label="X 坐标"
                      :min="0"
                      :max="7680"
                      :precision="0"
                      :controls="false"
                      @change="commit" /></el-form-item
                  ><el-form-item label="Y"
                    ><el-input-number
                      v-model="selected.y"
                      aria-label="Y 坐标"
                      :min="0"
                      :max="7680"
                      :precision="0"
                      :controls="false"
                      @change="commit"
                  /></el-form-item>
                </div>
                <div class="property-pair">
                  <el-form-item label="宽度"
                    ><el-input-number
                      v-model="selected.width"
                      aria-label="组件宽度"
                      :min="1"
                      :max="7680"
                      :precision="0"
                      :controls="false"
                      @change="commit" /></el-form-item
                  ><el-form-item label="高度"
                    ><el-input-number
                      v-model="selected.height"
                      aria-label="组件高度"
                      :min="1"
                      :max="7680"
                      :precision="0"
                      :controls="false"
                      @change="commit"
                  /></el-form-item>
                </div>
                <el-form-item label="字号"
                  ><el-input-number
                    v-model="selected.config.fontSize"
                    aria-label="字号"
                    :min="8"
                    :max="256"
                    :precision="0"
                    @change="commit"
                /></el-form-item>
                <div class="property-pair">
                  <el-form-item label="文字颜色"
                    ><input
                      v-model="selected.config.color"
                      aria-label="文字颜色"
                      type="color"
                      @change="commit" /></el-form-item
                  ><el-form-item label="填充色"
                    ><input
                      v-model="selected.config.backgroundColor"
                      aria-label="组件填充色"
                      type="color"
                      @change="commit"
                  /></el-form-item>
                </div>
                <el-form-item label="字重"
                  ><el-select
                    v-model="selected.config.fontWeight"
                    aria-label="字重"
                    @change="commit"
                    ><el-option label="常规" value="normal" /><el-option
                      label="加粗"
                      value="bold" /></el-select
                ></el-form-item>
                <el-form-item label="对齐"
                  ><el-select
                    v-model="selected.config.textAlign"
                    aria-label="文字对齐"
                    @change="commit"
                    ><el-option label="左对齐" value="left" /><el-option
                      label="居中"
                      value="center" /><el-option label="右对齐" value="right" /></el-select
                ></el-form-item>
                <div class="widget-actions">
                  <el-button @click="layer(1)">上移一层</el-button
                  ><el-button @click="layer(-1)">下移一层</el-button
                  ><el-button @click="copyWidget">复制组件</el-button
                  ><el-button type="danger" plain @click="removeWidget">删除组件</el-button>
                </div>
              </template>
            </el-form>
          </aside>
        </div>
      </template>
    </template>
  </section>
</template>
<style scoped>
.editor-actions {
  display: flex;
  align-items: center;
  gap: var(--forge-space-2);
  flex-wrap: wrap;
}
.editor-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) calc(var(--forge-space-5) * 10);
  gap: var(--forge-space-4);
  height: min(calc(var(--forge-space-5) * 27), calc(100dvh - var(--forge-space-5) * 7));
}
.canvas-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--el-fill-color-light);
  border-radius: var(--forge-radius-md);
  padding: var(--forge-space-4);
}
.canvas-meta {
  display: flex;
  justify-content: space-between;
  gap: var(--forge-space-3);
  margin-bottom: var(--forge-space-3);
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-sm);
}
.canvas-viewport {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.canvas-frame {
  position: relative;
  flex-shrink: 0;
}
.screen-canvas {
  position: absolute;
  transform-origin: top left;
  overflow: hidden;
}
.screen-widget {
  position: absolute;
  box-sizing: border-box;
  cursor: move;
  white-space: pre-wrap;
  overflow: hidden;
  overflow-wrap: anywhere;
  user-select: none;
  touch-action: none;
}
.screen-widget.selected {
  outline: 2px solid var(--el-color-primary);
}
.resize-handle {
  position: absolute;
  right: 0;
  bottom: 0;
  width: var(--forge-space-4);
  height: var(--forge-space-4);
  background: var(--el-color-primary);
  cursor: nwse-resize;
}
.properties {
  overflow-y: auto;
  padding-right: var(--forge-space-2);
}
.properties h3 {
  margin: 0 0 var(--forge-space-3);
  font-size: var(--forge-font-size-md);
}
.property-pair {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--forge-space-3);
}
.property-pair :deep(.el-input-number) {
  width: 100%;
}
.widget-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--forge-space-2);
}
.widget-actions :deep(.el-button) {
  margin: 0;
}
</style>
