<script setup lang="ts">
const paths = {
  network: "M9 3h6v5H9z M3 16h6v5H3z M15 16h6v5h-6z M12 8v4 M6 16v-4h12v4",
  chip: "M6 6h12v12H6z M9 9h6v6H9z M9 3v3 M15 3v3 M9 18v3 M15 18v3 M3 9h3 M3 15h3 M18 9h3 M18 15h3",
  database: "M20 6c0 1.66-3.58 3-8 3S4 7.66 4 6s3.58-3 8-3 8 1.34 8 3v12c0 1.66-3.58 3-8 3s-8-1.34-8-3V6 M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3",
  computer: "M3 4h18v13H3z M8 21h8 M12 17v4",
  math: "M18 4H6l7 8-7 8h12",
  language: "M3 5h12 M9 3v2 M12 5c-1 6-4 9-8 11 M5 8c1 3 4 6 7 8 M14 21l4-11 4 11 M15.5 17h5",
  code: "M8 6l-6 6 6 6 M16 6l6 6-6 6 M14 3l-4 18",
  book: "M12 5v16 M12 5C9 3 5 3 2 4v15c3-1 7-1 10 2 3-3 7-3 10-2V4c-3-1-7-1-10 1",
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
  previous: "M15 5l-7 7 7 7",
  outline: "M4 4h16v16H4z M9 4v16 M12 8h5 M12 12h5 M12 16h5",
  chat: "M4 4h16v13H9l-5 4V4 M8 8h8 M8 12h5",
  download: "M12 3v13 M7 11l5 5 5-5 M4 17v4h16v-4",
  zoom: "M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0 M10 6v8 M6 10h8",
  expand: "M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5",
  restore: "M3 8h5V3 M21 8h-5V3 M8 21v-5H3 M16 21v-5h5",
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
