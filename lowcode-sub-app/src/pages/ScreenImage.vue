<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useTenantClient } from '@crane199709/sdk'
import type { ImageFill } from './screen'
const props = defineProps<{
  objectId: string
  imageBase: string
  fill?: ImageFill | null
  alt: string
}>()
const client = useTenantClient()
const url = ref('')
const failed = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
async function load() {
  const current = ++generation
  clearTimeout(timer)
  try {
    const ticket = await client.api<{ url: string; expiresAt: string }>(
      `${props.imageBase}/${props.objectId}/download`,
    )
    if (current !== generation) return
    url.value = ticket.url
    failed.value = false
    const remaining = Date.parse(ticket.expiresAt) - Date.now()
    // 以服务器票据为准续签，既支持长时间播放，也容忍短时链接与网络重试。
    timer = setTimeout(load, Math.max(1000, remaining - Math.min(30000, remaining * 0.1)))
  } catch {
    if (current !== generation) return
    failed.value = true
    timer = setTimeout(load, 15000)
  }
}
watch(
  () => [props.objectId, props.imageBase],
  () => {
    url.value = ''
    failed.value = false
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  ++generation
  clearTimeout(timer)
})
</script>
<template>
  <div class="image-surface">
    <img
      v-if="url"
      :src="url"
      :alt="alt"
      draggable="false"
      :style="{ objectFit: fill ?? 'cover' }"
      @error="failed = true"
    />
    <button v-if="failed" class="image-error" type="button" @pointerdown.stop @click.stop="load">
      图片加载失败，重试
    </button>
  </div>
</template>
<style scoped>
.image-surface {
  width: 100%;
  height: 100%;
  position: relative;
}
img {
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.image-error {
  position: absolute;
  inset: 0;
  border: 0;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-sm);
  cursor: pointer;
}
</style>
