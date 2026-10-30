<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ApiError, message, request } from "../api";
import type { Material, Page } from "../api";
import { formatDate } from "../coursePresentation";
import AppIcon from "./AppIcon.vue";
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
const dragging = ref(false);
const stages = ["TEXT", "PREVIEW", "INDEX"] as const;
const validFile = computed(() => !!file.value && file.value.name.toLowerCase().endsWith(".pptx") && file.value.size <= 20 * 1024 * 1024);
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  generation = 0,
  delay = 2000,
  lastState = "";
const operations = new Map<string, { key: string; version: number }>();
function processing(item: Material) {
  return ["TEXT", "PREVIEW", "INDEX"].some((stage) =>
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
        NOT_APPLICABLE: "等待文字提取",
      } as Record<string, string>
    )[status] ?? "尚未就绪"
  );
}
function stageName(stage: string) {
  return stage === "TEXT" ? "文字提取" : stage === "INDEX" ? "重点定位" : "页面预览";
}
function availability(item: Material) {
  if (item.capabilities.canReadPreview) {
    if (item.stages.PREVIEW?.status === "FAILED") return { text: "可以阅读上次预览", tone: "warning" };
    if (!item.capabilities.canLocate) return { text: item.stages.INDEX?.status === "FAILED" ? "可以阅读，定位暂不可用" : "可以阅读，重点定位准备中", tone: "partial" };
    return { text: processing(item) ? "可以阅读，内容正在更新" : "阅读与定位已就绪", tone: "ready" };
  }
  if (item.stages.PREVIEW?.status === "FAILED") return { text: "页面预览生成失败", tone: "failed" };
  if (item.stages.PREVIEW?.status === "CANCELLED") return { text: "页面预览已取消", tone: "warning" };
  return { text: item.stages.PREVIEW?.status === "RUNNING" ? "正在生成页面预览" : "等待生成页面预览", tone: "processing" };
}
function hasStageError(item: Material) {
  return stages.some(stage => !!item.stages[stage]?.error);
}
function size(bytes: number) {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KiB";
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
  chooseFile((event.target as HTMLInputElement).files?.[0] ?? null);
}
function chooseFile(selected: File | null) {
  file.value = selected;
  uploadKey.value = crypto.randomUUID();
  uploadError.value = "";
  if (file.value && !file.value.name.toLowerCase().endsWith(".pptx"))
    uploadError.value = "请选择 .pptx 文件；旧版 .ppt 需先转换。";
  else if (file.value && file.value.size > 20 * 1024 * 1024)
    uploadError.value = "课件不能超过 20 MiB。";
}
function drop(event: DragEvent) {
  dragging.value = false;
  if (uploading.value) return;
  const files = event.dataTransfer?.files;
  if (!files?.length) return;
  if (files.length !== 1) {
    clearFile();
    uploadError.value = "每次请上传一份 PPTX 课件。";
    return;
  }
  if (fileInput.value) fileInput.value.value = "";
  chooseFile(files[0] ?? null);
}
function leaveDrop(event: DragEvent) {
  if (!(event.relatedTarget instanceof Node) || !(event.currentTarget as HTMLElement).contains(event.relatedTarget)) dragging.value = false;
}
function clearFile() {
  chooseFile(null);
  if (fileInput.value) fileInput.value.value = "";
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
    <form :class="['upload-panel', { 'is-dragging': dragging && !uploading }]" @submit.prevent="upload"
      @dragover.prevent @dragenter.prevent="dragging = true" @dragleave="leaveDrop" @drop.prevent="drop">
      <div class="upload-introduction">
        <span class="upload-symbol" aria-hidden="true"><AppIcon name="upload" :size="23" /></span>
        <div><h2>把课件放进这门课</h2>
          <p>选择或拖入一份 PPTX，自动整理成可阅读、可定位的页面。</p>
          <small>每份最大 20 MiB · 最多 300 页 · 保留原始页码</small>
        </div>
      </div>
      <div class="upload-controls">
        <input
          id="ppt-file"
          ref="fileInput"
          class="sr-only"
          type="file"
          tabindex="-1"
          aria-label="选择 PPTX 课件"
          accept=".pptx"
          :disabled="uploading"
          @change="select"
        />
        <button type="button" class="button secondary" :disabled="uploading" @click="fileInput?.click()">
          <AppIcon name="add" :size="18" />{{ file ? "重新选择" : "选择课件" }}
        </button>
        <button
          class="button primary"
          :disabled="!validFile || uploading"
          type="submit"
        >
          <AppIcon v-if="!uploading" name="upload" :size="17" /><span v-else class="status-spinner" aria-hidden="true"></span>
          {{ uploading ? "正在上传…" : "上传课件" }}
        </button>
      </div>
      <p v-if="file" class="selected-file">
        <AppIcon name="file" :size="17" /><span>{{ file.name }}<small>{{ size(file.size) }}</small></span>
        <button type="button" class="text-button" :disabled="uploading" @click="clearFile">移除</button>
      </p>
      <p v-if="uploading" role="status" class="muted">
        正在校验并上传，请保持当前页面打开…
      </p>
      <p v-if="uploadError" class="error" role="alert">{{ uploadError }}</p>
    </form>
    <p v-if="notice" class="notice" role="status"><AppIcon name="check" :size="17" />{{ notice }}</p>
    <div class="material-list-heading">
      <h2 id="materials-title">课程课件<span v-if="!loading && !error" class="count">{{ total }} 份</span></h2>
      <span>按上传时间排序</span>
    </div>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button class="text-button" @click="load()">重新加载</button>
    </p>
    <div v-if="loading" class="material-skeletons" role="status" aria-label="正在加载课件">
      <span class="sr-only">正在读取课件目录…</span>
      <div v-for="n in 2" :key="n" class="material-skeleton" aria-hidden="true"><i></i><div><i></i><i></i></div></div>
    </div>
    <div v-else-if="total === 0 && !error" class="material-empty">
      <span class="empty-symbol" aria-hidden="true"><AppIcon name="file" :size="29" /></span>
      <h3>这门课还没有课件</h3>
      <p>在上方选择课件，上传后就能在这里打开阅读。</p>
    </div>
    <div v-else-if="materials.length" class="material-list">
      <article v-for="item in materials" :key="item.id" class="material-row">
        <div class="material-file-icon" aria-hidden="true">
          <AppIcon name="file" :size="27" /><span>PPTX</span>
        </div>
        <div class="material-info">
          <h3>{{ item.displayName }}</h3>
          <p class="material-meta">
            {{ size(item.sizeBytes) }} · {{ item.slideCount ? item.slideCount + " 页" : "页数待解析" }} · {{ formatDate(item.createdAt) }} 上传
          </p>
          <p :class="['material-availability', availability(item).tone]" role="status"><span aria-hidden="true" class="availability-dot"></span>{{ availability(item).text }}</p>
          <details class="material-processing" :open="hasStageError(item)">
            <summary>处理详情</summary>
          <div class="stage-row">
            <span
              v-for="stage in stages"
              :key="stage"
              class="stage-badge"
              :class="item.stages[stage]?.status.toLowerCase()"
              >{{
                stageName(stage)
              }}
              · {{ label(item.stages[stage]?.status ?? "") }}</span
            >
          </div>
          <template v-for="stage in stages" :key="stage"
            ><p v-if="item.stages[stage]?.error" class="stage-error">
              {{ item.stages[stage]!.error!.message }}
              <button
                v-if="item.stages[stage]?.error?.retryable"
                class="text-button"
                :disabled="!!retrying"
                @click="retry(item, stage)"
              >
                {{
                  retrying === item.id + stage
                    ? "提交中…"
                    : "重试" + stageName(stage)
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
          </details>
        </div>
        <div class="material-actions">
          <RouterLink
            v-if="item.capabilities.canReadPreview"
            class="button primary"
            :to="`/materials/${item.id}/read`"
            >打开课件<AppIcon name="arrow" :size="17" /></RouterLink
          ><span v-else-if="processing(item)" class="muted" role="status"
            >预览准备中…</span
          ><a
            v-if="item.capabilities.canDownload"
            class="text-button"
            :href="item.originalDownloadUrl + '?disposition=attachment'"
            >下载原件</a
          ><button class="text-button delete-link" :aria-label="`删除课件：${item.displayName}`" @click="askDelete(item)">
            删除课件
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
