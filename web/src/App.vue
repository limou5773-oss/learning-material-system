<script setup lang="ts">
import { session } from './auth'
</script>

<template>
  <div class="site-shell">
    <header class="site-header">
      <router-link class="brand" to="/me" aria-label="学材首页">
        <span class="brand-mark">学</span>
        <span class="brand-name">学材 <small>LEARNING SPACE</small></span>
      </router-link>
      <nav class="header-links" aria-label="主导航">
        <router-link v-if="session" to="/materials">我的资料</router-link>
        <router-link v-if="session" to="/ai-tasks">整理任务</router-link>
        <router-link v-if="session" to="/notifications">通知</router-link>
        <router-link v-if="session" to="/cards">知识卡片</router-link>
        <router-link v-if="session" to="/me">我的信息</router-link>
        <router-link v-else to="/login">登录</router-link>
        <router-link v-if="!session" to="/register" class="nav-register">创建账号</router-link>
      </nav>
    </header>

    <main class="main-area">
      <router-view />
    </main>

    <footer class="site-footer">
      <span>让每一份学习资料都有迹可循</span>
      <span>个人学习资料智能整理系统</span>
    </footer>
  </div>
</template>
