<script setup lang="ts">
import { computed } from 'vue'
import { graphLayout, type KnowledgeGraph } from '../relations'

const props = defineProps<{ graph: KnowledgeGraph; disabled: boolean; selectedCardId?: string; selectedRelationId?: string }>()
const emit = defineEmits<{ selectCard: [id: string]; selectRelation: [id: string] }>()
const layout = computed(() => graphLayout(props.graph))
</script>

<template>
  <div class="graph-scroll" aria-label="当前范围知识关联图">
    <div class="graph-stage" :style="{ width: `${layout.width}px`, height: `${layout.height}px` }">
      <svg :width="layout.width" :height="layout.height" aria-hidden="true">
        <defs><marker id="relation-arrow" markerUnits="userSpaceOnUse" markerWidth="9" markerHeight="9" refX="9" refY="4.5" orient="auto"><path class="arrow" d="M 0 0 L 9 4.5 L 0 9 z" /></marker></defs>
        <path v-for="edge in layout.edges" :key="edge.id" :data-relation-id="edge.id" :d="edge.d"
          :class="[edge.type.toLowerCase(), { selected: selectedRelationId === edge.id, disabled }]"
          :marker-end="edge.type === 'RELATED' ? undefined : 'url(#relation-arrow)'"
          @click="!disabled && emit('selectRelation', edge.id)" />
      </svg>
      <button v-for="node in layout.nodes" :key="node.id" type="button" class="graph-node"
        :style="{ left: `${node.x}px`, top: `${node.y}px` }" :disabled="disabled"
        :aria-label="`查看卡片${node.id}：${node.title}`" :aria-pressed="selectedCardId === node.id"
        @click="emit('selectCard', node.id)">
        <span>#{{ node.id }} · {{ node.subject || '未设置学科' }}</span><strong>{{ node.title }}</strong>
      </button>
    </div>
  </div>
</template>

<style scoped>
.graph-scroll { overflow: auto; max-height: 540px; border: 1px solid #dce5f2; border-radius: 14px; background: #f6f8fd; }
.graph-stage { position: relative; }
svg { position: absolute; inset: 0; }
svg > path { fill: none; stroke: #6886ac; stroke-width: 3; cursor: pointer; }
svg > path.example { stroke: #a2702b; stroke-dasharray: 6 3; }
svg > path.related { stroke: #7f8f9d; }
svg > path.selected { stroke: #214eb8; stroke-width: 5; }
svg > path.disabled { cursor: default; }
.arrow { fill: #536b89; }
.graph-node { position: absolute; display: flex; flex-direction: column; justify-content: center; gap: 8px; width: 190px; height: 80px; padding: 12px; text-align: left; border: 1px solid #b9cce7; border-radius: 12px; color: #34435a; background: white; font: inherit; cursor: pointer; }
.graph-node span { font-size: 11px; color: #64748b; }
.graph-node strong { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 14px; }
.graph-node[aria-pressed="true"] { border-color: var(--brand-hover); background: #edf3ff; }
.graph-node:disabled { opacity: .55; cursor: default; }
.graph-node:focus-visible { outline: 2px solid var(--brand-hover); outline-offset: 3px; }
</style>
