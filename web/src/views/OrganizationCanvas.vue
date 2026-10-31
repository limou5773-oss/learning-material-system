<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { KnowledgeCard } from '../knowledge'
import { canvasBounds, type LayoutItem } from '../organization'
import { savedGenerationLabel } from '../tasks'

const props = defineProps<{ cards: KnowledgeCard[]; items: LayoutItem[]; selectedId: string; disabled: boolean }>()
const emit = defineEmits<{ select: [id: string]; move: [item: { cardId: string; x: number; y: number }] }>()
const dragOrigin = ref<{ offsetX: number; offsetY: number } | null>(null)
const bounds = computed(() => ({ ...canvasBounds(props.items), ...dragOrigin.value }))
const visible = computed(() => props.items.map(item => ({ ...item, card: props.cards.find(card => card.id === item.cardId)! })))
let drag: { pointerId: number; cardId: string; x: number; y: number; clientX: number; clientY: number; target: HTMLElement } | null = null
function endDrag() {
  if (drag?.target.hasPointerCapture(drag.pointerId)) drag.target.releasePointerCapture(drag.pointerId)
  drag = null; dragOrigin.value = null
}
function startDrag(event: PointerEvent, item: LayoutItem) {
  if (props.disabled || event.button !== 0) return
  event.preventDefault(); endDrag(); emit('select', item.cardId)
  dragOrigin.value = { offsetX: bounds.value.offsetX, offsetY: bounds.value.offsetY }
  const target = event.currentTarget as HTMLElement
  drag = { pointerId: event.pointerId, cardId: item.cardId, x: item.x, y: item.y,
    clientX: event.clientX, clientY: event.clientY, target }
  target.setPointerCapture(event.pointerId)
}
function moveDrag(event: PointerEvent) {
  if (props.disabled || !drag || event.pointerId !== drag.pointerId) return
  emit('move', { cardId: drag.cardId, x: drag.x + event.clientX - drag.clientX, y: drag.y + event.clientY - drag.clientY })
}
function keyMove(event: KeyboardEvent, item: LayoutItem) {
  if (props.disabled) return
  const step = event.shiftKey ? 50 : 10
  const delta: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }
  const movement = delta[event.key]
  if (!movement) return
  event.preventDefault(); emit('select', item.cardId)
  emit('move', { cardId: item.cardId, x: item.x + movement[0], y: item.y + movement[1] })
}
watch(() => props.disabled, disabled => { if (disabled) endDrag() })
onMounted(() => window.addEventListener('blur', endDrag))
onUnmounted(() => { endDrag(); window.removeEventListener('blur', endDrag) })
</script>

<template>
  <div class="organization-scroll" aria-label="卡片位置画布" :aria-busy="disabled">
    <div class="organization-canvas" :style="{ width: `${bounds.width}px`, height: `${bounds.height}px` }">
      <article v-for="item in visible" :key="item.cardId" class="canvas-card" :class="{ selected: item.cardId === selectedId }"
        :style="{ left: `${item.x + bounds.offsetX}px`, top: `${item.y + bounds.offsetY}px`, zIndex: item.orderNo }">
        <button type="button" class="drag-handle" :aria-label="`移动${item.card.title}`" :disabled="disabled"
          @pointerdown="startDrag($event, item)" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag"
          @lostpointercapture="endDrag" @keydown="keyMove($event, item)">⠿ 移动 · 顺序 {{ item.orderNo }}</button>
        <span class="note-badge">{{ savedGenerationLabel(item.card.generationMode, item.card.isEdited) }}</span>
        <router-link :to="`/cards/${item.cardId}`"><h2>{{ item.card.title }}</h2></router-link>
        <p>{{ item.card.summary || item.card.content }}</p>
        <small>坐标 {{ item.x }}, {{ item.y }}</small>
      </article>
      <p v-if="!visible.length" class="list-state">画布为空。可加入当前范围的卡片，或保存空画布。</p>
    </div>
  </div>
</template>

<style scoped>
.organization-scroll { max-height: 620px; overflow: auto; border: 1px solid #dce5f2; border-radius: 14px; background: #f6f8fd; }
.organization-canvas { position: relative; background-image: radial-gradient(#dce5f2 1px, transparent 1px); background-size: 20px 20px; }
.canvas-card { position: absolute; width: 260px; height: 150px; padding: 0 14px 12px; border: 1px solid #dce5f2; border-radius: 14px; background: white; color: #34435a; }
.canvas-card.selected { border-color: var(--brand-hover); box-shadow: 0 0 0 2px #d9e4ff; }
.drag-handle { width: calc(100% + 28px); margin: 0 -14px 8px; padding: 7px 12px; border: 0; border-radius: 9px 9px 0 0; background: #eef3ff; color: #4265ac; text-align: left; cursor: grab; touch-action: none; }
.drag-handle:active { cursor: grabbing; }
.drag-handle:disabled { cursor: default; opacity: .6; }
.drag-handle:focus-visible, a:focus-visible { outline: 2px solid var(--brand-hover); outline-offset: 2px; }
.canvas-card h2 { overflow: hidden; margin: 5px 0; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.canvas-card p { overflow: hidden; margin: 5px 0; font-size: 13px; color: #64748b; text-overflow: ellipsis; white-space: nowrap; }
.canvas-card small { color: #64748b; font-size: 11px; }
</style>
