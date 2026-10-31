<script setup lang="ts">
defineProps<{ name: 'folder' | 'cards' | 'tasks' | 'graph' | 'layout' | 'bell' | 'user' | 'users' | 'shield' | 'chart' | 'plus' | 'menu' | 'arrow' | 'image' | 'note' | 'pdf' | 'search' | 'spark' }>()
const paths = {
  folder: 'M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z',
  cards: 'M8 3h11a2 2 0 0 1 2 2v11M5 7h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2Zm2 5h6m-6 4h4',
  tasks: 'M9 5H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-4M9 3h6v4H9Zm-2 9 1 1 2-2m3 1h4m-10 5 1 1 2-2m3 1h4',
  graph: 'M12 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM5 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm14 0a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm-9-7-4 7m8-7 4 7M8 18h8',
  layout: 'M3 3h7v7H3Zm11 0h7v11h-7ZM3 14h7v7H3Zm11 4h7v3h-7Z',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-9 12a3 3 0 0 0 6 0',
  user: 'M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm-8 18v-2a8 8 0 0 1 16 0v2',
  users: 'M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm-7 18v-2a7 7 0 0 1 14 0v2m1-17a4 4 0 0 1 0 7m2 3a6 6 0 0 1 3 5v2',
  shield: 'm12 3 9 4v5c0 5-9 10-9 10S3 17 3 12V7Zm-4 9 3 3 5-6',
  chart: 'M3 3v18h18M7 16v-5m5 5V6m5 10V9',
  plus: 'M12 5v14M5 12h14',
  menu: 'M4 6h16M4 12h16M4 18h16',
  arrow: 'm9 5 7 7-7 7',
  image: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Zm-2 14 5-5 4 4 3-3 6 6M8 7h.01',
  note: 'M14 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-9M9 15l3-1 9-9-2-2-9 9Zm-2 3h9',
  pdf: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Zm0 0v6h6M8 13h8m-8 4h5',
  search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15Zm5.5 13 5 5',
  spark: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5ZM20 2v4m-2-2h4',
}
</script>

<template><svg class="ui-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name]" /></svg></template>
