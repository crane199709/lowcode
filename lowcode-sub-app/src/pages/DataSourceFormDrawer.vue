<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  type FormInstance,
  type FormItemRule,
  type FormRules,
} from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, overlaySize, type ErrorTip } from '@crane199709/ui'
import {
  authTypeLabels,
  dataSourceTypes,
  errorTip,
  isDatabaseType,
  jsonBody,
  typeDefaults,
  typeLabels,
  type ApiAuthType,
  type ApiMethod,
  type DataSource,
  type DataSourceInput,
  type DataSourceType,
} from './data-source'

const props = defineProps<{ modelValue: boolean; editing: DataSource | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      create: '新增数据源',
      edit: '编辑数据源',
      name: '名称',
      type: '类型',
      host: '主机',
      port: '端口',
      database: '库名',
      serviceName: '服务名',
      username: '账号',
      password: '密码',
      passwordKeep: '留空表示不修改',
      apiUrl: '接口地址',
      apiMethod: '请求方法',
      apiBody: '请求体（JSON）',
      authType: '认证方式',
      headerName: '请求头名称',
      headerValue: '请求头值',
      headerValueKeep: '留空表示不修改',
      urlInvalid: '请输入 http 或 https 的完整地址',
      jsonInvalid: '请输入有效的 JSON',
      ssl: 'SSL',
      sslDisable: '不使用',
      sslAllow: '服务器要求时使用',
      sslPrefer: '优先使用',
      sslRequire: '必须使用',
      allowPublicKeyRetrieval: '允许获取服务器公钥（不用 SSL 时需要）',
      encrypt: '加密连接',
      encryptOn: '加密',
      encryptOff: '不加密',
      trustServerCertificate: '信任服务器证书（自签证书时需要）',
      required: '请填写{field}',
      test: '测试连接',
      testOk: '连接成功',
      testOkStatus: '连接成功（HTTP {status}）',
      testFailed: '测试连接失败，请稍后重试',
      saveFailed: '保存失败，请稍后重试',
      cancel: '取消',
      save: '保存',
    },
  },
})

// 取值与后端白名单一致，各类型写法不同
const postgresSslModes = [
  ['prefer', 'sslPrefer'],
  ['require', 'sslRequire'],
  ['allow', 'sslAllow'],
  ['disable', 'sslDisable'],
] as const
const mysqlSslModes = [
  ['PREFERRED', 'sslPrefer'],
  ['REQUIRED', 'sslRequire'],
  ['DISABLED', 'sslDisable'],
] as const
const apiMethods: ApiMethod[] = ['GET', 'POST']
const apiAuthTypes: ApiAuthType[] = ['NONE', 'HEADER', 'BASIC']

const client = useTenantClient()
const formRef = ref<FormInstance>()
const resultRef = ref<HTMLElement>()
const form = reactive({
  name: '',
  type: 'POSTGRESQL' as DataSourceType,
  host: '',
  port: typeDefaults.POSTGRESQL.port,
  database: '',
  username: '',
  password: '',
  params: { ...typeDefaults.POSTGRESQL.params },
  apiUrl: '',
  apiMethod: 'GET' as ApiMethod,
  apiBody: '',
  apiAuthType: 'NONE' as ApiAuthType,
  apiHeaderName: '',
  apiHeaderValue: '',
})
const saving = ref(false)
const testing = ref(false)
const tested = ref(false)
const testStatus = ref<number | null>(null)
const error = ref<ErrorTip | null>(null)
let formRevision = 0
const isEdit = computed(() => props.editing !== null)
const isApi = computed(() => form.type === 'API')
const databaseLabel = computed(() => (form.type === 'ORACLE' ? 'serviceName' : 'database'))
const testOkTitle = computed(() =>
  testStatus.value === null ? t('testOk') : t('testOkStatus', { status: testStatus.value }),
)

const required = (field: string) => ({
  required: true,
  message: t('required', { field: t(field) }),
  trigger: 'blur',
})
const validateUrl: FormItemRule['validator'] = (_rule, value, callback) => {
  try {
    const url = new URL(String(value))
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) {
      throw new Error('invalid')
    }
    if (url.port === '0') throw new Error('invalid')
    callback()
  } catch {
    callback(new Error(t('urlInvalid')))
  }
}
const validateJson: FormItemRule['validator'] = (_rule, value, callback) => {
  const text = String(value)
  if (!text.trim()) {
    callback()
    return
  }
  try {
    JSON.parse(text)
    callback()
  } catch {
    callback(new Error(t('jsonInvalid')))
  }
}

