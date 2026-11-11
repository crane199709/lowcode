<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  type FormInstance,
} from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, overlaySize, type ErrorTip } from '@crane199709/ui'
import { errorTip, jsonBody } from './data-source'
import type { DatabaseSource, Dataset, DatasetField, SqlPreview } from './dataset'

const props = defineProps<{ modelValue: boolean; editing: Dataset | null; readonly: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      create: '新增数据集',
      edit: '编辑数据集',
      view: '数据集详情',
      name: '名称',
      source: '数据源',
      sql: 'SQL',
      readonlyTip: '请使用只读账号',
      readonlyDescription:
        '仅允许单条 SELECT 或 WITH 查询。只读连接不能保证拦截所有写操作，账号权限是最终防线。',
      fields: '字段',
      fieldName: '名称',
      displayName: '显示名',
      type: '类型',
      role: '用途',
      date: '日期',
      dimension: '维度',
      measure: '度量',
      preview: '预览',
      result: '预览结果',
      truncated: '结果超过 {count} 行，已截断',
      noPreview: '预览后可查看结果并调整字段',
      emptyResult: '查询未返回数据',
      cancel: '取消',
      close: '关闭',
      save: '保存',
      required: '请填写{field}',
      sourcesFailed: '数据源加载失败，请重试',
      previewFailed: '预览失败，请检查 SQL',
      saveFailed: '保存失败，请重试',
      retry: '重试',
      NUMBER: '数值',
      STRING: '文本',
      BOOLEAN: '布尔',
      DATE: '日期',
      DATETIME: '日期时间',
      OTHER: '其他',
    },
  },
})
const client = useTenantClient()
const formRef = ref<FormInstance>()
const resultRef = ref<HTMLElement>()
const form = reactive({ name: '', dataSourceId: '', sql: '' })
const fields = ref<DatasetField[]>([])
const sources = ref<DatabaseSource[]>([])
const sourcesBusy = ref(false)
const sourcesError = ref<ErrorTip | null>(null)
const preview = ref<SqlPreview | null>(null)
const error = ref<ErrorTip | null>(null)
const previewing = ref(false)
const saving = ref(false)
let revision = 0
let sourceRequest = 0
let fieldOverrides = new Map<string, DatasetField>()
const title = computed(() => t(props.readonly ? 'view' : props.editing ? 'edit' : 'create'))
const rules = computed(() =>
  Object.fromEntries(
    ['name', 'dataSourceId', 'sql'].map((key) => [
      key,
      [
        {
          required: true,
          whitespace: true,
          message: t('required', { field: t(key === 'dataSourceId' ? 'source' : key) }),
          trigger: 'blur',
        },
      ],
    ]),
  ),
)

