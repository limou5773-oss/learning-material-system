<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeCard } from '../knowledge'
import { hierarchyGraph } from '../organization'

const props = defineProps<{ cards: KnowledgeCard[] }>()
const graph = computed(() => hierarchyGraph(props.cards))
const lines = computed(() => graph.value.edges.map(edge => ({ ...edge,
  parent: graph.value.nodes.find(node => node.id === edge.parentId)!, child: graph.value.nodes.find(node => node.id === edge.childId)!,
})))
</script>

<template>
  <div class="hierarchy-scroll" aria-label="当前范围父子层级图">
    <div class="hierarchy-stage" :style="{ width: `${graph.width}px`, height: `${graph.height}px` }">
      <svg :width="graph.width" :height="graph.height" aria-hidden="true">
        <path v-for="line in lines" :key="line.childId" :d="`M ${line.parent.x + 260} ${line.parent.y + 55} C ${line.parent.x + 280} ${line.parent.y + 55}, ${line.child.x - 20} ${line.child.y + 55}, ${line.child.x} ${line.child.y + 55}`" />
      </svg>
      <article v-for="node in graph.nodes" :key="node.id" class="hierarchy-node" :style="{ left: `${node.x}px`, top: `${node.y}px` }">
        <span v-if="node.externalParentId" class="outside-parent">父卡片未载入 · {{ node.externalParentId }}</span>
        <span v-else-if="node.isRoot">根卡片</span><span v-else>父卡片：{{ cards.find(card => card.id === node.parentCardId)?.title }}</span>
        <router-link :to="`/cards/${node.id}`"><h2>{{ node.title }}</h2></router-link>
        <p>{{ node.summary || node.content }}</p>
      </article>
      <p v-if="!cards.length" class="list-state">当前范围没有卡片。</p>
    </div>
  </div>
</template>

<style scoped>
.hierarchy-scroll { max-height: 620px; overflow: auto; border: 1px solid #dce5f2; border-radius: 14px; background: #f6f8fd; }
.hierarchy-stage { position: relative; }
svg { position: absolute; inset: 0; }
path { fill: none; stroke: #a6bbdf; stroke-width: 2; }
.hierarchy-node { position: absolute; width: 260px; min-height: 110px; padding: 16px; border: 1px solid #dce5f2; border-radius: 14px; background: white; }
.hierarchy-node span { color: #64748b; font-size: 12px; overflow-wrap: anywhere; }
.hierarchy-node .outside-parent { color: #805719; }
.hierarchy-node h2 { overflow: hidden; margin: 8px 0; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.hierarchy-node p { overflow: hidden; margin: 5px 0; color: #64748b; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
a:focus-visible { outline: 2px solid var(--brand-hover); outline-offset: 2px; }
</style>
