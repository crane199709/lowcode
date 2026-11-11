<script setup lang="ts">
import ScreenImage from './ScreenImage.vue'
import ScreenWidgetContent from './ScreenWidgetContent.vue'
import { widgetStyle, type ScreenDraft } from './screen'
defineProps<{ draft: ScreenDraft; imageBase: string; queryBase?: string }>()
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
      :style="{ position: 'absolute', ...widgetStyle(widget) }"
    >
      <ScreenWidgetContent :widget="widget" :image-base="imageBase" :query-base="queryBase" />
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
