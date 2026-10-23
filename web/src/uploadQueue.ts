export async function runUploadQueue<T>(items: T[], task: (item: T) => Promise<void>, concurrency = 2): Promise<PromiseSettledResult<void>[]> {
  const results: PromiseSettledResult<void>[] = new Array(items.length)
  let cursor = 0
  async function worker() {
    while (cursor < items.length) {
      const index = cursor++
      try {
        await task(items[index]!)
        results[index] = { status: 'fulfilled', value: undefined }
      } catch (reason) {
        results[index] = { status: 'rejected', reason }
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(Math.max(concurrency, 1), items.length) }, () => worker()))
  return results
}
