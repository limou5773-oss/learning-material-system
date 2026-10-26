<script setup lang="ts">
import { sourceUnavailableLabels, type CardSource, type KnowledgeCard } from '../knowledge'
import { savedGenerationLabel, savedGenerationWarning, sourceLocator } from '../tasks'
defineProps<{ card: KnowledgeCard; sources: CardSource[] }>()
</script>

<template>
  <section class="relation-card-detail">
    <span class="candidate-badge">{{ savedGenerationLabel(card.generationMode, card.isEdited) }}</span>
    <h3>{{ card.title }} <small>卡片 #{{ card.id }} · 版本 {{ card.version }}</small></h3>
    <p class="candidate-warning">{{ savedGenerationWarning(card.generationMode, card.isEdited) }}</p>
    <p class="candidate-copy">{{ card.content }}</p>
    <router-link :to="`/cards/${card.id}`">查看此卡片</router-link>
    <h4>来源与依据</h4>
    <p v-if="!sources.length" class="task-state">{{ card.origin === 'MANUAL' ? '人工创建的卡片无自动来源。' : '此卡片暂无来源。' }}</p>
    <div v-for="source in sources" :key="source.chunkId" class="candidate-source">
      <h4>{{ source.materialTitle }} · 来源版本 {{ source.materialVersion }}</h4>
      <template v-if="source.isAvailable">
        <p class="task-state">{{ sourceLocator(source) }}</p>
        <p v-if="source.qualityNote" class="candidate-warning">{{ source.qualityNote }}</p>
        <blockquote>{{ source.quoteText }}</blockquote>
        <details><summary>查看完整片段</summary><p class="candidate-copy">{{ source.content }}</p></details>
        <router-link :to="`/materials/${source.materialId}`">查看原资料</router-link>
      </template>
      <p v-else class="candidate-warning">{{ sourceUnavailableLabels[source.unavailableReason || ''] || '来源暂不可用，内容不可访问' }}</p>
    </div>
  </section>
</template>

<style scoped>
.relation-card-detail { min-width: 0; padding: 16px; border: 1px solid #e3e9f1; border-radius: 8px; overflow-wrap: anywhere; }
h3 { margin: 0 0 12px; font-size: 16px; }
small { display: block; margin-top: 8px; color: #64748b; font-size: 12px; font-weight: normal; }
h4 { margin-bottom: 8px; font-size: 14px; }
</style>
