#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { appendFileSync } from 'node:fs'
import { mkdir, mkdtemp, readFile, readdir, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createInterface } from 'node:readline'

const ROOT = fileURLToPath(new URL('../', import.meta.url))
const STAGES = [
  { id: 'dependencies', command: 'npm', args: ['--prefix', 'web', 'ci'] },
  { id: 'node-tests', command: process.execPath, args: ['--test', 'tests/*.test.js'] },
  { id: 'backend', command: 'mvn', args: ['-f', 'backend/pom.xml', 'clean', 'verify'] },
  { id: 'web-build', command: 'npm', args: ['--prefix', 'web', 'run', 'build'] },
]
const WINDOWS_COMMANDS = {
  dependencies: 'npm --prefix web ci',
  backend: 'mvn -f backend/pom.xml clean verify',
  'web-build': 'npm --prefix web run build',
}

export function validateEnvironment(env, nodeVersion = process.versions.node) {
  if (Number(nodeVersion.split('.')[0]) !== 24) throw new Error('Node.js 24 is required')
  const match = /^jdbc:mysql:\/\/([^/?#]+)\/([^/?#]+)(?:\?([^#\s]*))?$/.exec(env.DB_URL ?? '')
  const host = match && /^(?:[a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\])(?::([0-9]{1,5}))?$/.exec(match[1])
  if (!match || !host || (host[1] && (Number(host[1]) < 1 || Number(host[1]) > 65535)) ||
      !/^learning_material_auth_test(?:_[a-zA-Z0-9][a-zA-Z0-9_]*)?$/.test(match[2])) {
    throw new Error('DB_URL must target the dedicated learning_material_auth_test schema or its underscore suffix on one MySQL host')
  }
  for (const key of new URLSearchParams(match[3]).keys()) {
    if (!['connectiontimezone', 'forceconnectiontimezonetosession'].includes(key.toLowerCase())) {
      throw new Error('DB_URL query parameters may only be connectionTimeZone and forceConnectionTimeZoneToSession')
    }
  }
  for (const key of ['DB_USERNAME', 'DB_PASSWORD']) {
    if (typeof env[key] !== 'string' || !env[key].trim()) throw new Error(`${key} is required`)
  }
}

export function childEnvironment(env) {
  const safe = {
    DB_URL: env.DB_URL, DB_USERNAME: env.DB_USERNAME, DB_PASSWORD: env.DB_PASSWORD,
    SPRING_DATASOURCE_URL: env.DB_URL, SPRING_DATASOURCE_USERNAME: env.DB_USERNAME, SPRING_DATASOURCE_PASSWORD: env.DB_PASSWORD,
    SPRING_DATASOURCE_HIKARI_JDBCURL: env.DB_URL,
    SPRING_FLYWAY_URL: env.DB_URL, SPRING_FLYWAY_USER: env.DB_USERNAME, SPRING_FLYWAY_PASSWORD: env.DB_PASSWORD,
    MODEL_PROVIDER: 'disabled', MODEL_API_KEY: '', LMS_MODEL_PROVIDER: 'disabled', LMS_MODEL_APIKEY: '',
    OCR_ENABLED: 'false', OCR_PROVIDER: 'disabled', OCR_SECRET_ID: '', OCR_SECRET_KEY: '',
    LMS_OCR_ENABLED: 'false', LMS_OCR_PROVIDER: 'disabled', LMS_OCR_SECRETID: '', LMS_OCR_SECRETKEY: '',
    TASK_PROCESSOR_MODE: 'disabled', TASK_WORKER_ENABLED: 'false', LMS_TASK_PROCESSOR_MODE: 'disabled', LMS_TASK_WORKER_ENABLED: 'false',
    ADMIN_BOOTSTRAP_ENABLED: 'false', ADMIN_BOOTSTRAP_USERNAME: '', ADMIN_BOOTSTRAP_PASSWORD: '', ADMIN_BOOTSTRAP_DISPLAY_NAME: '',
    LMS_ADMINBOOTSTRAP_ENABLED: 'false', LMS_ADMINBOOTSTRAP_USERNAME: '', LMS_ADMINBOOTSTRAP_PASSWORD: '', LMS_ADMINBOOTSTRAP_DISPLAYNAME: '',
    SPRING_PROFILES_ACTIVE: 'acceptance', SPRING_PROFILES_INCLUDE: '', SPRING_CONFIG_IMPORT: '',
    SPRING_APPLICATION_JSON: '', JAVA_TOOL_OPTIONS: '', _JAVA_OPTIONS: '', JDK_JAVA_OPTIONS: '', MAVEN_OPTS: '', MAVEN_ARGS: '',
  }
  const normalize = key => key.replace(/[^a-z0-9]/gi, '').toLowerCase()
  const overridden = new Set(Object.keys(safe).map(normalize))
  const inherited = Object.entries(env).filter(([key]) =>
    !overridden.has(normalize(key)) && !/^spring(?:datasource|flyway)/.test(normalize(key)))
  return { ...Object.fromEntries(inherited), ...safe }
}

// Parse only the XML constructs Surefire emits; reject incomplete or inconsistent reports.
function suiteCounts(xml) {
  const stack = [], counts = { tests: 0, failures: 0, errors: 0, skipped: 0 }
  let position = 0, root, cases = 0
  const invalid = () => { throw new Error('Malformed Surefire XML report') }
  const invalidText = /&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[a-fA-F0-9]+);)|[\x00-\x08\x0B\x0C\x0E-\x1F]/
  const tokens = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?[\s\S]*?\?>|<(?:[^<>"']|"[^"<]*"|'[^'<]*')*>/g
  for (const token of xml.matchAll(tokens)) {
    const text = xml.slice(position, token.index)
    if (text.includes('<') || invalidText.test(text) || (!stack.length && text.trim())) invalid()
    position = token.index + token[0].length
    const tag = token[0]
    if (tag.startsWith('<?') || tag.startsWith('<!--')) continue
    if (tag.startsWith('<![CDATA[')) { if (!stack.length) invalid(); continue }
    const close = /^<\/([\w:.-]+)\s*>$/.exec(tag)
    if (close) { if (stack.pop() !== close[1]) invalid(); continue }
    const open = /^<([a-zA-Z_][\w:.-]*)([\s\S]*?)(\/?)>$/.exec(tag)
    if (!open || (!stack.length && root)) invalid()
    const attrs = {}, attr = /\s+([a-zA-Z_][\w:.-]*)\s*=\s*("[^"]*"|'[^']*')/gy
    let consumed = 0, parsed
    while ((parsed = attr.exec(open[2]))) {
      if (Object.hasOwn(attrs, parsed[1])) invalid()
      if (invalidText.test(parsed[2])) invalid()
      attrs[parsed[1]] = parsed[2].slice(1, -1)
      consumed = attr.lastIndex
    }
    if (open[2].slice(consumed).trim()) invalid()
    if (!root) {
      if (open[1] !== 'testsuite') invalid()
      root = attrs
      for (const key of Object.keys(counts)) {
        if (!/^\d+$/.test(root[key]) || !Number.isSafeInteger(Number(root[key]))) invalid()
        counts[key] = Number(root[key])
      }
    }
    if (open[1] === 'testcase') {
      if (stack.length !== 1 || stack[0] !== 'testsuite') invalid()
      cases++
    }
    if (['failure', 'error', 'skipped'].includes(open[1])) throw new Error('Surefire report contains failure, error or skipped test')
    if (!open[3]) stack.push(open[1])
  }
  if (!root || stack.length || xml.slice(position).trim() || cases !== counts.tests) invalid()
  return counts
}

export async function readSurefireReports(reportDir, startedAt) {
  let files
  try { files = (await readdir(reportDir)).filter(name => /^TEST-.+\.xml$/.test(name)) }
  catch (error) { if (error.code !== 'ENOENT') throw error; throw new Error('Surefire report directory is missing') }
  if (!files.length) throw new Error('No Surefire XML reports were generated')
  const total = { reports: files.length, tests: 0, failures: 0, errors: 0, skipped: 0 }
  for (const name of files) {
    const file = path.join(reportDir, name)
    if ((await stat(file)).mtimeMs < startedAt) throw new Error('Stale Surefire report: fresh reports are required')
    const counts = suiteCounts(await readFile(file, 'utf8'))
    for (const key of Object.keys(counts)) total[key] += counts[key]
  }
  if (!total.tests || total.failures || total.errors || total.skipped) {
    const error = new Error('Surefire reports require positive tests and zero failures, errors and skipped tests')
    error.counts = total
    throw error
  }
  return total
}

function redactor(env) {
  const values = Object.entries(env).filter(([key, value]) => value &&
    /(?:dburl|dbusername|password|apikey|secretid|secretkey|adminbootstrapusername)/.test(key.replace(/[^a-z0-9]/gi, '').toLowerCase()))
    .map(([, value]) => value).sort((a, b) => b.length - a.length)
  return value => {
    let safe = String(value).replace(/jdbc:mysql:[^\s"'<>]+/gi, '[REDACTED_DB_URL]')
    for (const secret of values) safe = safe.replaceAll(secret, '[REDACTED]')
    return safe
  }
}

export async function executeStage(stage, { cwd, env, logFile, redact }) {
  const windowsCommand = process.platform === 'win32' && WINDOWS_COMMANDS[stage.id]
  const command = windowsCommand ? 'cmd.exe' : stage.command
  const args = windowsCommand ? ['/d', '/s', '/c', windowsCommand] : stage.args
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    for (const stream of [child.stdout, child.stderr]) {
      const lines = createInterface({ input: stream, crlfDelay: Infinity })
      lines.on('line', line => appendFileSync(logFile, redact(line) + '\n'))
    }
    child.on('error', reject)
    child.on('close', (exitCode, signal) => resolve({ exitCode, signal }))
  })
}

export async function runAcceptance({ rootDir = ROOT, env = process.env, nodeVersion = process.versions.node, execute = executeStage } = {}) {
  const parent = path.join(rootDir, 'acceptance-output')
  await mkdir(parent, { recursive: true })
  const outputDir = await mkdtemp(path.join(parent, 'run-'))
  const redact = redactor(env)
  const summary = {
    status: 'FAILED', scope: 'AUTOMATED_ONLY', releaseReady: false,
    manualPending: ['Real OCR and DeepSeek quality', 'Native WeChat and real account binding', 'Manual performance and final acceptance'],
    startedAt: new Date().toISOString(), outputDir, backend: null,
    stages: [{ id: 'preflight', status: 'NOT_RUN', durationMs: null }, ...STAGES.map(stage => ({ id: stage.id, status: 'NOT_RUN', durationMs: null }))],
  }
  const save = () => writeFile(path.join(outputDir, 'summary.json'), JSON.stringify(summary, null, 2) + '\n')
  await save()
  for (const stage of summary.stages) {
    const started = Date.now()
    try {
      if (stage.id === 'preflight') validateEnvironment(env, nodeVersion)
      else {
        const spec = STAGES.find(item => item.id === stage.id)
        stage.log = `${stage.id}.log`
        const logFile = path.join(outputDir, stage.log)
        await writeFile(logFile, '')
        const result = await execute({ ...spec }, { cwd: rootDir, env: childEnvironment(env), logFile, redact })
        stage.exitCode = result.exitCode
        if (result.exitCode !== 0) throw new Error(`Command failed with exit code ${result.exitCode}${result.signal ? ` (${result.signal})` : ''}; see ${stage.log}`)
        if (stage.id === 'backend') summary.backend = await readSurefireReports(path.join(rootDir, 'backend', 'target', 'surefire-reports'), started)
      }
      stage.status = 'PASSED'
    } catch (error) {
      stage.status = 'FAILED'
      stage.error = redact(error.message)
      if (error.counts) summary.backend = error.counts
      if (stage.log) appendFileSync(path.join(outputDir, stage.log), `\n${stage.error}\n`)
    }
    stage.durationMs = Date.now() - started
    await save()
    if (stage.status === 'FAILED') break
  }
  summary.status = summary.stages.every(stage => stage.status === 'PASSED') ? 'PASSED' : 'FAILED'
  summary.finishedAt = new Date().toISOString()
  await save()
  return summary
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  if (process.argv.length === 3 && process.argv[2] === '--help') {
    console.log('Usage: node scripts/verify.mjs\nRequires Node.js 24 and DB_URL, DB_USERNAME, DB_PASSWORD for a dedicated learning_material_auth_test[_suffix] schema.\nDB_URL query keys: connectionTimeZone and forceConnectionTimeZoneToSession only.\nRuns dependency install, Node tests, Maven clean verify, Web build sequentially.\nWrites sanitized logs and summary.json under acceptance-output/run-*. Automated verification only; manual acceptance remains pending.')
  } else if (process.argv.length !== 2) {
    console.error('Unknown arguments. Use node scripts/verify.mjs --help')
    process.exitCode = 1
  } else {
    try {
      const summary = await runAcceptance()
      console.log(`Automatic verification: ${summary.status}; releaseReady=false; manual acceptance pending.\nReports: ${summary.outputDir}`)
      const failed = summary.stages.find(stage => stage.status === 'FAILED')
      if (failed) console.error(`${failed.id}: ${failed.error}`)
      process.exitCode = summary.status === 'PASSED' ? 0 : 1
    } catch (error) {
      console.error(`Acceptance runner failed: ${redactor(process.env)(error.message)}`)
      process.exitCode = 1
    }
  }
}
