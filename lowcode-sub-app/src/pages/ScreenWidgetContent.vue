<script setup lang="ts">
import ScreenImage from './ScreenImage.vue'
import type { ScreenWidget } from './screen'
defineProps<{ widget: ScreenWidget; imageBase: string }>()
</script>
<template>
  <ScreenImage
    v-if="widget.type === 'IMAGE'"
    :object-id="widget.config.objectId"
    :fill="widget.config.fill"
    :image-base="imageBase"
    alt="图片组件"
  />
  <template v-else>{{ widget.config.text }}</template>
</template>