const rules = computed<FormRules>(() => {
  if (isApi.value) {
    return {
      name: [required('name')],
      apiUrl: [required('apiUrl'), { validator: validateUrl, trigger: 'blur' }],
      apiBody: form.apiMethod === 'POST' ? [{ validator: validateJson, trigger: 'blur' }] : [],
      apiHeaderName: form.apiAuthType === 'HEADER' ? [required('headerName')] : [],
      apiHeaderValue:
        form.apiAuthType === 'HEADER' && !isEdit.value ? [required('headerValue')] : [],
      username: form.apiAuthType === 'BASIC' ? [required('username')] : [],
      password: form.apiAuthType === 'BASIC' && !isEdit.value ? [required('password')] : [],
    }
  }
  return {
    name: [required('name')],
    host: [required('host')],
    port: [required('port')],
    database: [required(databaseLabel.value)],
    username: [required('username')],
    // 编辑时密码留空表示沿用已保存的密码
    password: isEdit.value ? [] : [required('password')],
  }
})

watch(
  () => props.modelValue,
  (open) => {
    formRevision += 1
    if (!open) return
    const source = props.editing
    const type = source?.type ?? 'POSTGRESQL'
    const databaseType = isDatabaseType(type) ? type : 'POSTGRESQL'
    Object.assign(form, {
      name: source?.name ?? '',
      type,
      host: source?.host ?? '',
      port: source?.port ?? typeDefaults[databaseType].port,
      database: source?.database ?? '',
      username: source?.username ?? '',
      password: '',
      params: { ...typeDefaults[databaseType].params, ...(source?.params ?? {}) },
      apiUrl: source?.api?.url ?? '',
      apiMethod: source?.api?.method ?? 'GET',
      apiBody: source?.api?.body ?? '',
      apiAuthType: source?.api?.authType ?? 'NONE',
      apiHeaderName: source?.api?.headerName ?? '',
      apiHeaderValue: '',
    })
    error.value = null
    tested.value = false
    testStatus.value = null
    formRef.value?.clearValidate()
  },
)

// 表单比抽屉高时结果落在可视区外，点击后看不到反馈
async function showResult() {
  await nextTick()
  resultRef.value?.scrollIntoView({ block: 'nearest' })
}

// 测试成功的结论只对当时的字段成立，修改任一字段后清除
watch(
  form,
  () => {
    formRevision += 1
    tested.value = false
    testStatus.value = null
  },
  { flush: 'sync' },
)

// 换类型时连接参数整体换成新类型的默认值（各类型参数名不同）；端口仍是旧类型默认值时一并换掉，用户改过的端口保留
function changeType(type: DataSourceType) {
  if (isDatabaseType(type)) {
    if (!isDatabaseType(form.type) || form.port === typeDefaults[form.type].port) {
      form.port = typeDefaults[type].port
    }
    form.params = { ...typeDefaults[type].params }
  }
  form.type = type
}

function databaseInput(): DataSourceInput {
  return {
    name: form.name.trim(),
    type: form.type,
    database: {
      host: form.host.trim(),
      port: form.port,
      database: form.database.trim(),
      username: form.username.trim(),
      password: form.password,
      params: { ...form.params },
    },
  }
}

function apiInput(): DataSourceInput {
  const isBasic = form.apiAuthType === 'BASIC'
  return {
    name: form.name.trim(),
    type: form.type,
    api: {
      url: form.apiUrl.trim(),
      method: form.apiMethod,
      body: form.apiMethod === 'POST' ? form.apiBody : '',
      authType: form.apiAuthType,
      headerName: form.apiAuthType === 'HEADER' ? form.apiHeaderName.trim() : '',
      headerValue: form.apiAuthType === 'HEADER' ? form.apiHeaderValue : '',
      username: isBasic ? form.username.trim() : '',
      password: isBasic ? form.password : '',
    },
  }
}

function input(): DataSourceInput {
  return form.type === 'API' ? apiInput() : databaseInput()
}

async function testConnection() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  const revision = formRevision
  testing.value = true
  error.value = null
  tested.value = false
  testStatus.value = null
  try {
    const path = isEdit.value
      ? `/lowcode/data-sources/${props.editing!.id}/connection-test`
      : '/lowcode/data-sources/connection-test'
    const result = await client.api<{ status: number }>(path, jsonBody('POST', input()))
    if (revision === formRevision) {
      tested.value = true
      testStatus.value = typeof result?.status === 'number' ? result.status : null
    }
  } catch (cause) {
    if (revision === formRevision) error.value = errorTip(cause, t('testFailed'))
  } finally {
    testing.value = false
  }
  if (revision === formRevision) await showResult()
}

