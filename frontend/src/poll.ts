import { onMounted, onUnmounted } from 'vue'

export function poll(refresh: () => Promise<boolean>, interval = 1800) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  async function tick() {
    const keepGoing = await refresh()
    if (!stopped && keepGoing) timer = setTimeout(tick, interval)
  }
  onMounted(tick)
  onUnmounted(() => {
    stopped = true
    clearTimeout(timer)
  })
}
