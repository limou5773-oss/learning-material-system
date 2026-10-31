<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiRequestError } from '../api'
import { clearSession } from '../auth'
import type { Material } from '../materials'

const router = useRouter()
const route = useRoute()
const form = reactive({ title: '', subject: '', tags: '', noteText: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  error.value = ''
  const tags = form.tags.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (!form.title.trim() || !form.noteText.trim()) { error.value = '请填写标题和正文'; return }
  if (tags.length > 10) { error.value = '最多添加 10 个标签'; return }
  loading.value = true
  try {
    const material = await api<Material>('/materials', 'POST', {
      type: 'NOTE', title: form.title.trim(), subject: form.subject.trim(), tags, noteText: form.noteText,
    })
    await router.replace(`/materials/${material.id}`)
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession()
      await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else {
      error.value = cause instanceof Error ? cause.message : '保存失败'
    }
  } finally { loading.value = false }
}
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" to="/materials">← 返回资料</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">QUICK CAPTURE</span><h1>新建文字笔记</h1><p>写下课堂要点，稍后在两端继续查看。</p></div></div>
    <section class="materials-panel form-panel">
      <el-alert v-if="error" class="form-alert" :title="error" type="error" show-icon :closable="false" role="alert" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="255" placeholder="例如：线性代数第三章要点" />
        </el-form-item>
        <div class="form-row-2">
          <el-form-item label="学科">
            <el-input v-model="form.subject" maxlength="64" placeholder="可选，例如：数学" />
          </el-form-item>
          <el-form-item label="标签">
            <el-input v-model="form.tags" placeholder="可选，多个标签用逗号隔开" />
          </el-form-item>
        </div>
        <el-form-item label="笔记正文" required>
          <el-input v-model="form.noteText" type="textarea" :rows="13" maxlength="20000" placeholder="写下你的学习内容…" />
        </el-form-item>
        <div class="editor-actions"><span>{{ form.noteText.length }} / 20000 字</span><el-button type="primary" native-type="submit" :loading="loading">保存笔记</el-button></div>
      </el-form>
    </section>
  </div>
</template>

<style scoped>
.form-alert { margin-bottom: 18px; }
.editor-actions { justify-content: space-between; }
</style>
