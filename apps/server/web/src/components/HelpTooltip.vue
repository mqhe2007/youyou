<script setup>
import { CircleHelp } from '@lucide/vue';

defineProps({
  text: {
    type: String,
    required: true,
  },
  label: {
    type: String,
    default: '查看说明',
  },
  placement: {
    type: String,
    default: 'tooltip-right',
  },
});

const iconStrokeWidth = 1.8;
</script>

<template>
  <span class="tooltip help-tooltip" :class="placement" :data-tip="text">
    <button class="help-button" type="button" :aria-label="label" :title="text">
      <CircleHelp :size="15" :stroke-width="iconStrokeWidth" aria-hidden="true" />
    </button>
  </span>
</template>
