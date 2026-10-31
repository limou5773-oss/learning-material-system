<script setup lang="ts">
import WorkspaceLayout from './WorkspaceLayout.vue'

const links = [
  { to: '/admin/statistics', label: '统计大屏', icon: 'chart' },
  { to: '/admin/users', label: '用户管理', icon: 'users' },
  { to: '/admin/reviews', label: '内容审核', icon: 'shield' },
  { to: '/admin/ai-tasks', label: '任务监控', icon: 'tasks' },
] as const
</script>

<template><WorkspaceLayout label="平台管理" home="/admin/statistics" :links="links" account-path="/admin/account" account-label="管理员账号"><slot /></WorkspaceLayout></template>
