import { createApp } from 'vue'
import 'element-plus/dist/index.css'
import { ElLoading } from 'element-plus'
import App from './App.vue'
import router from './router'
import { installGlobalErrorHandler } from './composables/useGlobalErrorHandler'
import './assets/main.css'

const app = createApp(App)

app.directive('loading', ElLoading.directive)
app.use(router)
installGlobalErrorHandler(app)
app.mount('#app')
