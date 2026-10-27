<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ApiError, message, request } from "../api";
import type { Material, Page } from "../api";
const props = defineProps<{ courseId: string }>();
const materials = ref<Material[]>([]),
  total = ref(0),
  page = ref(1),
  loading = ref(true),
  error = ref(""),
  notice = ref("");
const file = ref<File | null>(null),
  fileInput = ref<HTMLInputElement>(),
  uploading = ref(false),
  uploadError = ref(""),
  uploadKey = ref("");
const removing = ref<Material | null>(null),
  confirmation = ref(""),
  deleteDialog = ref<HTMLDialogElement>(),
  deleting = ref(false),
  deleteError = ref("");
const retrying = ref("");
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  generation = 0,
  delay = 2000,
  lastState = "";
const operations = new Map<string, { key: string; version: number }>();
function processing(item: Material) {
  return ["TEXT", "PREVIEW"].some((stage) =>
    ["QUEUED", "RUNNING"].includes(item.stages[stage]?.status ?? ""),
  );
}
function label(status: string) {
  return (
    (
      {
        QUEUED: "等待处理",
        RUNNING: "处理中",
        SUCCEEDED: "已完成",
        FAILED: "处理失败",
        CANCELLED: "已取消",
      } as Record<string, string>
    )[status] ?? status
  );
}
function size(bytes: number) {
  return (bytes / 1024 / 1024).toFixed(1) + " MiB";
}
async function load(target = page.value, quiet = false) {
  clearTimeout(timer);
  const current = ++generation;
  if (!quiet) loading.value = true;
  try {
    const data = await request<Page<Material>>(
      `/api/courses/${props.courseId}/materials?page=${target}&size=10`,
    );
    if (disposed || current !== generation) return;
    if (target > 1 && data.items.length === 0) {
      await load(target - 1);
      return;
    }
    materials.value = data.items;
    total.value = data.total;
    page.value = target;
    error.value = "";
    const state = JSON.stringify(
      data.items.map((item) => [item.id, item.stages]),
    );
    delay = state === lastState ? Math.min(delay + 1000, 10000) : 2000;
    lastState = state;
  } catch (cause) {
    if (!disposed && current === generation) error.value = message(cause);
  } finally {
    if (!disposed && current === generation) {
      loading.value = false;
      if (materials.value.some(processing))
        timer = setTimeout(() => load(page.value, true), delay);
    }
  }
}
function select(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
  uploadKey.value = crypto.randomUUID();
  uploadError.value = "";
  if (file.value && !file.value.name.toLowerCase().endsWith(".pptx"))
    uploadError.value = "请选择 .pptx 文件；旧版 .ppt 需先转换。";
  else if (file.value && file.value.size > 20 * 1024 * 1024)
    uploadError.value = "课件不能超过 20 MiB。";
}
async function upload() {
  if (!file.value) return;
  if (
    !file.value.name.toLowerCase().endsWith(".pptx") ||
    file.value.size > 20 * 1024 * 1024
  )
    return;
  uploading.value = true;
  uploadError.value = "";
  const data = new FormData();
  data.append("file", file.value);
  try {
    await request(
      `/api/courses/${props.courseId}/materials`,
      "POST",
      data,
      uploadKey.value,
    );
    file.value = null;
    if (fileInput.value) fileInput.value.value = "";
    notice.value = "课件已上传，正在后台准备文字和预览。";
    await load(1);
  } catch (cause) {
    uploadError.value = message(cause);
  } finally {
    uploading.value = false;
  }
}
async function retry(item: Material, stage: string) {
  const operation = item.id + stage;
  if (!operations.has(operation))
    operations.set(operation, {
      key: crypto.randomUUID(),
      version: item.version,
    });
  const saved = operations.get(operation)!;
  retrying.value = operation;
  error.value = "";
  try {
    await request(
      `/api/materials/${item.id}/processing-retries`,
      "POST",
      { stage, expectedVersion: saved.version },
      saved.key,
    );
    operations.delete(operation);
    notice.value = "已提交重新处理。";
    await load(page.value, true);
  } catch (cause) {
    error.value = message(cause);
    if (cause instanceof ApiError && cause.status === 409) {
      operations.delete(operation);
      await load(page.value, true);
      error.value = message(cause);
    }
  } finally {
    retrying.value = "";
  }
}
function askDelete(item: Material) {
  removing.value = item;
  confirmation.value = "";
  deleteError.value = "";
  deleteDialog.value?.showModal();
}
async function remove() {
  const item = removing.value;
  if (!item || confirmation.value !== item.displayName) return;
  const operation = item.id + "delete";
  if (!operations.has(operation))
    operations.set(operation, {
      key: crypto.randomUUID(),
      version: item.version,
    });
  const saved = operations.get(operation)!;
  deleting.value = true;
  try {
    await request(
      `/api/materials/${item.id}?expectedVersion=${saved.version}`,
      "DELETE",
      undefined,
      saved.key,
    );
    operations.delete(operation);
    deleteDialog.value?.close();
    notice.value = "课件已移除，原件和预览将在后台清理。";
    await load(page.value, true);
  } catch (cause) {
    deleteError.value = message(cause);
  } finally {
    deleting.value = false;
  }
}
onMounted(() => load());
watch(
  () => props.courseId,
  () => {
    materials.value = [];
    void load(1);
  },
);
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  clearTimeout(timer);
});
</script>
<template>
  <section class="materials-section" aria-labelledby="materials-title">
    <div class="section-line">
      <h2 id="materials-title">
        课程课件 <span class="count">{{ total }}</span>
      </h2>
      <span>PPTX · 原始页码</span>
    </div>
    <form class="upload-panel" @submit.prevent="upload">
      <div>
        <span class="small-label">把课件放进这门课</span>
        <p>上传后自动生成可阅读页面，保留隐藏页和原始页码。</p>
        <small class="muted"
          >支持 .pptx，单份最大 20 MiB、300
          页。动画与视频请下载原件查看。</small
        >
      </div>
      <div class="upload-controls">
        <label class="file-label" for="ppt-file">选择课件</label
        ><input
          id="ppt-file"
          ref="fileInput"
          type="file"
          accept=".pptx"
          :disabled="uploading"
          @change="select"
        /><button
          class="button primary"
          :disabled="!file || uploading"
          type="submit"
        >
          {{ uploading ? "正在上传…" : "上传课件" }}
        </button>
      </div>
      <p v-if="file" class="selected-file">
        已选择：{{ file.name }} · {{ size(file.size) }}
      </p>
      <p v-if="uploading" role="status" class="muted">
        正在校验并上传，请保持当前页面打开…
      </p>
      <p v-if="uploadError" class="error" role="alert">{{ uploadError }}</p>
    </form>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button class="text-button" @click="load()">重新加载</button>
    </p>
    <div v-if="loading" class="state-panel" role="status">
      正在读取课件目录…
    </div>
    <div v-else-if="total === 0 && !error" class="material-empty">
      <span aria-hidden="true">▤</span>
      <h3>这门课还没有课件</h3>
      <p>选择一份 PPTX，开始整理课程内容。</p>
    </div>
    <div v-else class="material-list">
      <article v-for="item in materials" :key="item.id" class="material-row">
        <div class="material-file-icon" aria-hidden="true">
          PPT<span>{{ item.slideCount }} 页</span>
        </div>
        <div class="material-info">
          <h3>{{ item.displayName }}</h3>
          <p class="material-meta">
            {{ size(item.sizeBytes) }} · {{ item.slideCount }} 页
          </p>
          <div class="stage-row">
            <span
              v-for="stage in ['TEXT', 'PREVIEW']"
              :key="stage"
              class="stage-badge"
              :class="item.stages[stage]?.status.toLowerCase()"
              >{{ stage === "TEXT" ? "文字" : "预览" }} ·
              {{ label(item.stages[stage]?.status ?? "") }}</span
            >
          </div>
          <template v-for="stage in ['TEXT', 'PREVIEW']" :key="stage"
            ><p v-if="item.stages[stage]?.error" class="stage-error">
              {{ item.stages[stage]!.error!.message }}
              <button
                class="text-button"
                :disabled="!!retrying"
                @click="retry(item, stage)"
              >
                {{
                  retrying === item.id + stage
                    ? "提交中…"
                    : "重试" + (stage === "TEXT" ? "文字提取" : "预览")
                }}
              </button>
            </p></template
          >
          <p
            v-if="
              item.capabilities.canReadPreview &&
              item.stages.PREVIEW?.status !== 'SUCCEEDED'
            "
            class="muted"
          >
            上次成功的预览仍可阅读。
          </p>
        </div>
        <div class="material-actions">
          <RouterLink
            v-if="item.capabilities.canReadPreview"
            class="button primary"
            :to="`/materials/${item.id}/read`"
            >阅读课件 →</RouterLink
          ><span v-else-if="processing(item)" class="muted" role="status"
            >预览准备中…</span
          ><a
            class="text-button"
            :href="item.originalDownloadUrl + '?disposition=attachment'"
            >下载原件</a
          ><button class="text-button delete-link" @click="askDelete(item)">
            删除
          </button>
        </div>
      </article>
    </div>
    <nav v-if="total > 10" class="pagination" aria-label="课件分页">
      <button
        class="button secondary"
        :disabled="page === 1 || loading"
        @click="load(page - 1)"
      >
        上一页</button
      ><span>{{ page }} / {{ Math.ceil(total / 10) }}</span
      ><button
        class="button secondary"
        :disabled="page * 10 >= total || loading"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </nav>
    <dialog
      ref="deleteDialog"
      class="dialog"
      aria-labelledby="material-delete-title"
      @cancel="deleting && $event.preventDefault()"
    >
      <form @submit.prevent="remove">
        <h2 id="material-delete-title">删除这份课件？</h2>
        <p class="muted">
          课件原件、预览和阅读位置将被清理。请输入“{{
            removing?.displayName
          }}”确认，此操作无法撤销。
        </p>
        <label for="material-delete-name">课件文件名</label
        ><input
          id="material-delete-name"
          v-model="confirmation"
          autocomplete="off"
          :disabled="deleting"
        />
        <p v-if="deleteError" class="error" role="alert">
          {{ deleteError }} 请取消并刷新课件列表后重试。
        </p>
        <div class="dialog-actions">
          <button
            type="button"
            class="button secondary"
            :disabled="deleting"
            @click="deleteDialog?.close()"
          >
            取消</button
          ><button
            class="button danger"
            :disabled="deleting || confirmation !== removing?.displayName"
          >
            {{ deleting ? "正在删除…" : "确认删除课件" }}
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>
