<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElOption, ElSelect, ElTableColumn } from 'element-plus'
import { ApiError, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ListPage,
  PageHeader,
  PageToolbar,
  formatDateTime,
  type ErrorTip,
} from '@crane199709/ui'

interface Order {
  id: string
  title: string
  amount: number
  createdAt: string
}

const client = useTenantClient()
const orders = ref<Page<Order> | null>(null)
// 选项取自 archetype 订单接口 @SortableFields 声明的可排序字段；新增筛选前先让后端接口支持对应参数。
const sorts = [
  { value: 'createdAt,desc', label: 'newest' },
  { value: 'amount,desc', label: 'amountDesc' },
] as const
const sort = ref<string>(sorts[0].value)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '订单',
      sort: '排序方式',
      newest: '最新创建',
      amountDesc: '金额从高到低',
      name: '标题',
      amount: '金额',
      createdAt: '创建时间',
      empty: '暂无订单',
      failed: '订单加载失败，请稍后重试',
    },
  },
})
const money = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function load(page = orders.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    const query = new URLSearchParams({ page: String(page), size: '20', sort: sort.value })
    // 数据范围由服务端裁剪；通过 Shell 的共享客户端，让 401 走统一刷新与登出流程。
    orders.value = await client.page<Order>(`/lowcode/orders?${query}`)
  } catch (cause) {
    orders.value = null
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('failed'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
  } finally {
    busy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <section :aria-label="t('title')">
    <PageHeader :title="t('title')" />
    <PageToolbar>
      <el-select v-model="sort" class="sort" :aria-label="t('sort')" @change="load(1)">
        <el-option v-for="item in sorts" :key="item.value" :value="item.value" :label="t(item.label)" />
      </el-select>
      <!-- 主操作（如新增）放在 #actions 插槽，靠右显示；每个区域只有一个实心主按钮。 -->
    </PageToolbar>
    <ErrorAlert v-if="error" :tip="error" />
    <ListPage :page="orders" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <el-table-column :label="t('name')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span data-testid="order-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('amount')" width="140" align="right">
        <template #default="{ row }">{{ money.format(row.amount) }}</template>
      </el-table-column>
      <el-table-column :label="t('createdAt')" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </ListPage>
  </section>
</template>

<style scoped>
.sort {
  width: 160px;
}
</style>
