<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ElTableColumn } from 'element-plus'
import type { Page } from '@crane199709/sdk'
import { ListPage, PageHeader, PageToolbar } from '@crane199709/ui'

// 定义与维护数据集由后续工单接入接口，当前只搭好列表原型与空状态。
const empty: Page<never> = { items: [], page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } }
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': { title: '数据集', name: '名称', updatedAt: '更新时间', empty: '暂无数据集' },
  },
})
</script>

<template>
  <section :aria-label="t('title')">
    <PageHeader :title="t('title')" />
    <PageToolbar />
    <ListPage :page="empty" :empty-text="t('empty')">
      <el-table-column :label="t('name')" min-width="240" />
      <el-table-column :label="t('updatedAt')" width="180" />
    </ListPage>
  </section>
</template>
