<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog } from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, overlaySize, type ErrorTip } from '@crane199709/ui'
import { errorTip } from './data-source'

const props = defineProps<{
  modelValue: boolean
  resource: { id: string; name: string } | null
  path: 'data-sources' | 'datasets'
  label: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; deleted: [] }>()
interface References {
  references: { id: string; name: string }[]
  hiddenReferences: boolean
}
const client = useTenantClient()
const references = ref<References>()
const loading = ref(false)
const saving = ref(false)
const error = ref<ErrorTip | null>(null)
const blocked = computed(
  () => references.value?.hiddenReferences || !!references.value?.references.length,
)
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  references.value = undefined
  try {
    const response = await client.api<References>(
      `/lowcode/${props.path}/${props.resource!.id}/references`,
    )
    if (current === generation) references.value = response
  } catch (cause) {
    if (current === generation) error.value = errorTip(cause, '引用清单加载失败，请重新打开后重试')
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(
  () => props.modelValue,
  (open) => {
    generation++
    if (open) {
      error.value = null
      void load()
    }
  },
)
async function remove() {
  if (!references.value || blocked.value || saving.value) return
  saving.value = true
  error.value = null
  try {
    await client.api<void>(`/lowcode/${props.path}/${props.resource!.id}`, { method: 'DELETE' })
    emit('update:modelValue', false)
    emit('deleted')
  } catch (cause) {
    error.value = errorTip(cause, '删除失败，请稍后重试')
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="`删除${label}`"
    :width="overlaySize.dialogMd"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p>确认删除「{{ resource?.name }}」？</p>
    <ErrorAlert v-if="error" :tip="error" />
    <p v-if="loading" role="status">正在检查引用…</p>
    <template v-else-if="references">
      <el-alert
        v-if="blocked && !error"
        title="正在被以下对象引用，解除引用后才能删除"
        type="warning"
        :closable="false"
      />
      <ul v-if="references.references.length" class="reference-list">
        <li v-for="reference in references.references" :key="reference.id">{{ reference.name }}</li>
      </ul>
      <p v-if="references.hiddenReferences">
        其他无权查看的大屏也在引用此数据集，请联系大屏设计者处理。
      </p>
      <p v-if="!blocked">当前没有引用，删除后将无法在列表中查看。</p>
    </template>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="danger"
        :disabled="loading || !references || blocked"
        :loading="saving"
        @click="remove"
        >删除</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.reference-list {
  max-height: calc(var(--forge-space-4) * 12);
  overflow: auto;
  padding-inline-start: var(--forge-space-4);
  overflow-wrap: anywhere;
}
.reference-list li + li {
  margin-top: var(--forge-space-2);
}
</style>
