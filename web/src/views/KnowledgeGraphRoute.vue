<script setup lang="ts">
import { session } from '../auth'
import KnowledgeGraphView from './KnowledgeGraphView.vue'
</script>

<template><KnowledgeGraphView :key="`${session?.user.id || ''}:${$route.fullPath}`" /></template>
