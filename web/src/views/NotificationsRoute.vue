<script setup lang="ts">
import { session } from '../auth'
import NotificationsView from './NotificationsView.vue'
</script>

<template><NotificationsView :key="`${session?.user.id || ''}:${session?.accessToken || ''}:${$route.fullPath}`" /></template>
