<script setup lang="ts">
import { onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiRequestError, uploadFile } from '../api'
import { clearSession } from '../auth'
import type { Material } from '../materials'
import UiIcon from '../components/UiIcon.vue'

const router = useRouter()
const route = useRoute()
const form = reactive({ title: '', subject: '', tags: '' })
const selected = ref<File | null>(null)
const previewUrl = ref('')
const saving = ref(false)
const error = ref('')
const existingId = ref('')

function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}
onUnmounted(clearPreview)

function choose(event: Event) {
  clearPreview()
  selected.value = null
  existingId.value = ''
  error.value = ''
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!/\.(jpe?g|png)$/i.test(file.name) || !['image/jpeg', 'image/png'].includes(file.type)) {
    error.value = '请选择扩展名与类型一致的 JPEG 或 PNG 图片'
    input.value = ''
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    error.value = '图片不能超过 10 MiB'
    input.value = ''
    return
  }
  selected.value = file
  previewUrl.value = URL.createObjectURL(file)
  if (!form.title.trim()) form.title = file.name.replace(/\.[^.]+$/, '')
}

async function submit(allowDuplicate = false) {
  if (!selected.value || saving.value) return
  error.value = ''; existingId.value = ''
  const tags = form.tags.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (!form.title.trim()) { error.value = '请填写标题'; return }
  if (tags.length > 10) { error.value = '最多添加 10 个标签'; return }
  const body = new FormData()
  body.append('file', selected.value)
  body.append('title', form.title.trim())
  body.append('subject', form.subject.trim())
  body.append('tagsJson', JSON.stringify(tags))
  body.append('allowDuplicate', String(allowDuplicate))
  saving.value = true
  try {
    const material = await uploadFile<Material>('/materials/files', body)
    await router.replace(`/materials/${material.id}`)
  } catch (cause) {
    if (cause instanceof ApiRequestError && cause.status === 409 && cause.existingMaterialId) {
      existingId.value = cause.existingMaterialId
      error.value = '这张图片已上传过。可以打开已有资料，或确认保存副本。'
    } else if (cause instanceof ApiRequestError && cause.status === 401) {
      clearSession(); await router.replace({ name: 'login', query: { redirect: route.fullPath } })
    } else error.value = cause instanceof Error ? cause.message : '上传失败'
  } finally { saving.value = false }
}
</script>

<template>
  <div class="materials-layout narrow">
    <router-link class="back-link" to="/materials">← 返回资料</router-link>
    <div class="section-heading"><div><span class="intro-eyebrow">IMAGE CAPTURE</span><h1>上传图片资料</h1><p>支持 JPEG、PNG，单张不超过 10 MiB。</p></div></div>
    <section class="materials-panel editor-panel">
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div v-if="existingId" class="duplicate-actions"><router-link :to="`/materials/${existingId}`">查看已有资料</router-link><button type="button" :disabled="saving" @click="submit(true)">确认保存副本</button></div>
      <form @submit.prevent="submit(false)">
        <div class="upload-picker"><span class="upload-picker-icon"><UiIcon name="image" /></span><label for="image-file">选择一张学习图片</label><p>课件截图、课堂板书，都可以整理进资料库。</p><input id="image-file" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" :disabled="saving" @change="choose" /></div>
        <img v-if="previewUrl" class="upload-preview" :src="previewUrl" alt="待上传图片预览" />
        <label for="image-title">标题</label><input id="image-title" v-model="form.title" maxlength="255" placeholder="例如：课堂板书" />
        <div class="editor-meta-grid"><div><label for="image-subject">所属学科</label><input id="image-subject" v-model="form.subject" maxlength="64" placeholder="例如：计算机网络（选填）" /></div><div><label for="image-tags">标签</label><input id="image-tags" v-model="form.tags" placeholder="多个标签用逗号隔开（选填）" /></div></div>
        <div class="editor-actions"><span>{{ selected ? `${(selected.size / 1024 / 1024).toFixed(2)} MiB` : '尚未选择图片' }}</span><button type="submit" :disabled="saving || !selected">{{ saving ? '正在上传…' : '上传图片' }}</button></div>
      </form>
    </section>
  </div>
</template>
