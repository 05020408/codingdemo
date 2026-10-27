<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { message, request } from "../api";
interface CleanupJob {
  id: string;
  type: string;
  status: string;
  error: { message: string } | null;
}
const jobs = ref<CleanupJob[]>([]),
  error = ref(""),
  retrying = ref("");
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false;
const keys = new Map<string, string>();
async function load() {
  clearTimeout(timer);
  try {
    const result = await request<CleanupJob[]>("/api/cleanup-jobs");
    if (!disposed) {
      jobs.value = result;
      error.value = "";
    }
  } catch (cause) {
    if (!disposed) error.value = "清理状态暂不可用：" + message(cause);
  } finally {
    if (!disposed)
      timer = setTimeout(
        load,
        jobs.value.some((j) => j.status !== "FAILED") ? 2500 : 15000,
      );
  }
}
async function retry(job: CleanupJob) {
  if (!keys.has(job.id)) keys.set(job.id, crypto.randomUUID());
  retrying.value = job.id;
  try {
    await request(
      `/api/jobs/${job.id}/retries`,
      "POST",
      undefined,
      keys.get(job.id),
    );
    keys.delete(job.id);
    await load();
  } catch (cause) {
    error.value = message(cause);
  } finally {
    retrying.value = "";
  }
}
onMounted(() => {
  void load();
  window.addEventListener("review-cleanup-changed", load);
});
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(timer);
  window.removeEventListener("review-cleanup-changed", load);
});
</script>
<template>
  <aside
    v-if="jobs.length || error"
    class="cleanup-status"
    aria-label="后台清理状态"
  >
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-for="job in jobs" :key="job.id">
      <span
        >{{ job.type === "COURSE_PURGE" ? "课程" : "课件" }}文件{{
          job.status === "FAILED" ? "清理失败" : "正在后台清理"
        }}。{{
          job.status === "FAILED"
            ? "文件访问已停止，可重新提交清理。"
            : "完成后此提示会自动消失。"
        }}</span
      ><button
        v-if="job.status === 'FAILED'"
        class="text-button"
        :disabled="!!retrying"
        @click="retry(job)"
      >
        {{ retrying === job.id ? "提交中…" : "重试清理" }}
      </button>
    </p>
  </aside>
</template>
<style scoped>
.cleanup-status {
  margin: 20px 36px 0;
  padding: 12px 16px;
  border: 1px solid #dfd4b7;
  background: #faf6e9;
  border-radius: 8px;
  color: #766237;
  font-size: 12px;
}
.cleanup-status p {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.cleanup-status .text-button {
  font-size: 12px;
}
@media (max-width: 750px) {
  .cleanup-status {
    margin: 16px 16px 0;
  }
}
</style>
