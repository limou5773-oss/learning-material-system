import { createRouter, createWebHistory } from 'vue-router'
import { session } from './auth'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import MeView from './views/MeView.vue'
import MaterialsView from './views/MaterialsView.vue'
import NoteCreateView from './views/NoteCreateView.vue'
import MaterialDetailRoute from './views/MaterialDetailRoute.vue'
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
import KnowledgeGraphRoute from './views/KnowledgeGraphRoute.vue'
import NotificationsRoute from './views/NotificationsRoute.vue'
import AdminUsersRoute from './views/AdminUsersRoute.vue'
import AdminReviewsRoute from './views/AdminReviewsRoute.vue'
import ForbiddenView from './views/ForbiddenView.vue'

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
    { path: '/materials/:id', name: 'material-detail', component: MaterialDetailRoute, meta: { requiresAuth: true } },
    { path: '/ai-tasks', name: 'tasks', component: TasksView, meta: { requiresAuth: true } },
    { path: '/notifications', name: 'notifications', component: NotificationsRoute, meta: { requiresAuth: true } },
    { path: '/admin/users', name: 'admin-users', component: AdminUsersRoute, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/reviews', name: 'admin-reviews', component: AdminReviewsRoute, meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/admin/statistics', name: 'admin-statistics', component: () => import('./views/AdminStatisticsRoute.vue'), meta: { requiresAuth: true, requiresRole: 'ADMIN' } },
    { path: '/forbidden', name: 'forbidden', component: ForbiddenView, meta: { requiresAuth: true } },
    { path: '/ai-tasks/:id/confirm', name: 'task-confirm', component: TaskConfirmView, meta: { requiresAuth: true } },
    { path: '/ai-tasks/:id', name: 'task-detail', component: TaskDetailView, meta: { requiresAuth: true } },
    { path: '/cards', name: 'cards', component: CardsView, meta: { requiresAuth: true } },
    { path: '/knowledge-graph', name: 'knowledge-graph', component: KnowledgeGraphRoute, meta: { requiresAuth: true } },
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
  if (to.meta.requiresRole && session.value?.user.role !== to.meta.requiresRole) {
    return { name: 'forbidden' }
  }
})

export default router
