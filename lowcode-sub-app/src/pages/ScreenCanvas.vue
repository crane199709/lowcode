<script setup lang="ts">
import ScreenImage from './ScreenImage.vue'
import ScreenWidgetContent from './ScreenWidgetContent.vue'
import type { ScreenDraft, ScreenWidget } from './screen'
defineProps<{ draft: ScreenDraft; imageBase: string }>()
function style(widget: ScreenWidget) {
  return {
    position: 'absolute' as const,
    left: `${widget.x}px`,
    top: `${widget.y}px`,
    width: `${widget.width}px`,
    height: `${widget.height}px`,
    zIndex: widget.zIndex,
    ...(widget.type === 'TEXT'
      ? {
          color: widget.config.color,
          backgroundColor: widget.config.backgroundColor,
          fontSize: `${widget.config.fontSize}px`,
          fontWeight: widget.config.fontWeight,
          textAlign: widget.config.textAlign,
        }
      : {}),
  }
}
</script>
<template>
  <div
    class="screen-canvas"
    :style="{
      width: `${draft.designWidth}px`,
      height: `${draft.designHeight}px`,
      backgroundColor: draft.backgroundColor,
    }"
  >
    <ScreenImage
      v-if="draft.backgroundImageObjectId"
      class="background-image"
      :object-id="draft.backgroundImageObjectId"
      :fill="draft.backgroundImageFill"
      :image-base="imageBase"
      alt="大屏背景图"
    />
    <div
      v-for="widget in draft.widgets"
      :key="widget.id"
      class="screen-widget"
      data-testid="playback-widget"
      :style="style(widget)"
    >
      <ScreenWidgetContent :widget="widget" :image-base="imageBase" />
    </div>
  </div>
</template>
<style scoped>
.screen-canvas {
  position: relative;
  overflow: hidden;
}
.background-image {
  position: absolute;
  inset: 0;
}
.screen-widget {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  overflow: hidden;
}
</style>
