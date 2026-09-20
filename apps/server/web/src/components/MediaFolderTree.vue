<script setup>
import { computed } from 'vue';
import { ChevronDown, ChevronRight, Folder } from '@lucide/vue';

defineOptions({ name: 'MediaFolderTree' });

const props = defineProps({
  node: {
    type: Object,
    required: true,
  },
  selectedPath: {
    type: String,
    required: true,
  },
  depth: {
    type: Number,
    default: 0,
  },
});

const emit = defineEmits(['toggle', 'select']);

const iconStrokeWidth = 1.8;
const isSelected = computed(() => props.selectedPath === props.node.path);

function onSelect() {
  emit('select', props.node.path);
}

function onToggle(event) {
  event.stopPropagation();
  emit('toggle', props.node);
}
</script>

<template>
  <div class="media-tree-node" role="treeitem" :aria-expanded="node.expanded ? 'true' : 'false'">
    <button
      class="media-tree-row"
      type="button"
      :class="{ 'is-selected': isSelected }"
      :style="{ paddingInlineStart: `${0.55 + depth * 0.9}rem` }"
      @click="onSelect"
    >
      <span class="media-tree-twist" @click="onToggle">
        <ChevronDown v-if="node.expanded" :size="14" :stroke-width="iconStrokeWidth" />
        <ChevronRight v-else :size="14" :stroke-width="iconStrokeWidth" />
      </span>
      <Folder :size="15" :stroke-width="iconStrokeWidth" aria-hidden="true" />
      <span class="media-tree-label">{{ node.name }}</span>
      <span v-if="node.loading" class="media-tree-loading-inline">…</span>
    </button>
    <div v-if="node.expanded && node.children?.length" role="group">
      <MediaFolderTree
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :selected-path="selectedPath"
        :depth="depth + 1"
        @toggle="emit('toggle', $event)"
        @select="emit('select', $event)"
      />
    </div>
  </div>
</template>
