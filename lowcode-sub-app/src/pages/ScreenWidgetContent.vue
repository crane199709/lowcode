<script setup lang="ts">
import ScreenImage from './ScreenImage.vue'
import ScreenChart from './ScreenChart.vue'
import type { ScreenWidget } from './screen'
defineProps<{ widget: ScreenWidget; imageBase: string; queryBase?: string }>()
</script>
<template>
  <ScreenImage
    v-if="widget.type === 'IMAGE'"
    :object-id="widget.config.objectId"
    :fill="widget.config.fill"
    :image-base="imageBase"
    alt="图片组件"
  />
  <ScreenChart
    v-else-if="
      (widget.type === 'BAR' ||
        widget.type === 'LINE' ||
        widget.type === 'STACKED_BAR' ||
        widget.type === 'PIE') &&
      queryBase
    "
    :widget="widget"
    :query-base="queryBase"
  />
  <template v-else-if="widget.type === 'TEXT'">{{ widget.config.text }}</template>
</template>
