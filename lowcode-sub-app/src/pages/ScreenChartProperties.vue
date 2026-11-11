<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElSelect,
  ElTag,
} from 'element-plus'
import { useTenantClient } from '@crane199709/sdk'
import {
  aggregations,
  emptyChart,
  chartHint,
  dimensionLimit,
  measureLimit,
  chartNames,
  type QueryWidgetType,
  type Aggregation,
  type ChartConfig,
  type BindingDataset,
  type DateGrouping,
  type WidgetQuery,
} from './widget-query'
import { errorTip } from './data-source'
const props = defineProps<{ config: ChartConfig; screenId: string; type: QueryWidgetType }>()
const emit = defineEmits<{ change: [config: ChartConfig] }>()
const client = useTenantClient()
const datasets = ref<BindingDataset[]>([])
const error = ref('')
const page = ref(1)
const more = ref(false)
const selected = computed(() => datasets.value.find((d) => d.id === props.config.datasetId))
const outputColumns = computed(() =>
  props.type === 'DETAIL_TABLE'
    ? (props.config.query.fields ?? []).map(label)
    : [
        ...props.config.query.dimensions.map((d) => label(d.field)),
        ...props.config.query.measures.map(
          (m) => `${label(m.field)} · ${aggregations[m.aggregation]}`,
        ),
      ],
)
function label(field: string) {
  return selected.value?.fields.find((f) => f.name === field)?.displayName ?? field
}
async function load() {
  try {
    const response = await client.page<BindingDataset>(
      `/lowcode/screens/${props.screenId}/draft/datasets?page=${page.value}&size=100`,
    )
    datasets.value.push(...response.items)
    more.value = page.value < response.page.totalPages
    error.value = ''
  } catch (cause) {
    error.value = errorTip(cause, '数据集加载失败').message
  }
}
function loadMore() {
  page.value++
  void load()
}
function update(change: (config: ChartConfig) => void) {
  const config = JSON.parse(JSON.stringify(props.config)) as ChartConfig
  change(config)
  emit('change', config)
}
function dataset(value: string) {
  update((c) =>
    Object.assign(c, {
      ...emptyChart(props.type),
      datasetId: value,
      ...(props.type === 'INDICATOR'
        ? { title: c.title, unit: c.unit, numberFormat: c.numberFormat }
        : {}),
    }),
  )
}
function addDetail(field: string) {
  if (
    !selected.value?.fields.some((f) => f.name === field) ||
    (props.config.query.fields?.length ?? 0) >= 64 ||
    props.config.query.fields?.includes(field)
  )
    return
  update((c) => {
    c.query.fields ??= []
    c.query.fields.push(field)
    c.query.sorts = []
  })
}
function removeDetail(index: number) {
  update((c) => {
    c.query.fields?.splice(index, 1)
    c.query.sorts = []
  })
}
function drag(event: DragEvent, field: string) {
  event.dataTransfer?.setData('text/plain', field)
}
function add(field: string, target: 'dimensions' | 'measures') {
  const f = selected.value?.fields.find((f) => f.name === field)
  if (!f) {
    return
  }
  if (
    target === 'dimensions' &&
    (props.config.query.dimensions.length >= dimensionLimit(props.type) ||
      props.config.query.dimensions.some((d) => d.field === field))
  ) {
    return
  }
  if (target === 'measures' && props.config.query.measures.length >= measureLimit(props.type)) {
    return
  }
  update((c) => {
    c.query.sorts = []
    if (target === 'dimensions') c.query.dimensions.push({ field })
    else c.query.measures.push({ field, aggregation: f.type === 'NUMBER' ? 'SUM' : 'COUNT' })
  })
}
function drop(event: DragEvent, target: 'dimensions' | 'measures') {
  add(event.dataTransfer?.getData('text/plain') ?? '', target)
}
function remove(index: number, target: 'dimensions' | 'measures') {
  update((c) => {
    c.query[target].splice(index, 1)
    c.query.sorts = []
  })
}
function aggregate(index: number, value: Aggregation) {
  update((c) => {
    c.query.measures[index]!.aggregation = value
    c.query.sorts = []
  })
}
function dateGrouping(index: number, value: DateGrouping | '') {
  update((c) => {
    if (value) c.query.dimensions[index]!.dateGrouping = value
    else delete c.query.dimensions[index]!.dateGrouping
  })
}
function addFilter() {
  if (!selected.value?.fields[0]) {
    return
  }
  update((c) =>
    c.query.filters.push({ field: selected.value!.fields[0]!.name, operator: 'EQ', values: [''] }),
  )
}
function filterField(index: number, value: string) {
  update((c) => {
    c.query.filters[index]!.field = value
    c.query.filters[index]!.values = ['']
  })
}
function filterOperator(index: number, value: WidgetQuery['filters'][number]['operator']) {
  update((c) => {
    c.query.filters[index]!.operator = value
    c.query.filters[index]!.values = value === 'BETWEEN' ? ['', ''] : ['']
  })
}
function filterValue(index: number, value: string, part = 0) {
  update((c) => {
    const f = c.query.filters[index]!
    if (f.operator === 'IN') {
      f.values = value.split(',').map((v) => v.trim())
    } else {
      f.values[part] = value
    }
  })
}
function sort(column: number | undefined) {
  update((c) => {
    c.query.sorts = column === undefined || column === null ? [] : [{ column, direction: 'DESC' }]
  })
}
onMounted(load)
</script>
<template>
  <el-form-item label="数据集"
    ><el-select
      :model-value="config.datasetId"
      :aria-label="`${chartNames[type].replace('基础', '')}数据集`"
      placeholder="选择数据集"
      @update:model-value="dataset"
      ><el-option v-for="d in datasets" :key="d.id" :value="d.id" :label="d.name" /></el-select
  ></el-form-item>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button v-if="error" @click="load">重试加载数据集</el-button>
  <el-button v-if="more" link @click="loadMore">加载更多数据集</el-button>
  <el-checkbox
    v-if="type === 'PIE'"
    :model-value="config.donut"
    @change="
      update((c) => {
        c.donut = !!$event
      })
    "
    >环形显示</el-checkbox
  >
  <template v-if="type === 'INDICATOR'">
    <el-form-item label="标题"
      ><el-input
        :model-value="config.title"
        aria-label="指标标题"
        :maxlength="200"
        @update:model-value="
          update((c) => {
            c.title = $event
          })
        "
    /></el-form-item>
    <el-form-item label="单位"
      ><el-input
        :model-value="config.unit"
        aria-label="指标单位"
        :maxlength="40"
        @update:model-value="
          update((c) => {
            c.unit = $event
          })
        "
    /></el-form-item>
    <el-form-item label="数字格式"
      ><el-select
        :model-value="config.numberFormat"
        aria-label="数字格式"
        @update:model-value="
          update((c) => {
            c.numberFormat = $event
          })
        "
        ><el-option label="自动" value="AUTO" /><el-option label="整数" value="INTEGER" /><el-option
          label="两位小数"
          value="DECIMAL_2" /></el-select
    ></el-form-item>
  </template>
  <template v-if="selected">
    <el-alert v-if="type !== 'BAR'" :title="chartHint(type)" type="info" :closable="false" />
    <div class="field-list" aria-label="数据集字段">
      <button
        v-for="field in selected.fields"
        :key="field.name"
        class="field"
        draggable="true"
        type="button"
        :disabled="
          type === 'DETAIL_TABLE'
            ? config.query.fields?.includes(field.name) || (config.query.fields?.length ?? 0) >= 64
            : type === 'INDICATOR'
              ? config.query.measures.length >= 1
              : field.role === 'DIMENSION'
                ? config.query.dimensions.length >= dimensionLimit(type) ||
                  config.query.dimensions.some((d) => d.field === field.name)
                : config.query.measures.length >= measureLimit(type)
        "
        :aria-label="`拖动字段 ${field.displayName}`"
        @dragstart="drag($event, field.name)"
        @click="
          type === 'DETAIL_TABLE'
            ? addDetail(field.name)
            : add(
                field.name,
                type === 'INDICATOR'
                  ? 'measures'
                  : field.role === 'DIMENSION'
                    ? 'dimensions'
                    : 'measures',
              )
        "
      >
        {{ field.displayName }} <span>{{ field.role === 'DIMENSION' ? '维度' : '度量' }}</span>
      </button>
    </div>
    <div
      v-if="type === 'DETAIL_TABLE'"
      class="drop-zone"
      role="group"
      aria-label="明细字段放置区"
      @dragover.prevent
      @drop.prevent="addDetail($event.dataTransfer?.getData('text/plain') ?? '')"
    >
      <span v-if="!config.query.fields?.length">拖入明细字段</span>
      <el-tag
        v-for="(field, i) in config.query.fields"
        :key="field"
        closable
        @close="removeDetail(i)"
        >{{ label(field) }}</el-tag
      >
    </div>
    <div
      v-if="type !== 'DETAIL_TABLE' && type !== 'INDICATOR'"
      class="drop-zone"
      role="group"
      aria-label="维度放置区"
      @dragover.prevent
      @drop.prevent="drop($event, 'dimensions')"
    >
      <span v-if="!config.query.dimensions.length">拖入维度</span>
      <div v-for="(d, i) in config.query.dimensions" :key="i" class="measure">
        <el-tag closable @close="remove(i, 'dimensions')">{{ label(d.field) }}</el-tag>
        <el-select
          v-if="selected.fields.find((field) => field.name === d.field)?.date"
          :model-value="d.dateGrouping ?? ''"
          :aria-label="`维度${i + 1}日期分组`"
          @update:model-value="dateGrouping(i, $event)"
        >
          <el-option label="原值" value="" /><el-option label="按日" value="DAY" />
          <el-option label="按月" value="MONTH" /><el-option label="按年" value="YEAR" />
        </el-select>
      </div>
    </div>
    <div
      v-if="type !== 'DETAIL_TABLE'"
      class="drop-zone"
      role="group"
      aria-label="度量放置区"
      @dragover.prevent
      @drop.prevent="drop($event, 'measures')"
    >
      <span v-if="!config.query.measures.length">拖入度量</span>
      <div v-for="(m, i) in config.query.measures" :key="i" class="measure">
        <el-tag closable @close="remove(i, 'measures')">{{ label(m.field) }}</el-tag
        ><el-select
          :model-value="m.aggregation"
          :aria-label="`度量${i + 1}聚合`"
          @update:model-value="aggregate(i, $event)"
          ><el-option
            v-for="(text, key) in aggregations"
            :key="key"
            :value="key"
            :label="text"
            :disabled="
              key !== 'COUNT' &&
              key !== 'COUNT_DISTINCT' &&
              selected.fields.find((f) => f.name === m.field)?.type !== 'NUMBER'
            "
        /></el-select>
      </div>
    </div>
    <el-form-item label="排序"
      ><el-select
        :model-value="config.query.sorts[0]?.column"
        aria-label="排序字段"
        clearable
        placeholder="按维度排序"
        @update:model-value="sort"
        ><el-option
          v-for="(name, i) in outputColumns"
          :key="i"
          :label="name"
          :value="i" /></el-select
    ></el-form-item>
    <el-form-item v-if="config.query.sorts.length" label="顺序"
      ><el-select
        :model-value="config.query.sorts[0]!.direction"
        aria-label="排序顺序"
        @update:model-value="
          update((c) => {
            c.query.sorts[0]!.direction = $event
          })
        "
        ><el-option label="从大到小" value="DESC" /><el-option
          label="从小到大"
          value="ASC" /></el-select
    ></el-form-item>
    <el-checkbox
      :model-value="config.query.topN != null"
      @change="
        update((c) => {
          c.query.topN = $event ? 10 : null
        })
      "
      >限制条数（Top N）</el-checkbox
    >
    <el-form-item v-if="config.query.topN != null" label="条数"
      ><el-input-number
        :model-value="config.query.topN"
        aria-label="Top N"
        :min="1"
        :max="100000"
        :precision="0"
        @update:model-value="
          update((c) => {
            c.query.topN = $event ?? 1
          })
        "
    /></el-form-item>
    <div class="filters">
      <el-button :disabled="config.query.filters.length >= 20" @click="addFilter"
        >添加过滤</el-button
      >
      <div v-for="(f, i) in config.query.filters" :key="i" class="filter">
        <el-select
          :model-value="f.field"
          :aria-label="`过滤${i + 1}字段`"
          @update:model-value="filterField(i, $event)"
          ><el-option
            v-for="field in selected.fields"
            :key="field.name"
            :label="field.displayName"
            :value="field.name"
        /></el-select>
        <el-select
          :model-value="f.operator"
          :aria-label="`过滤${i + 1}运算符`"
          @update:model-value="filterOperator(i, $event)"
          ><el-option label="等于" value="EQ" /><el-option label="包含于" value="IN" /><el-option
            label="区间"
            value="BETWEEN"
        /></el-select>
        <el-input
          :model-value="f.operator === 'IN' ? f.values.join(',') : String(f.values[0] ?? '')"
          :aria-label="`过滤${i + 1}取值`"
          :placeholder="f.operator === 'IN' ? '多个值以逗号分隔' : '取值'"
          @update:model-value="filterValue(i, $event)"
        />
        <el-input
          v-if="f.operator === 'BETWEEN'"
          :model-value="String(f.values[1] ?? '')"
          :aria-label="`过滤${i + 1}上限`"
          placeholder="上限（含）"
          @update:model-value="filterValue(i, $event, 1)"
        />
        <el-button
          link
          type="danger"
          @click="
            update((c) => {
              c.query.filters.splice(i, 1)
            })
          "
          >移除过滤</el-button
        >
      </div>
    </div>
    <el-checkbox
      :model-value="config.refreshSeconds > 0"
      @change="
        update((c) => {
          c.refreshSeconds = $event ? 10 : 0
        })
      "
      >自动刷新</el-checkbox
    >
    <el-form-item v-if="config.refreshSeconds > 0" label="刷新间隔（秒）"
      ><el-input-number
        :model-value="config.refreshSeconds"
        aria-label="刷新间隔（秒）"
        :min="10"
        :precision="0"
        @update:model-value="
          update((c) => {
            c.refreshSeconds = Math.max(10, $event ?? 10)
          })
        "
    /></el-form-item>
  </template>
