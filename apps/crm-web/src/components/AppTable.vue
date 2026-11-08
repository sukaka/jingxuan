<script lang="ts">
import {
  Fragment,
  cloneVNode,
  computed,
  defineComponent,
  getCurrentInstance,
  h,
  inject,
  mergeProps,
  nextTick,
  ref,
  watch,
} from 'vue'
import type { VNode, VNodeArrayChildren } from 'vue'
import { ElButton, ElCheckbox, ElPopover, ElTable } from 'element-plus'
import type { TableInstance } from 'element-plus'
import { piniaSymbol } from 'pinia'

interface ColumnMeta {
  id: string
  label: string
  prop?: string
  columnType?: string
}

interface StoredTableSettings {
  hidden?: unknown
  widths?: unknown
}

const nativeMethods = [
  'clearSelection', 'toggleRowSelection', 'toggleAllSelection', 'toggleRowExpansion',
  'setCurrentRow', 'clearSort', 'clearFilter', 'doLayout', 'sort',
]

function isColumn(node: VNode): boolean {
  return node.type != null && typeof node.type === 'object' && 'name' in node.type && node.type.name === 'ElTableColumn'
}

function signatureHash(value: string): string {
  let hash = 2166136261
  for (let index = 0; index < value.length; index++) {
    hash = Math.imul(hash ^ value.charCodeAt(index), 16777619)
  }
  return (hash >>> 0).toString(36)
}

