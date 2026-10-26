<script setup lang="ts">
import { useRoute } from 'vue-router'
import CardEditorView from './CardEditorView.vue'

const route = useRoute()
</script>

<template>
  <CardEditorView :key="String(route.params.id || 'new')" />
</template>
