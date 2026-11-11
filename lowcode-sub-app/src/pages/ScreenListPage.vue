<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTableColumn,
} from 'element-plus'
import { useAccessControl, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ListPage,
  PageHeader,
  PageToolbar,
  formatDateTime,
  overlaySize,
  type ErrorTip,
} from '@crane199709/ui'
import { errorTip, jsonBody } from './data-source'
import { cloneDraft, emptyDraft, resolutions, type Screen } from './screen'

const client = useTenantClient()
const router = useRouter()
const access = useAccessControl()
const canEdit = computed(() => access.has('screen:edit'))
const screens = ref<Page<Screen> | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const dialog = ref(false)
const saving = ref(false)
const editing = ref<Screen | null>(null)
const name = ref('')
const preset = ref('1920×1080')
const width = ref(1920)
const height = ref(1080)
watch(preset, (value) => {
  if (value !== '自定义')
    [width.value, height.value] = value.split('×').map(Number) as [number, number]
})
async function load(page = screens.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    screens.value = await client.page<Screen>(`/lowcode/screens?page=${page}&size=20`)
  } catch (cause) {
    error.value = errorTip(cause, '大屏加载失败，请重试')
  } finally {
    busy.value = false
  }
}
function openForm(row: Screen | null) {
  editing.value = row
  name.value = row?.name ?? ''
  width.value = row?.draft.designWidth ?? 1920
  height.value = row?.draft.designHeight ?? 1080
  const value = `${width.value}×${height.value}`
  preset.value = resolutions.includes(value) ? value : '自定义'
  error.value = null
  dialog.value = true
}
function edit(row: Screen) {
  router.push({ name: 'lowcode:screen-editor', params: { id: row.id } })
}
async function save() {
  if (
    !name.value.trim() ||
    name.value.trim().length > 120 ||
    width.value < 640 ||
    width.value > 7680 ||
    height.value < 640 ||
    height.value > 7680
  ) {
    error.value = { message: '请填写名称，设计分辨率的宽高须在 640–7680 之间' }
    return
  }
  saving.value = true
  error.value = null
  try {
    const draft = editing.value ? cloneDraft(editing.value.draft) : emptyDraft()
    draft.designWidth = width.value
    draft.designHeight = height.value
    const result = await client.api<Screen>(
      editing.value ? `/lowcode/screens/${editing.value.id}` : '/lowcode/screens',
      jsonBody(editing.value ? 'PUT' : 'POST', { name: name.value.trim(), draft }),
    )
    dialog.value = false
    if (editing.value) await load()
    else edit(result)
  } catch (cause) {
    error.value = errorTip(cause, '保存失败，请重试')
  } finally {
    saving.value = false
  }
}
async function copy(row: Screen) {
  error.value = null
  try {
    await client.api<Screen>(`/lowcode/screens/${row.id}/copy`, { method: 'POST' })
    await load(1)
  } catch (cause) {
    error.value = errorTip(cause, '复制失败，请重试')
  }
}
async function remove(row: Screen) {
  try {
    await ElMessageBox.confirm(`删除「${row.name}」后，该大屏将不再可用。`, '删除大屏', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await client.api<void>(`/lowcode/screens/${row.id}`, { method: 'DELETE' })
    await load()
  } catch (cause) {
    error.value = errorTip(cause, '删除失败，请重试')
  }
}
onMounted(() => load())
</script>
<template>
  <section aria-label="大屏">
    <PageHeader title="大屏" />
    <PageToolbar
      ><template #actions
        ><el-button v-if="canEdit" type="primary" @click="openForm(null)">新增</el-button></template
      ></PageToolbar
    >
    <ErrorAlert v-if="error && !dialog" :tip="error" />
    <ListPage :page="screens" :loading="busy" empty-text="暂无大屏" @page-change="load">
      <el-table-column label="名称" min-width="240" prop="name" show-overflow-tooltip />
      <el-table-column label="设计分辨率" width="160"
        ><template #default="{ row }"
          >{{ row.draft.designWidth }}×{{ row.draft.designHeight }}</template
        ></el-table-column
      >
      <el-table-column label="更新时间" width="180"
        ><template #default="{ row }">{{
          formatDateTime(row.updatedAt)
        }}</template></el-table-column
      >
      <el-table-column v-if="canEdit" label="操作" width="240" fixed="right"
        ><template #default="{ row }">
          <el-button link type="primary" @click="edit(row as Screen)">编辑</el-button>
          <el-button link type="primary" @click="openForm(row as Screen)">设置</el-button>
          <el-button link type="primary" @click="copy(row as Screen)">复制</el-button>
          <el-button link type="danger" @click="remove(row as Screen)">删除</el-button>
        </template></el-table-column
      >
    </ListPage>
    <el-dialog
      v-model="dialog"
      :title="editing ? '大屏设置' : '新增大屏'"
      :width="overlaySize.dialogMd"
      :close-on-click-modal="false"
    >
      <ErrorAlert v-if="error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="名称" required
          ><el-input v-model="name" aria-label="名称" maxlength="120"
        /></el-form-item>
        <el-form-item label="设计分辨率"
          ><el-select v-model="preset" aria-label="设计分辨率"
            ><el-option
              v-for="value in [...resolutions, '自定义']"
              :key="value"
              :label="value"
              :value="value" /></el-select
        ></el-form-item>
        <div v-if="preset === '自定义'" class="resolution-fields">
          <el-form-item label="宽度"
            ><el-input-number
              v-model="width"
              aria-label="画布宽度"
              :min="640"
              :max="7680"
              :precision="0"
          /></el-form-item>
          <el-form-item label="高度"
            ><el-input-number
              v-model="height"
              aria-label="画布高度"
              :min="640"
              :max="7680"
              :precision="0"
          /></el-form-item>
        </div>
      </el-form>
      <template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">{{
          editing ? '保存' : '创建并编辑'
        }}</el-button></template
      >
    </el-dialog>
  </section>
</template>
<style scoped>
.resolution-fields {
  display: flex;
  gap: var(--forge-space-4);
}
</style>
