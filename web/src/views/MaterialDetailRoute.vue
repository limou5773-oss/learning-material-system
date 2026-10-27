<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { session } from '../auth'
import MaterialDetailView from './MaterialDetailView.vue'
const route = useRoute(), router = useRouter()
watch(() => session.value, () => {
  if (!session.value) void router.replace({ name: 'login', query: { redirect: route.fullPath } })
}, { flush: 'sync' })
</script>
<template><MaterialDetailView v-if="session" :key="`${session.user.id}:${session.accessToken}:${$route.fullPath}`" /></template>
