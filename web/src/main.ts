import { createApp, h } from 'vue'
import {
  ElAlert, ElButton, ElCard, ElCheckbox, ElConfigProvider, ElDialog, ElEmpty,
  ElForm, ElFormItem, ElInput, ElLink, ElLoading, ElOption, ElPagination,
  ElSelect, ElSkeleton, ElTag, ElTooltip,
} from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 主题变量完整引入，组件按需注册，避免整包体积。
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

const components = [
  ElAlert, ElButton, ElCard, ElCheckbox, ElConfigProvider, ElDialog, ElEmpty,
  ElForm, ElFormItem, ElInput, ElLink, ElOption, ElPagination, ElSelect,
  ElSkeleton, ElTag, ElTooltip,
]

// 语言包在根节点注入，App.vue 保持与单元测试共用的纯净结构。
const Root = { render: () => h(ElConfigProvider, { locale: zhCn }, { default: () => h(App) }) }

const app = createApp(Root).use(router)
for (const component of components) app.use(component)
// v-loading 指令与 $loading 服务
app.use(ElLoading)

app.mount('#app')
