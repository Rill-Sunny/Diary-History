<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { renderSharedNode } from './SharedContent'

export default defineComponent({
  name: 'SharedContent',
  props: { node: { type: Object as PropType<Record<string, unknown>>, required: true } },
  setup(props) {
    return () => renderSharedNode(props.node)
  },
})
</script>
