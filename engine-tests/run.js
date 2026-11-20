// Runs every engine test (each in its own node process) and fails if any of them fails. `npm test`, or `node run.js orient`.
const { spawnSync } = require('child_process'); const fs = require('fs'); const path = require('path')
const skip = new Set(['lib.js', 'run.js'])
const only = process.argv.slice(2)
const files = fs.readdirSync(__dirname).filter(f => f.endsWith('.js') && !skip.has(f) && (!only.length || only.some(o => f.startsWith(o)))).sort()
const failed = []
for (const f of files) {
  const t0 = Date.now()
  const r = spawnSync(process.execPath, [path.join(__dirname, f)], { stdio: 'inherit', timeout: 180000 })
  if (r.status !== 0) failed.push(f + (r.signal ? ' (' + r.signal + ')' : ''))
  console.log(`  -- ${f}: ${r.status === 0 ? 'passed' : 'FAILED'} in ${((Date.now() - t0) / 1000).toFixed(1)} s\n`)
}
console.log(failed.length ? `FAILED: ${failed.join(', ')}` : `all ${files.length} engine tests passed`)
process.exit(failed.length ? 1 : 0)
