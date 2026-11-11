<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ElDescriptions, ElDescriptionsItem, ElDrawer } from 'element-plus'
import { overlaySize } from '@crane199709/ui'
import DataSourceSchemaBrowser from './DataSourceSchemaBrowser.vue'
import { sourceAddress, typeLabels, type DataSource } from './data-source'

defineProps<{ modelValue: boolean; source: DataSource | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '数据源详情',
      name: '名称',
      type: '类型',
      address: '连接地址',
      username: '账号',
    },
  },
})
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('title')"
    :size="overlaySize.drawerLg"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="source">
      <el-descriptions :column="1" class="details">
        <el-descriptions-item :label="t('name')">{{ source.name }}</el-descriptions-item>
        <el-descriptions-item :label="t('type')">{{
          typeLabels[source.type]
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('address')">{{
          sourceAddress(source) || '—'
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('username')">{{
          source.username || '—'
        }}</el-descriptions-item>
      </el-descriptions>
      <DataSourceSchemaBrowser v-if="modelValue && source.type !== 'API'" :source-id="source.id" />
    </template>
  </el-drawer>
</template>

<style scoped>
.details {
  margin-bottom: var(--forge-space-5);
  overflow-wrap: anywhere;
}
</style>