watch(
  () => [form.dataSourceId, form.sql],
  () => {
    revision += 1
    for (const field of fields.value) fieldOverrides.set(field.name, { ...field })
    preview.value = null
    fields.value = []
    error.value = null
  },
  { flush: 'sync' },
)
watch(
  () => props.modelValue,
  (isOpen) => {
    revision += 1
    sourceRequest += 1
    if (!isOpen) return
    Object.assign(form, {
      name: props.editing?.name ?? '',
      dataSourceId: props.editing?.dataSourceId ?? '',
      sql: props.editing?.sql ?? '',
    })
    fields.value = props.editing?.fields.map((field) => ({ ...field })) ?? []
    fieldOverrides = new Map(fields.value.map((field) => [field.name, { ...field }]))
    preview.value = null
    error.value = null
    formRef.value?.clearValidate()
    if (!props.readonly) void loadSources()
  },
)
async function loadSources() {
  const current = ++sourceRequest
  sourcesBusy.value = true
  sourcesError.value = null
  sources.value = []
  try {
    const items: DatabaseSource[] = []
    for (let page = 1; ; page += 1) {
      const result = await client.page<DatabaseSource>(
        `/lowcode/datasets/sources?page=${page}&size=100`,
      )
      items.push(...result.items)
      if (page >= result.page.totalPages) break
    }
    if (props.modelValue && current === sourceRequest) sources.value = items
  } catch (cause) {
    if (props.modelValue && current === sourceRequest)
      sourcesError.value = errorTip(cause, t('sourcesFailed'))
  } finally {
    if (current === sourceRequest) sourcesBusy.value = false
  }
}
async function showResult() {
  await nextTick()
  resultRef.value?.scrollIntoView({ block: 'nearest' })
}
async function runPreview() {
  if (!form.dataSourceId || !form.sql.trim()) {
    await formRef.value?.validate().catch(() => false)
    return false
  }
  const current = revision
  let succeeded = false
  previewing.value = true
  preview.value = null
  error.value = null
  try {
    const result = await client.api<SqlPreview>(
      '/lowcode/datasets/preview',
      jsonBody('POST', { dataSourceId: form.dataSourceId, sql: form.sql }),
    )
    if (current !== revision || !props.modelValue) return false
    const overrides = new Map([
      ...fieldOverrides,
      ...fields.value.map((field) => [field.name, field] as const),
    ])
    fields.value = result.fields.map((field) => {
      const previous = overrides.get(field.name)
      return previous
        ? { ...field, displayName: previous.displayName, role: previous.role, date: previous.date }
        : field
    })
    preview.value = result
    succeeded = true
  } catch (cause) {
    if (current === revision && props.modelValue) error.value = errorTip(cause, t('previewFailed'))
  } finally {
    previewing.value = false
  }
  if (current === revision) await showResult()
  return succeeded
}
async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  // SQL 改动后先用最新模型筛选同名覆盖，避免丢失显示配置或提交已经移除的 Field。
  if (!fields.value.length && fieldOverrides.size && !(await runPreview())) return
  const current = revision
  saving.value = true
  error.value = null
  try {
    const body = {
      ...form,
      fields: fields.value.map(({ name, displayName, role, date }) => ({
        name,
        displayName,
        role,
        date,
      })),
    }
    await client.api<Dataset>(
      props.editing ? `/lowcode/datasets/${props.editing.id}` : '/lowcode/datasets',
      jsonBody(props.editing ? 'PUT' : 'POST', body),
    )
    emit('saved')
    if (current === revision) emit('update:modelValue', false)
  } catch (cause) {
    if (current === revision && props.modelValue) {
      error.value = errorTip(cause, t('saveFailed'))
      await showResult()
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="overlaySize.drawerLg"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="readonly">
      <el-form label-position="top">
        <el-form-item :label="t('name')">{{ editing?.name || '—' }}</el-form-item>
        <el-form-item :label="t('source')">{{ editing?.dataSourceName || '—' }}</el-form-item>
        <el-form-item :label="t('sql')">
          <pre class="sql-text">{{ editing?.sql || '—' }}</pre>
        </el-form-item>
      </el-form>
    </template>
    <template v-else>
      <el-alert
        class="notice"
        type="warning"
        :title="t('readonlyTip')"
        :description="t('readonlyDescription')"
        :closable="false"
        show-icon
      />
      <ErrorAlert v-if="sourcesError" :tip="sourcesError" />
      <el-button v-if="sourcesError" @click="loadSources">{{ t('retry') }}</el-button>
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="saving" label-position="top">
        <el-form-item :label="t('name')" prop="name"
          ><el-input v-model="form.name" :aria-label="t('name')" maxlength="120"
        /></el-form-item>
        <el-form-item :label="t('source')" prop="dataSourceId">
          <el-select
            v-model="form.dataSourceId"
            :aria-label="t('source')"
            filterable
            :loading="sourcesBusy"
          >
            <el-option
              v-for="source in sources"
              :key="source.id"
              :value="source.id"
              :label="source.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('sql')" prop="sql"
          ><el-input
            v-model="form.sql"
            :aria-label="t('sql')"
            type="textarea"
            :rows="5"
            maxlength="65536"
            placeholder="SELECT ..."
        /></el-form-item>
      </el-form>
    </template>
    <div ref="resultRef">
      <ErrorAlert v-if="error" :tip="error" />
      <template v-if="fields.length">
        <h3>{{ t('fields') }}</h3>
        <el-table :data="fields">
          <el-table-column
            prop="name"
            :label="t('fieldName')"
            min-width="110"
            show-overflow-tooltip
          />
          <el-table-column :label="t('displayName')" min-width="140">
            <template #default="{ row }"
              ><span v-if="readonly">{{ row.displayName }}</span
              ><el-input
                v-else
                v-model="row.displayName"
                :aria-label="`${row.name} ${t('displayName')}`"
                maxlength="120"
                :disabled="saving"
            /></template>
          </el-table-column>
          <el-table-column :label="t('type')" width="90"
            ><template #default="{ row }">{{ t(row.type) }}</template></el-table-column
          >
          <el-table-column :label="t('role')" width="110">
            <template #default="{ row }"
              ><span v-if="readonly">{{ t(row.role === 'MEASURE' ? 'measure' : 'dimension') }}</span
              ><el-select
                v-else
                v-model="row.role"
                :aria-label="`${row.name} ${t('role')}`"
                :disabled="saving"
                ><el-option value="DIMENSION" :label="t('dimension')" /><el-option
                  value="MEASURE"
                  :label="t('measure')" /></el-select
            ></template>
          </el-table-column>
          <el-table-column :label="t('date')" width="70"
            ><template #default="{ row }"
              ><el-checkbox
                v-model="row.date"
                :aria-label="`${row.name} ${t('date')}`"
                :disabled="readonly || saving" /></template
          ></el-table-column>
        </el-table>
      </template>
      <template v-if="preview">
        <h3>{{ t('result') }}</h3>
        <el-alert
          v-if="preview.truncated"
          type="warning"
          :title="t('truncated', { count: preview.maxRows })"
          :closable="false"
        />
        <el-table :data="preview.rows" :empty-text="t('emptyResult')" :max-height="300">
          <el-table-column
            v-for="(field, index) in preview.fields"
            :key="field.name"
            :label="field.displayName"
            min-width="140"
            show-overflow-tooltip
            :align="field.type === 'NUMBER' ? 'right' : 'left'"
          >
            <template #default="{ row }">{{ row[index] ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else-if="!readonly && !fields.length" :description="t('noPreview')" />
    </div>
    <template #footer>
      <el-button v-if="!readonly" :loading="previewing" :disabled="saving" @click="runPreview">{{
        t('preview')
      }}</el-button>
      <el-button @click="emit('update:modelValue', false)">{{
        t(readonly ? 'close' : 'cancel')
      }}</el-button>
      <el-button
        v-if="!readonly"
        type="primary"
        :loading="saving"
        :disabled="previewing"
        @click="save"
        >{{ t('save') }}</el-button
      >
    </template>
  </el-drawer>
</template>
<style scoped>
.notice {
  margin-bottom: var(--forge-space-4);
}
h3 {
  margin-block: var(--forge-space-4) var(--forge-space-3);
  font-size: var(--forge-font-size-sm);
}
.sql-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
}
</style>
