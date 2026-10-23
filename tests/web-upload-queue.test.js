const test = require('node:test')
const assert = require('node:assert/strict')

test('批量上传最多并发两个，单个失败后其余文件仍执行', async () => {
  const { runUploadQueue } = await import('../web/src/uploadQueue.ts')
  const files = ['ok-1.pdf', 'broken.pdf', 'ok-2.pdf', 'ok-3.pdf']
  let active = 0
  let highest = 0
  const visited = []
  const results = await runUploadQueue(files, async file => {
    active += 1
    highest = Math.max(highest, active)
    await new Promise(resolve => setTimeout(resolve, 5))
    visited.push(file)
    active -= 1
    if (file === 'broken.pdf') throw new Error('PDF损坏')
  })
  assert.equal(highest, 2)
  assert.deepEqual(new Set(visited), new Set(files))
  assert.deepEqual(results.map(result => result.status), ['fulfilled', 'rejected', 'fulfilled', 'fulfilled'])
  assert.match(results[1].reason.message, /PDF损坏/)
})