export default defineComponent({
  name: 'AppTable',
  inheritAttrs: false,
  props: {
    tableId: { type: String, default: undefined },
  },
  setup(props, { attrs, slots, expose }) {
    const table = ref<TableInstance>()
    const route = { get path() { return window.location.pathname } }
    const pinia = inject(piniaSymbol, null)
    const userId = computed(() => (pinia?.state.value.auth as { user?: { userId?: number } } | undefined)?.user?.userId)
    const instance = getCurrentInstance()
    const availableColumns = ref<ColumnMeta[]>([])
    const hidden = ref<Set<string>>(new Set())
    const widths = ref<Record<string, number>>({})
    const loadedKey = ref('')
    let currentSignature = ''

    const hiddenCount = computed(() => availableColumns.value.filter((column) => hidden.value.has(column.id)).length)

    function collect(nodes: VNodeArrayChildren) {
      const columns: ColumnMeta[] = []
      const occurrences = new Map<string, number>()

      function visit(items: VNodeArrayChildren): VNodeArrayChildren {
        return items.map((item) => {
          if (!item || typeof item !== 'object' || !('type' in item)) return item
          const node = item as VNode
          if (node.type === Fragment && Array.isArray(node.children)) {
            return cloneVNode(h(Fragment, visit(node.children as VNodeArrayChildren)), { key: node.key ?? undefined })
          }
          if (!isColumn(node)) return node
          const columnProps = (node.props ?? {}) as Record<string, unknown>
          const prop = typeof columnProps.prop === 'string' ? columnProps.prop : undefined
          const columnType = typeof columnProps.type === 'string' ? columnProps.type : undefined
          const label = typeof columnProps.label === 'string' && columnProps.label
            ? columnProps.label
            : columnType === 'selection' ? '选择' : columnType === 'expand' ? '详情' : columnType === 'index' ? '序号' : prop ?? `列 ${columns.length + 1}`
          const base = prop ? `prop:${prop}` : columnType && columnType !== 'default' ? `type:${columnType}` : `label:${label}`
          const occurrence = (occurrences.get(base) ?? 0) + 1
          occurrences.set(base, occurrence)
          const id = `${base}#${occurrence}`
          columns.push({ id, label: occurrence > 1 ? `${label}（${occurrence}）` : label, prop, columnType })
          if (hidden.value.has(id)) return null
          return cloneVNode(node, {
            resizable: true,
            ...(widths.value[id] === undefined ? {} : { width: widths.value[id] }),
          })
        })
      }

      return { columns, nodes: visit(nodes) }
    }

    function saveSettings() {
      const columns = availableColumns.value
      const signature = columns.map((column) => column.id).join('|')
      const componentType = instance?.type as { __name?: string; name?: string } | undefined
      const componentName = componentType?.__name ?? componentType?.name ?? 'table'
      const tableKey = props.tableId ?? `${route.path}:${componentName}:${signatureHash(signature)}`
      const key = `crm:table:v1:${userId.value ?? 'anonymous'}:${tableKey}`
      if (loadedKey.value !== key) {
        hidden.value = new Set()
        widths.value = {}
        loadedKey.value = key
        try {
          const raw: unknown = JSON.parse(localStorage.getItem(key) ?? 'null')
          if (raw && typeof raw === 'object') {
            const stored = raw as StoredTableSettings
            if (Array.isArray(stored.hidden)) {
              hidden.value = new Set(stored.hidden.filter((id): id is string => typeof id === 'string' && id.length <= 160))
            }
            if (stored.widths && typeof stored.widths === 'object' && !Array.isArray(stored.widths)) {
              widths.value = Object.fromEntries(Object.entries(stored.widths).flatMap(([id, width]) =>
                typeof width === 'number' && Number.isFinite(width) && width >= 40 && width <= 2000 ? [[id, Math.round(width)]] : [],
              ))
            }
          }
        } catch {
          try {
            localStorage.removeItem(key)
          } catch {
            // 无法访问本地存储时使用默认列设置。
          }
        }
      }
      if (columns.length > 0 && columns.every((column) => hidden.value.has(column.id))) {
        hidden.value = new Set()
      }
      return key
    }

    function persist() {
      try {
        localStorage.setItem(loadedKey.value, JSON.stringify({ hidden: [...hidden.value], widths: widths.value }))
      } catch {
        // 浏览器禁用存储时保留当前页面的设置。
      }
    }

    function setColumnVisible(id: string, visible: boolean) {
      const next = new Set(hidden.value)
      if (visible) next.delete(id)
      else {
        if (availableColumns.value.filter((column) => !next.has(column.id)).length <= 1) return
        next.add(id)
      }
      hidden.value = next
      persist()
    }

    function resetSettings() {
      hidden.value = new Set()
      widths.value = {}
      try {
        localStorage.removeItem(loadedKey.value)
      } catch {
        // 与保存失败一致，仅影响持久化，不影响表格。
      }
      void nextTick(() => table.value?.doLayout())
    }

    function handleDragEnd(width: number, _oldWidth: number, column: { id?: string; property?: string; label?: string; type?: string }) {
      const index = /_column_(\d+)$/.exec(column.id ?? '')
      const meta = (index ? availableColumns.value[Number(index[1]) - 1] : undefined)
        ?? availableColumns.value.find((item) => item.prop === column.property && item.label === column.label)
      if (!meta || !Number.isFinite(width)) return
      widths.value = { ...widths.value, [meta.id]: Math.min(2000, Math.max(40, Math.round(width))) }
      persist()
    }

    watch(userId, () => {
      loadedKey.value = ''
      availableColumns.value = []
    })

    const exposed: Record<string, (...args: unknown[]) => unknown> = { table: () => table.value }
    for (const method of nativeMethods) {
      exposed[method] = (...args: unknown[]) => {
        const target = table.value as unknown as Record<string, (...args: unknown[]) => unknown> | undefined
        return target?.[method]?.(...args)
      }
    }
    expose(exposed)

    return () => {
      const collected = collect(slots.default?.() ?? [])
      availableColumns.value = collected.columns
      currentSignature = collected.columns.map((column) => column.id).join('|')
      saveSettings()
      const settings = h('div', { class: 'app-table__toolbar' }, [
        hiddenCount.value > 0 ? h('span', { class: 'app-table__hidden-count' }, `已隐藏 ${hiddenCount.value} 列`) : null,
        h(ElPopover, { trigger: 'click', width: 270, placement: 'bottom-end' }, {
          reference: () => h(ElButton, {
            text: true,
            size: 'small',
            class: 'app-table__settings-button',
            'aria-label': '设置表格显示列',
            'data-table-columns': props.tableId ?? currentSignature,
          }, () => '列设置'),
          default: () => h('div', { class: 'app-table__panel' }, [
            h('div', { class: 'app-table__panel-title' }, '选择要显示的列'),
            h('div', { class: 'app-table__column-list' }, availableColumns.value.map((column) => {
              const visible = !hidden.value.has(column.id)
              return h(ElCheckbox, {
                key: column.id,
                modelValue: visible,
                label: column.label,
                disabled: visible && availableColumns.value.length - hiddenCount.value === 1,
                'onUpdate:modelValue': (value: string | number | boolean) => setColumnVisible(column.id, Boolean(value)),
              })
            })),
            h(ElButton, { text: true, size: 'small', onClick: resetSettings, class: 'app-table__reset' }, () => '恢复默认'),
          ]),
        }),
      ])
      return h('div', { class: 'app-table' }, [
        settings,
        h(ElTable, mergeProps(attrs, { ref: table, border: attrs.border ?? true, onHeaderDragend: handleDragEnd }), {
          ...slots,
          default: () => collected.nodes,
        }),
      ])
    }
  },
})
</script>

<style scoped>
.app-table {
  width: 100%;
  min-width: 0;
  padding: 0 20px;
}

.app-table__toolbar {
  display: flex;
  min-height: 30px;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.app-table__hidden-count {
  color: var(--proto-muted, #74827f);
  font-size: 12px;
}

.app-table__settings-button,
.app-table__reset {
  font-weight: 500;
}

.app-table__panel-title {
  padding: 2px 0 10px;
  color: var(--proto-text, #243b38);
  font-size: 13px;
  font-weight: 600;
}

.app-table__column-list {
  display: grid;
  max-height: 300px;
  gap: 3px;
  overflow-y: auto;
  padding-bottom: 10px;
}

.app-table__column-list :deep(.el-checkbox) {
  height: 30px;
  margin-right: 0;
}
</style>