</template>
<style scoped>
.field-list {
  display: flex;
  gap: var(--forge-space-2);
  flex-wrap: wrap;
  margin-bottom: var(--forge-space-3);
}
.field {
  border: var(--el-border);
  border-radius: var(--forge-radius-sm);
  padding: var(--forge-space-2);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  cursor: grab;
  font: inherit;
}
.field:disabled {
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
  background: var(--el-fill-color-blank);
}
.field span {
  font-size: var(--forge-font-size-sm);
  color: var(--el-text-color-secondary);
}
.drop-zone {
  border: var(--el-border);
  border-style: dashed;
  border-radius: var(--forge-radius-sm);
  padding: var(--forge-space-3);
  margin-bottom: var(--forge-space-3);
  display: flex;
  flex-wrap: wrap;
  gap: var(--forge-space-2);
  min-height: var(--forge-space-5);
  color: var(--el-text-color-secondary);
}
.measure {
  display: flex;
  gap: var(--forge-space-2);
  align-items: center;
  width: 100%;
  min-width: 0;
}
.measure :deep(.el-select) {
  flex: 1;
  min-width: 0;
}
.filters {
  margin-block: var(--forge-space-3);
}
.filter {
  display: grid;
  gap: var(--forge-space-2);
  margin-top: var(--forge-space-3);
  padding-block: var(--forge-space-2);
  border-bottom: var(--el-border);
}
</style>
