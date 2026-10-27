import { defineStore } from "pinia";
import { ref } from "vue";
import { ApiError, refreshCsrf, request } from "../api";
import type { User } from "../api";
export const useAuth = defineStore("auth", () => {
  const user = ref<User | null>(null);
  const restored = ref(false);
  const connectionError = ref("");
  async function restore() {
    if (restored.value) return;
    try {
      user.value = await request<User>("/api/auth/me");
      connectionError.value = "";
      restored.value = true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        user.value = null;
        restored.value = true;
      } else {
        connectionError.value =
          error instanceof Error ? error.message : "服务暂时不可用";
      }
    }
  }
  async function login(username: string, password: string) {
    user.value = await request<User>("/api/auth/login", "POST", {
      username,
      password,
    });
    restored.value = true;
    connectionError.value = "";
    await refreshCsrf();
  }
  async function logout() {
    await request("/api/auth/logout", "POST");
    user.value = null;
    await refreshCsrf();
  }
  return { user, restored, connectionError, restore, login, logout };
});
