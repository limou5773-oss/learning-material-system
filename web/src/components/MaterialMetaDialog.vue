<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '../api'
import type { Material } from '../materials'

const props = defineProps<{ material: Material | null; modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; updated: [Material] }>()

const form = reactive({ title: '', subject: '', tags: '' })
const saving = ref(false)
const error = ref('')

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

watch(() => props.modelValue, value => {
  if (!value || !props.material) return
  form.title = props.material.title
  form.subject = props.material.subject || ''
  form.tags = props.material.tags.map(tag => tag.name).join('，')
  error.value = ''
})

function handleBeforeClose(done: () => void) {
  if (!saving.value) done()
}

function close() {
  if (!saving.value) emit('update:modelValue', false)
}

async function submit() {
  if (!props.material || saving.value) return
  const title = form.title.trim()
  const tags = form.tags.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (!title) { error.value = '标题不能为空'; return }
  if (tags.length > 10) { error.value = '最多添加 10 个标签'; return }
  saving.value = true
  error.value = ''
  try {
    const updated = await api<Material>(`/materials/${props.material.id}`, 'PATCH', {
      version: props.material.version,
      title,
      subject: form.subject.trim(),
      tags,
    })
    emit('updated', updated)
    emit('update:modelValue', false)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败'
  } finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="dialogVisible" title="编辑资料信息" width="520px" align-center append-to-body
    :close-on-click-modal="false" :show-close="!saving" :before-close="handleBeforeClose">
    <p class="meta-hint">这里只修改标题、学科和标签，不会影响已上传的文件内容。</p>
    <el-alert v-if="error" class="meta-alert" :title="error" type="error" show-icon :closable="false" role="alert" />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="255" />
      </el-form-item>
      <el-form-item label="学科">
        <el-input v-model="form.subject" maxlength="64" placeholder="可选，例如：数学" />
      </el-form-item>
      <el-form-item label="标签">
        <el-input v-model="form.tags" placeholder="可选，多个标签用逗号隔开，最多 10 个" />
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="meta-actions">
        <el-button :disabled="saving" @click="close">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.meta-hint { margin: 0 0 16px; color: var(--muted); font-size: 13px; line-height: 1.7; }
.meta-alert { margin-bottom: 14px; }
.meta-actions { display: flex; justify-content: flex-end; gap: 10px; }
</style>
