<script setup lang="ts">
import WorkspaceLayout from './WorkspaceLayout.vue'

const links = [
  { to: '/materials', label: '我的资料', icon: 'folder' },
  { to: '/cards', label: '知识卡片', icon: 'cards' },
  { to: '/ai-tasks', label: '整理任务', icon: 'tasks' },
  { to: '/cards/organize', label: '卡片编排', icon: 'layout' },
  { to: '/knowledge-graph', label: '知识关系图', icon: 'graph' },
] as const
</script>

<template><WorkspaceLayout label="学习空间" home="/materials" :links="links" account-path="/me" account-label="个人中心" learning><slot /></WorkspaceLayout></template>
