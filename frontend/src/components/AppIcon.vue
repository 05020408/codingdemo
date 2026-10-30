<script setup lang="ts">
const paths = {
  courses: "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z",
  add: "M12 5v14 M5 12h14",
  search: "M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
  arrow: "M4 12h16 M14 6l6 6-6 6",
  more: "M5 12h.01 M12 12h.01 M19 12h.01",
  edit: "M14 5l5 5 M4 20l4-1 12-12a2.12 2.12 0 0 0-3-3L5 16l-1 4 M12 20h8",
  trash: "M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7",
  upload: "M12 16V3 M7 8l5-5 5 5 M4 15v6h16v-6",
  file: "M14 3H5v18h14V8l-5-5 M14 3v5h5 M8 12h8 M8 16h6",
  check: "M5 12l4 4L19 6",
  chevron: "M9 5l7 7-7 7",
  close: "M6 6l12 12 M18 6L6 18",
  refresh: "M20 7v5h-5 M4 17v-5h5 M6 6a8 8 0 0 1 14 6 M18 18a8 8 0 0 1-14-6",
  clock: "M12 8v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0",
};
withDefaults(defineProps<{ name: keyof typeof paths; size?: number }>(), {
  size: 20,
});
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" stroke-width="1.7" stroke-linecap="round"
    stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name]" />
  </svg>
</template>
