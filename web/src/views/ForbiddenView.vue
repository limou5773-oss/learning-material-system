<script setup lang="ts">
const title = '无权限'
</script>

<template>
  <section class="materials-layout narrow materials-panel">
    <h1>{{ title }}</h1>
    <p role="alert">当前账号无权限访问用户管理，请联系管理员。</p>
    <router-link class="back-link" to="/me">返回我的信息</router-link>
  </section>
</template>
