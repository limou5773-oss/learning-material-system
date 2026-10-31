// 这些用例使用Vue自定义宿主树检查业务交互；真实Element Plus的遮罩/焦点在浏览器验收。
// 适配器保留Dialog的开关、插槽和beforeClose契约，不渲染已关闭的内容。
module.exports = function elementPlus(vue) {
  const ElDialog = vue.defineComponent({
    props: ['modelValue', 'title', 'beforeClose', 'showClose'],
    emits: ['update:modelValue'],
    setup(props, { slots, emit }) {
      const close = () => props.beforeClose ? props.beforeClose(() => emit('update:modelValue', false)) : emit('update:modelValue', false)
      return () => props.modelValue ? vue.h('section', { role: 'dialog', 'aria-modal': 'true' }, [
        vue.h('h2', props.title), props.showClose === false ? null : vue.h('button', { onClick: close, 'aria-label': '关闭弹窗' }, '关闭弹窗'),
        slots.default?.(), slots.footer?.(),
      ]) : null
    },
  })
  return { ElDialog }
}
