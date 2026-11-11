<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, overlaySize, type ErrorTip } from '@crane199709/ui'
import { errorTip, jsonBody, type DataSource, type DataSourceInput } from './data-source'

const props = defineProps<{ modelValue: boolean; editing: DataSource | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      create: '新增数据源',
      edit: '编辑数据源',
      name: '名称',
      host: '主机',
      port: '端口',
      database: '库名',
      username: '账号',
      password: '密码',
      passwordKeep: '留空表示不修改',
      sslmode: 'SSL',
      sslDisable: '不使用',
      sslAllow: '服务器要求时使用',
      sslPrefer: '优先使用',
      sslRequire: '必须使用',
      required: '请填写{field}',
      test: '测试连接',
      testOk: '连接成功',
      testFailed: '测试连接失败，请稍后重试',
      saveFailed: '保存失败，请稍后重试',
      cancel: '取消',
      save: '保存',
    },
  },
})

const sslModes = [
  ['prefer', 'sslPrefer'],
  ['require', 'sslRequire'],
  ['allow', 'sslAllow'],
  ['disable', 'sslDisable'],
] as const

const client = useTenantClient()
const formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  host: '',
  port: 5432,
  database: '',
  username: '',
  password: '',
  sslmode: 'prefer',
})
const saving = ref(false)
const testing = ref(false)
const tested = ref(false)
const error = ref<ErrorTip | null>(null)
const isEdit = computed(() => props.editing !== null)

const required = (field: string) => ({ required: true, message: t('required', { field: t(field) }), trigger: 'blur' })
const rules = computed<FormRules>(() => ({
  name: [required('name')],
  host: [required('host')],
  port: [required('port')],
  database: [required('database')],
  username: [required('username')],
  // 编辑时密码留空表示沿用已保存的密码
  password: isEdit.value ? [] : [required('password')],
}))

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const source = props.editing
    Object.assign(form, {
      name: source?.name ?? '',
      host: source?.host ?? '',
      port: source?.port ?? 5432,
      database: source?.database ?? '',
      username: source?.username ?? '',
      password: '',
      sslmode: source?.params.sslmode ?? 'prefer',
    })
    error.value = null
    tested.value = false
    formRef.value?.clearValidate()
  },
)

// 测试成功的结论只对当时的字段成立，修改任一字段后清除
watch(form, () => {
  tested.value = false
})

function input(): DataSourceInput {
  return {
    name: form.name.trim(),
    // 一期先接入 PostgreSQL，其他类型由工单 03 加入类型选择
    type: 'POSTGRESQL',
    host: form.host.trim(),
    port: form.port,
    database: form.database.trim(),
    username: form.username.trim(),
    password: form.password,
    params: { sslmode: form.sslmode },
  }
}

async function testConnection() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  testing.value = true
  error.value = null
  tested.value = false
  try {
    const path = isEdit.value
      ? `/lowcode/data-sources/${props.editing!.id}/connection-test`
      : '/lowcode/data-sources/connection-test'
    await client.api<void>(path, jsonBody('POST', input()))
    tested.value = true
  } catch (cause) {
    error.value = errorTip(cause, t('testFailed'))
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  error.value = null
  try {
    if (isEdit.value) {
      await client.api<DataSource>(`/lowcode/data-sources/${props.editing!.id}`, jsonBody('PUT', input()))
    } else {
      await client.api<DataSource>('/lowcode/data-sources', jsonBody('POST', input()))
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (cause) {
    error.value = errorTip(cause, t('saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="isEdit ? t('edit') : t('create')"
    :size="overlaySize.drawerMd"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item :label="t('name')" prop="name">
        <el-input v-model="form.name" maxlength="120" />
      </el-form-item>
      <div class="address">
        <el-form-item :label="t('host')" prop="host" class="host">
          <el-input v-model="form.host" maxlength="253" />
        </el-form-item>
        <el-form-item :label="t('port')" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" :controls="false" />
        </el-form-item>
      </div>
      <el-form-item :label="t('database')" prop="database">
        <el-input v-model="form.database" maxlength="128" />
      </el-form-item>
      <el-form-item :label="t('username')" prop="username">
        <el-input v-model="form.username" maxlength="128" autocomplete="off" />
      </el-form-item>
      <el-form-item :label="t('password')" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          maxlength="256"
          autocomplete="new-password"
          :placeholder="isEdit ? t('passwordKeep') : ''"
        />
      </el-form-item>
      <el-form-item :label="t('sslmode')">
        <el-select v-model="form.sslmode">
          <el-option v-for="[value, label] in sslModes" :key="value" :value="value" :label="t(label)" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-alert v-if="tested" type="success" :title="t('testOk')" :closable="false" show-icon />
    <ErrorAlert v-if="error" :tip="error" />
    <template #footer>
      <el-button :loading="testing" @click="testConnection">{{ t('test') }}</el-button>
      <el-button @click="emit('update:modelValue', false)">{{ t('cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ t('save') }}</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.address {
  display: flex;
  gap: var(--forge-space-3);
}

.host {
  flex: 1;
}
</style>
