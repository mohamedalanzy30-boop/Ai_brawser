// Runs every engine test (or the ones named on the command line); exits non-zero if any fails.
const { spawnSync, execSync } = require('child_process'); const fs = require('fs'); const path = require('path')
const skip = new Set(['run.js', 'lib.js'])
const tests = process.argv.slice(2).length ? process.argv.slice(2) : fs.readdirSync(__dirname).filter(f => f.endsWith('.js') && !skip.has(f)).sort()
let globalRoot = ''; try { globalRoot = execSync('npm root -g').toString().trim() } catch (e) { /* no npm */ }
const env = Object.assign({}, process.env, { NODE_PATH: [process.env.NODE_PATH, globalRoot].filter(Boolean).join(path.delimiter) })
const failed = []
for (const t of tests) {
  console.log('--- ' + t)
  const t0 = Date.now()
  const r = spawnSync(process.execPath, [path.join(__dirname, t)], { stdio: 'inherit', env, timeout: 180000 })
  const ok = r.status === 0
  console.log((ok ? 'PASS ' : 'FAIL ') + t + ' (' + ((Date.now() - t0) / 1000).toFixed(1) + ' s)')
  if (!ok) failed.push(t)
}
console.log('\n' + (tests.length - failed.length) + '/' + tests.length + ' passed' + (failed.length ? ', failed: ' + failed.join(', ') : ''))
process.exit(failed.length ? 1 : 0)
