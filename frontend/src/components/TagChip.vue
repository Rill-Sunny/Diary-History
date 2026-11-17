<script setup lang="ts">
import Icon from './Icon.vue'
import type { Tag } from '../lib/types'

defineProps<{ tag: Tag; offline?: boolean }>()
</script>

<template>
  <span class="tag-chip" :class="'tag-style-' + (tag.style || 'soft')" :style="{ '--tag-color': tag.color }">
    <img v-if="tag.approvedIconMediaId && !offline" :src="'/api/media/' + tag.approvedIconMediaId" alt="" loading="lazy" />
    <Icon v-else :name="tag.icon || 'tag'" :size="13" />{{ tag.name }}<slot />
  </span>
</template>