async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  error.value = null
  try {
    if (isEdit.value) {
      await client.api<DataSource>(
        `/lowcode/data-sources/${props.editing!.id}`,
        jsonBody('PUT', input()),
      )
    } else {
      await client.api<DataSource>('/lowcode/data-sources', jsonBody('POST', input()))
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (cause) {
    error.value = errorTip(cause, t('saveFailed'))
    await showResult()
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
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :validate-on-rule-change="false"
      label-position="top"
    >
      <el-form-item :label="t('name')" prop="name">
        <el-input v-model="form.name" maxlength="120" />
      </el-form-item>
      <el-form-item :label="t('type')">
        <el-select :model-value="form.type" @update:model-value="changeType">
          <el-option
            v-for="type in dataSourceTypes"
            :key="type"
            :value="type"
            :label="typeLabels[type]"
          />
        </el-select>
      </el-form-item>
      <template v-if="isApi">
        <el-form-item :label="t('apiUrl')" prop="apiUrl">
          <el-input v-model="form.apiUrl" maxlength="2048" placeholder="https://" />
        </el-form-item>
        <el-form-item :label="t('apiMethod')" prop="apiMethod">
          <el-select v-model="form.apiMethod">
            <el-option v-for="method in apiMethods" :key="method" :value="method" :label="method" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.apiMethod === 'POST'" :label="t('apiBody')" prop="apiBody">
          <el-input v-model="form.apiBody" type="textarea" :rows="4" maxlength="65536" />
        </el-form-item>
        <el-form-item :label="t('authType')" prop="apiAuthType">
          <el-select v-model="form.apiAuthType">
            <el-option
              v-for="authType in apiAuthTypes"
              :key="authType"
              :value="authType"
              :label="authTypeLabels[authType]"
            />
          </el-select>
        </el-form-item>
        <template v-if="form.apiAuthType === 'HEADER'">
          <el-form-item :label="t('headerName')" prop="apiHeaderName">
            <el-input v-model="form.apiHeaderName" maxlength="128" />
          </el-form-item>
          <el-form-item :label="t('headerValue')" prop="apiHeaderValue">
            <el-input
              v-model="form.apiHeaderValue"
              type="password"
              show-password
              maxlength="1024"
              autocomplete="new-password"
              :placeholder="isEdit ? t('headerValueKeep') : ''"
            />
          </el-form-item>
        </template>
        <template v-else-if="form.apiAuthType === 'BASIC'">
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
        </template>
      </template>
      <template v-else>
        <div class="address">
          <el-form-item :label="t('host')" prop="host" class="host">
            <el-input v-model="form.host" maxlength="253" />
          </el-form-item>
          <el-form-item :label="t('port')" prop="port">
            <el-input-number v-model="form.port" :min="1" :max="65535" :controls="false" />
          </el-form-item>
        </div>
        <el-form-item :label="t(databaseLabel)" prop="database">
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
        <template v-if="form.type === 'POSTGRESQL'">
          <el-form-item :label="t('ssl')">
            <el-select v-model="form.params.sslmode">
              <el-option
                v-for="[value, label] in postgresSslModes"
                :key="value"
                :value="value"
                :label="t(label)"
              />
            </el-select>
          </el-form-item>
        </template>
        <template v-else-if="form.type === 'MYSQL'">
          <el-form-item :label="t('ssl')">
            <el-select v-model="form.params.sslMode">
              <el-option
                v-for="[value, label] in mysqlSslModes"
                :key="value"
                :value="value"
                :label="t(label)"
              />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox
              v-model="form.params.allowPublicKeyRetrieval"
              true-value="true"
              false-value="false"
            >
              {{ t('allowPublicKeyRetrieval') }}
            </el-checkbox>
          </el-form-item>
        </template>
        <template v-else-if="form.type === 'CLICKHOUSE'">
          <el-form-item :label="t('encrypt')">
            <el-select v-model="form.params.ssl">
              <el-option value="true" :label="t('encryptOn')" />
              <el-option value="false" :label="t('encryptOff')" />
            </el-select>
          </el-form-item>
        </template>
        <template v-else-if="form.type === 'SQLSERVER'">
          <el-form-item :label="t('encrypt')">
            <el-select v-model="form.params.encrypt">
              <el-option value="true" :label="t('encryptOn')" />
              <el-option value="false" :label="t('encryptOff')" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-checkbox
              v-model="form.params.trustServerCertificate"
              true-value="true"
              false-value="false"
            >
              {{ t('trustServerCertificate') }}
            </el-checkbox>
          </el-form-item>
        </template>
      </template>
    </el-form>
    <div ref="resultRef">
      <el-alert v-if="tested" type="success" :title="testOkTitle" :closable="false" show-icon />
      <ErrorAlert v-if="error" :tip="error" />
    </div>
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
