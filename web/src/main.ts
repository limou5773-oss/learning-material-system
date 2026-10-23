import { createApp } from 'vue'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElTag } from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

const app = createApp(App).use(router)
for (const component of [ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElTag]) {
  app.use(component)
}
app.mount('#app')
