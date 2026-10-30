import { createApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import Login from "./views/Login.vue";
import Courses from "./views/Courses.vue";
import CourseDetail from "./views/CourseDetail.vue";
import MaterialReader from "./views/MaterialReader.vue";
import { useAuth } from "./stores/auth";
import "./style.css";
import "./course-workspace.css";
import "./study-workspace.css";

const app = createApp(App);
app.use(createPinia());
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition;
    if (to.path !== from.path) return { top: 0 };
    return false;
  },
  routes: [
    { path: "/login", component: Login },
    { path: "/", component: Courses },
    { path: "/courses/:id", component: CourseDetail },
    { path: "/materials/:id/read", component: MaterialReader },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
router.beforeEach(async (to) => {
  const auth = useAuth();
  await auth.restore();
  if (!auth.user && to.path !== "/login") return "/login";
  if (auth.user && to.path === "/login") return "/";
});
app.use(router);
window.addEventListener("review-session-expired", () => {
  useAuth().user = null;
  void router.replace("/login");
});
app.mount("#app");
