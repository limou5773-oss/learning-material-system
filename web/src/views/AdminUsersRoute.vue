<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { session } from '../auth'
import AdminUsersView from './AdminUsersView.vue'
import ForbiddenView from './ForbiddenView.vue'

const route = useRoute(), router = useRouter()
watch(() => [session.value?.user.id, session.value?.accessToken, session.value?.user.role], () => {
  if (!session.value) void router.replace({ name: 'login', query: { redirect: route.fullPath } })
  else if (session.value.user.role !== 'ADMIN') void router.replace({ name: 'forbidden' })
}, { flush: 'sync' })
</script>

<template>
  <AdminUsersView v-if="session?.user.role === 'ADMIN'" :key="`${session.user.id}:${session.accessToken}:${session.user.role}:${$route.fullPath}`" />
  <ForbiddenView v-else />
</template>
