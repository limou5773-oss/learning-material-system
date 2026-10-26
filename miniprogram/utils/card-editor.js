function cardForm(card) {
  return { title: card && card.title || '', content: card && card.content || '', summary: card && card.summary || '',
    subject: card && card.subject || '', tagsText: card ? card.tags.join('，') : '' }
}
function cardPayload(form, originalTags) {
  const title = form.title.trim(), subject = form.subject.trim(), summary = form.summary.trim()
  const tags = originalTags && form.tagsText === originalTags.join('，')
    ? [...originalTags] : form.tagsText.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  if (!title || !form.content.trim()) throw new Error('请填写标题和正文')
  if (title.length > 255) throw new Error('标题最多 255 字')
  if (form.content.length > 8000) throw new Error('正文最多 8000 字')
  if (summary.length > 2000) throw new Error('摘要最多 2000 字')
  if (subject.length > 64) throw new Error('学科最多 64 字')
  if (tags.length > 10) throw new Error('最多添加 10 个标签')
  if (tags.some(tag => tag.length > 64)) throw new Error('每个标签最多 64 字')
  if (new Set(tags.map(tag => tag.toLowerCase())).size !== tags.length) throw new Error('标签不能重复')
  return { title, content: form.content, summary, subject, tags }
}
function draftKey(userId, id) { return `lms.cardDraft.v1.${encodeURIComponent(userId)}.${encodeURIComponent(id || 'new')}` }
function readDraft(value, editing) {
  if (!value || !Number.isInteger(value.version) || (editing ? value.version < 1 : value.version !== 0)
      || !value.form || !['title', 'content', 'summary', 'subject', 'tagsText'].every(key => typeof value.form[key] === 'string')) return null
  return { version: value.version, form: { title: value.form.title, content: value.form.content,
    summary: value.form.summary, subject: value.form.subject, tagsText: value.form.tagsText } }
}
function cardListPath(filters, page) {
  const keyword = filters.keyword.trim(), subject = filters.subject.trim(), tag = filters.tag.trim()
  if (keyword.length > 100 || subject.length > 64 || tag.length > 64) throw new Error('关键词最多 100 字，学科和标签最多 64 字')
  const params = { keyword, subject, tag, favoriteOnly: String(filters.favoriteOnly), page: String(page), pageSize: '20' }
  return `/cards?${Object.keys(params).map(key => `${key}=${encodeURIComponent(params[key])}`).join('&')}`
}
module.exports = { cardForm, cardPayload, draftKey, readDraft, cardListPath }
