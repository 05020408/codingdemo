<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { useRoute } from "vue-router";
import { message, request } from "../api";
import type { Material, Page, ReadingProgress, Slide } from "../api";
const route = useRoute();
const id = String(route.params.id);
const material = ref<Material | null>(null),
  slides = ref<Slide[]>([]),
  page = ref(1),
  pageInput = ref("1"),
  loading = ref(true),
  error = ref(""),
  pageError = ref(""),
  imageError = ref(false),
  imageLoading = ref(true),
  imageRevision = ref(0),
  saveError = ref(""),
  savedPage = ref<number | null>(null);
const focusContext = ref("");
const focusId = computed(() =>
  typeof route.query.focus === "string" ? route.query.focus : "",
);
const selected = computed(() => slides.value[page.value - 1]);
const thumbnails = ref<HTMLElement>();
let disposed = false,
  saveChain = Promise.resolve();
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const meta = await request<Material>(`/api/materials/${id}`);
    if (!meta.capabilities.canReadPreview)
      throw new Error("课件预览尚未就绪，请返回课程查看处理状态。");
    let items: Slide[] = [];
    for (let n = 1; n <= Math.ceil(meta.slideCount / 100); n++) {
      const data = await request<Page<Slide>>(
        `/api/materials/${id}/slides?page=${n}&size=100`,
      );
      items.push(...data.items);
    }
    const progress = await request<ReadingProgress>(
      `/api/materials/${id}/reading-progress`,
    );
    if (disposed) return;
    material.value = meta;
    slides.value = items;
    page.value = progress.lastPageNo ?? 1;
    pageInput.value = String(page.value);
    savedPage.value = progress.lastPageNo;
    imageError.value = false;
    if (focusId.value) {
      try {
        const focus = await request<{ content: string; courseId: string }>(
          `/api/focuses/${encodeURIComponent(focusId.value)}`,
        );
        if (focus.courseId === meta.courseId)
          focusContext.value = focus.content;
      } catch {
        focusContext.value = "";
      }
    }
    applyQueryPage();
  } catch (cause) {
    if (!disposed) error.value = message(cause);
  } finally {
    if (!disposed) {
      loading.value = false;
      await nextTick();
      scrollThumbnail();
    }
  }
}
function applyQueryPage() {
  if (route.query.page === undefined) return;
  const raw = route.query.page;
  if (typeof raw !== "string" || !/^\d+$/.test(raw)) {
    pageError.value = "链接中的页码无效，请使用页码框跳转。";
    return;
  }
  const target = Number(raw);
  go(target);
  if (
    Number.isInteger(target) &&
    target >= 1 &&
    target <= (material.value?.slideCount ?? 0)
  )
    retrySave();
}
watch(
  () => route.query.page,
  () => {
    if (!loading.value) applyQueryPage();
  },
);
function scrollThumbnail() {
  thumbnails.value
    ?.querySelector(`[data-page="${page.value}"]`)
    ?.scrollIntoView({ block: "nearest", inline: "nearest" });
}
function go(target: number) {
  if (
    !material.value ||
    !Number.isInteger(target) ||
    target < 1 ||
    target > material.value.slideCount
  ) {
    pageError.value = `请输入 1–${material.value?.slideCount ?? 1} 之间的整数页码。`;
    return;
  }
  pageError.value = "";
  pageInput.value = String(target);
  if (target === page.value) return;
  page.value = target;
  imageError.value = false;
  imageLoading.value = true;
  void nextTick(scrollThumbnail);
  // Serialize writes so a slower previous request cannot overwrite the newest page.
  saveChain = saveChain.then(async () => {
    try {
      await request(`/api/materials/${id}/reading-progress`, "PUT", {
        lastPageNo: target,
      });
      if (!disposed) {
        savedPage.value = target;
        saveError.value = "";
      }
    } catch (cause) {
      if (!disposed) saveError.value = "阅读位置未保存：" + message(cause);
    }
  });
}
function retrySave() {
  const target = page.value;
  saveChain = saveChain.then(async () => {
    try {
      await request(`/api/materials/${id}/reading-progress`, "PUT", {
        lastPageNo: target,
      });
      if (!disposed) {
        savedPage.value = target;
        saveError.value = "";
      }
    } catch (cause) {
      if (!disposed) saveError.value = "阅读位置未保存：" + message(cause);
    }
  });
}
function key(event: KeyboardEvent) {
  if (
    loading.value ||
    error.value ||
    (event.target instanceof Element &&
      event.target.closest(
        "input,textarea,button,select,summary,a,[contenteditable]",
      ))
  )
    return;
  if (
    event.key === "ArrowRight" &&
    page.value < (material.value?.slideCount ?? 1)
  ) {
    event.preventDefault();
    go(page.value + 1);
  }
  if (event.key === "ArrowLeft" && page.value > 1) {
    event.preventDefault();
    go(page.value - 1);
  }
}
onMounted(() => {
  void load();
  window.addEventListener("keydown", key);
});
onBeforeUnmount(() => {
  disposed = true;
  window.removeEventListener("keydown", key);
});
</script>
<template>
  <section class="reader-page">
    <div v-if="loading" class="state-panel" role="status">
      正在打开课件，恢复上次阅读位置…
    </div>
    <div v-else-if="error" class="state-panel">
      <p class="error" role="alert">{{ error }}</p>
      <RouterLink class="button secondary" to="/">返回我的课程</RouterLink
      ><button class="button secondary" @click="load">重新加载</button>
    </div>
    <template v-else-if="material"
      ><header class="reader-heading">
        <div>
          <RouterLink class="back-link" :to="`/courses/${material.courseId}`"
            >← 返回课程</RouterLink
          >
          <h1>{{ material.displayName }}</h1>
          <p>保留原始页码 · {{ material.slideCount }} 页 · 使用键盘 ← → 翻页</p>
        </div>
        <a
          class="button secondary"
          :href="material.originalDownloadUrl + '?disposition=attachment'"
          >下载原件</a
        >
      </header>
      <aside v-if="focusContext" class="reader-focus-context">
        <strong>当前复习重点</strong>
        <p>{{ focusContext }}</p>
        <RouterLink
          :to="{
            path: `/courses/${material.courseId}`,
            query: { tab: 'focus', focus: focusId },
          }"
          >← 返回重点与定位结果</RouterLink
        >
      </aside>
      <div class="reader-layout">
        <aside
          ref="thumbnails"
          class="thumbnail-list"
          aria-label="课件页面目录"
        >
          <button
            v-for="slide in slides"
            :key="slide.id"
            class="thumbnail-item"
            :class="{ selected: slide.pageNo === page }"
            :data-page="slide.pageNo"
            :aria-current="slide.pageNo === page ? 'page' : undefined"
            :aria-label="`第 ${slide.pageNo} 页${slide.isHidden ? '，隐藏页' : ''}`"
            @click="go(slide.pageNo)"
          >
            <img
              v-if="slide.thumbnailUrl"
              :src="slide.thumbnailUrl"
              alt=""
              loading="lazy"
            /><span
              >{{ slide.pageNo
              }}<small v-if="slide.isHidden">隐藏页</small></span
            >
          </button>
        </aside>
        <div class="reader-content">
          <div class="reader-toolbar">
            <button
              class="button secondary"
              :disabled="page === 1"
              @click="go(page - 1)"
            >
              ← 上一页
            </button>
            <form class="page-jump" @submit.prevent="go(Number(pageInput))">
              <label for="reader-page-number">页码</label
              ><input
                id="reader-page-number"
                v-model="pageInput"
                type="text"
                inputmode="numeric"
                aria-label="跳转页码"
              /><span>/ {{ material.slideCount }}</span
              ><button class="text-button">跳转</button>
            </form>
            <button
              class="button secondary"
              :disabled="page === material.slideCount"
              @click="go(page + 1)"
            >
              下一页 →
            </button>
          </div>
          <p v-if="pageError" class="error" role="alert">{{ pageError }}</p>
          <div class="slide-paper" :aria-busy="imageLoading">
            <div v-if="imageError" class="image-error" role="alert">
              <p>页面图片加载失败，请检查网络后重试。</p>
              <button
                class="button secondary"
                @click="
                  imageError = false;
                  imageLoading = true;
                  imageRevision++;
                "
              >
                重新加载图片
              </button>
            </div>
            <template v-else
              ><p v-if="imageLoading" class="image-loading" role="status">
                正在加载第 {{ page }} 页…
              </p>
              <img
                :key="`${page}-${imageRevision}`"
                :src="selected?.previewUrl + '?v=' + imageRevision"
                :alt="`${material.displayName}，原始第 ${page} 页`"
                @load="imageLoading = false"
                @error="
                  imageLoading = false;
                  imageError = true;
                "
            /></template>
          </div>
          <div class="reading-status">
            <span
              >原始第 {{ page }} 页
              <span v-if="selected?.isHidden" class="stage-badge"
                >隐藏页</span
              ></span
            ><span v-if="!saveError">{{
              savedPage === page
                ? "阅读位置已保存"
                : savedPage === null
                  ? "尚无保存的阅读位置"
                  : "正在保存阅读位置…"
            }}</span>
          </div>
          <p v-if="saveError" class="error" role="alert">
            {{ saveError }}
            <button class="text-button" @click="retrySave">重新保存</button>
          </p>
          <details class="slide-text">
            <summary>查看本页提取文字</summary>
            <pre>{{
              selected?.text ||
              "本页暂无可提取文字。图片、公式和图表请以课件页面为准。"
            }}</pre>
          </details>
        </div>
      </div>
    </template>
  </section>
</template>
