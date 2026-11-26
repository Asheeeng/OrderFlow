import { createApp } from 'vue'
import {
  ElButton, ElDialog, ElInput, ElInputNumber, ElOption, ElSelect, ElTag, ElSwitch,
} from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'

const app = createApp(App)
for (const component of [ElButton, ElDialog, ElInput, ElInputNumber, ElOption, ElSelect, ElTag, ElSwitch]) {
  app.use(component)
}
app.mount('#app')
