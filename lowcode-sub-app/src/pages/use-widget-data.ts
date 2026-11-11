import { onBeforeUnmount, ref, watch, type ComputedRef } from 'vue'
import { useTenantClient } from '@crane199709/sdk'
import type { ChartConfig, WidgetData } from './widget-query'
import { errorTip, jsonBody } from './data-source'
export function useWidgetData(
  props: { widget: { id: string; type: string; config: ChartConfig }; queryBase: string },
  ready: ComputedRef<boolean>,
) {
  const client = useTenantClient()
  const result = ref<WidgetData>()
  const message = ref('')
  const loading = ref(false)
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  async function load() {
    const current = ++generation
    clearTimeout(timer)
    if (!ready.value) return
    loading.value = true
    try {
      const data = await client.api<WidgetData>(
        props.queryBase.endsWith('/draft')
          ? `${props.queryBase}/query`
          : `${props.queryBase}/widgets/${encodeURIComponent(props.widget.id)}/query`,
        props.queryBase.endsWith('/draft') ? jsonBody('POST', props.widget) : undefined,
      )
      if (current !== generation) return
      result.value = data
      message.value = ''
    } catch (cause) {
      if (current !== generation) return
      result.value = undefined
      message.value = errorTip(cause, '组件取数失败，请重试').message
    } finally {
      if (current === generation) {
        loading.value = false
        const seconds = props.widget.config.refreshSeconds
        if (!props.queryBase.endsWith('/draft') && seconds >= 10)
          timer = setTimeout(load, Math.min(seconds * 1000, 2147483647))
      }
    }
  }
  watch(
    () =>
      JSON.stringify([
        props.queryBase,
        props.widget.id,
        props.widget.type,
        props.widget.config.datasetId,
        props.widget.config.query,
        props.widget.config.refreshSeconds,
      ]),
    () => {
      ++generation
      clearTimeout(timer)
      loading.value = false
      result.value = undefined
      message.value = ''
      if (ready.value) timer = setTimeout(load, 150)
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    ++generation
    clearTimeout(timer)
  })
  return { result, message, loading, load }
}
