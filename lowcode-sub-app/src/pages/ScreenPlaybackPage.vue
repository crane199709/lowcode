<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElOption, ElSelect } from 'element-plus'
import { useAccessControl, useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, PageToolbar, type ErrorTip } from '@crane199709/ui'
import { errorTip } from './data-source'
import type { PublishedScreen, ScreenWidget } from './screen'

const route = useRoute()
const router = useRouter()
const client = useTenantClient()
const access = useAccessControl()
const screen = ref<PublishedScreen | null>(null)
const error = ref<ErrorTip | null>(null)
const root = ref<HTMLElement>()
const viewport = ref<HTMLElement>()
const mode = ref<'fit' | 'stretch' | 'width'>('fit')
const size = ref({ width: 0, height: 0 })
const fullscreen = ref(false)
let observer: ResizeObserver | undefined
const scale = computed(() => {
  if (!screen.value) return { x: 1, y: 1 }
  const x = size.value.width / screen.value.definition.designWidth
  const y = size.value.height / screen.value.definition.designHeight
  return mode.value === 'stretch'
    ? { x, y }
    : {
        x: mode.value === 'width' ? x : Math.min(x, y),
        y: mode.value === 'width' ? x : Math.min(x, y),
      }
})
const stageStyle = computed(() =>
  screen.value
    ? {
        width: `${screen.value.definition.designWidth}px`,
        height: `${screen.value.definition.designHeight}px`,
        transform: `scale(${scale.value.x}, ${scale.value.y})`,
        backgroundColor: screen.value.definition.backgroundColor,
      }
    : {},
)
const extentStyle = computed(() =>
  screen.value
    ? {
        width: `${screen.value.definition.designWidth * scale.value.x}px`,
        height: `${screen.value.definition.designHeight * scale.value.y}px`,
      }
    : {},
)
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
function measure() {
  if (viewport.value)
    size.value = { width: viewport.value.clientWidth, height: viewport.value.clientHeight }
}
function fullscreenChanged() {
  fullscreen.value = document.fullscreenElement === root.value
  nextTick(measure)
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement === root.value) await document.exitFullscreen()
    else await root.value?.requestFullscreen()
  } catch (cause) {
    error.value = errorTip(cause, '无法进入全屏，请检查浏览器权限')
  }
}
onMounted(async () => {
  document.addEventListener('fullscreenchange', fullscreenChanged)
  if (!access.has('screen:view')) {
    error.value = { message: '没有查看大屏的权限' }
    return
  }
  try {
    screen.value = await client.api<PublishedScreen>(
      `/lowcode/screens/${route.params.id}/published`,
    )
    await nextTick()
    measure()
    observer = new ResizeObserver(measure)
    if (viewport.value) observer.observe(viewport.value)
  } catch (cause) {
    error.value = errorTip(cause, '大屏未发布或不在你的数据范围内')
  }
})
onBeforeUnmount(() => {
  observer?.disconnect()
  document.removeEventListener('fullscreenchange', fullscreenChanged)
  if (document.fullscreenElement === root.value) void document.exitFullscreen()
})
</script>
<template>
  <section ref="root" class="playback" aria-label="已发布大屏">
    <PageHeader :title="screen?.name ?? '大屏播放'"
      ><template #actions
        ><el-button @click="router.push({ name: 'lowcode:screens' })">返回列表</el-button></template
      ></PageHeader
    >
    <ErrorAlert v-if="error" :tip="error" />
    <template v-if="screen">
      <PageToolbar
        ><template #default
          ><el-select v-model="mode" aria-label="缩放方式" class="scale-select"
            ><el-option label="等比适配" value="fit" /><el-option
              label="铺满拉伸"
              value="stretch" /><el-option label="按宽度适配" value="width" /></el-select></template
        ><template #actions
          ><el-button type="primary" @click="toggleFullscreen">{{
            fullscreen ? '退出全屏' : '全屏'
          }}</el-button></template
        ></PageToolbar
      >
      <div
        ref="viewport"
        class="viewport"
        :class="{ 'width-mode': mode === 'width' }"
        role="region"
        aria-label="播放画布"
      >
        <div class="extent" :style="extentStyle">
          <div class="stage" data-testid="playback-stage" :style="stageStyle">
            <div
              v-for="widget in screen.definition.widgets"
              :key="widget.id"
              class="widget"
              :style="widgetStyle(widget)"
            >
              {{ widget.config.text }}
            </div>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
<style scoped>
.playback {
  display: flex;
  flex-direction: column;
  height: calc(100dvh - var(--forge-space-5) * 5);
  min-height: 0;
  overflow: hidden;
  background: var(--el-bg-color);
}
.playback:fullscreen {
  height: 100dvh;
  padding: var(--forge-space-4);
  box-sizing: border-box;
}
.scale-select {
  width: calc(var(--forge-space-4) * 10);
}
.viewport {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;
  background: var(--el-fill-color-darker);
}
.viewport.width-mode {
  align-items: flex-start;
  overflow-y: auto;
}
.extent {
  position: relative;
  flex-shrink: 0;
}
.stage {
  position: absolute;
  transform-origin: top left;
  overflow: hidden;
}
.widget {
  position: absolute;
  overflow: hidden;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
