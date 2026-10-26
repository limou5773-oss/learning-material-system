import { createRouter, createWebHistory } from 'vue-router'
import { session } from './auth'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import MeView from './views/MeView.vue'
import MaterialsView from './views/MaterialsView.vue'
import NoteCreateView from './views/NoteCreateView.vue'
import MaterialDetailView from './views/MaterialDetailView.vue'
import NoteEditView from './views/NoteEditView.vue'
import ImageUploadView from './views/ImageUploadView.vue'
import PdfUploadView from './views/PdfUploadView.vue'
import TaskCreateView from './views/TaskCreateView.vue'
import TasksView from './views/TasksView.vue'
import TaskDetailView from './views/TaskDetailView.vue'
import TaskConfirmView from './views/TaskConfirmView.vue'
import CardsView from './views/CardsView.vue'
import CardDetailView from './views/CardDetailView.vue'
import CardEditorRoute from './views/CardEditorRoute.vue'
import CardOrganizationRoute from './views/CardOrganizationRoute.vue'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', redirect: '/me' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/register', name: 'register', component: RegisterView },
    { path: '/me', name: 'me', component: MeView, meta: { requiresAuth: true } },
    { path: '/materials', name: 'materials', component: MaterialsView, meta: { requiresAuth: true } },
    { path: '/materials/new', name: 'note-create', component: NoteCreateView, meta: { requiresAuth: true } },
    { path: '/materials/image/new', name: 'image-upload', component: ImageUploadView, meta: { requiresAuth: true } },
    { path: '/materials/pdf/new', name: 'pdf-upload', component: PdfUploadView, meta: { requiresAuth: true } },
    { path: '/materials/:id/organize', name: 'task-create', component: TaskCreateView, meta: { requiresAuth: true } },
    { path: '/materials/:id/edit', name: 'note-edit', component: NoteEditView, meta: { requiresAuth: true } },
    { path: '/materials/:id', name: 'material-detail', component: MaterialDetailView, meta: { requiresAuth: true } },
    { path: '/ai-tasks', name: 'tasks', component: TasksView, meta: { requiresAuth: true } },
    { path: '/ai-tasks/:id/confirm', name: 'task-confirm', component: TaskConfirmView, meta: { requiresAuth: true } },
    { path: '/ai-tasks/:id', name: 'task-detail', component: TaskDetailView, meta: { requiresAuth: true } },
    { path: '/cards', name: 'cards', component: CardsView, meta: { requiresAuth: true } },
    { path: '/cards/new', name: 'card-create', component: CardEditorRoute, meta: { requiresAuth: true } },
    { path: '/cards/organize', name: 'card-organize', component: CardOrganizationRoute, meta: { requiresAuth: true } },
    { path: '/cards/:id/edit', name: 'card-edit', component: CardEditorRoute, meta: { requiresAuth: true } },
    { path: '/cards/:id', name: 'card-detail', component: CardDetailView, meta: { requiresAuth: true } },
  ],
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !session.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
