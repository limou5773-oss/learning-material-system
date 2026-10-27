const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')

const loaded = import('../scripts/verify.mjs').catch(error => {
  if (error.code === 'ERR_MODULE_NOT_FOUND') return null
  throw error
})
const validEnv = {
  DB_URL: 'jdbc:mysql://127.0.0.1:3306/learning_material_auth_test?connectionTimeZone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true',
  DB_USERNAME: 'software-test-user', DB_PASSWORD: 'software-test-password',
}
async function runner() {
  const api = await loaded
  assert.ok(api, 'implement scripts/verify.mjs acceptance runner')
  return api
}
async function workspace(t) {
  const rootDir = await fs.mkdtemp(path.join(os.tmpdir(), 'lms-acceptance-'))
  t.after(() => fs.rm(rootDir, { recursive: true, force: true }))
  return rootDir
}
function xml({ tests = 2, failures = 0, errors = 0, skipped = 0 } = {}) {
  return `<?xml version="1.0"?><testsuite name="SoftwareTest" tests="${tests}" failures="${failures}" errors="${errors}" skipped="${skipped}">${'<testcase name="software check"/>'.repeat(tests)}</testsuite>`
}
async function report(rootDir, content = xml(), modified = Date.now() + 2000) {
  const dir = path.join(rootDir, 'backend', 'target', 'surefire-reports')
  await fs.mkdir(dir, { recursive: true })
  const file = path.join(dir, 'TEST-SoftwareTest.xml')
  await fs.writeFile(file, content)
  await fs.utimes(file, modified / 1000, modified / 1000)
  return dir
}

for (const url of [undefined, '', 'jdbc:mysql://localhost/learning_material',
  'jdbc:mysql://localhost/learning_material?hint=learning_material_auth_test',
  'jdbc:mysql://localhost/learning_material_auth_test_not/a',
  'jdbc:mysql://localhost/%6cearning_material_auth_test',
  'jdbc:mysql://localhost/learning_material_auth_test%2fother',
  'jdbc:mysql://localhost,other/learning_material_auth_test',
  'jdbc:mysql:loadbalance://localhost/learning_material_auth_test',
  'jdbc:mysql://localhost/learning_material_auth_testevil',
  'jdbc:mysql://user:secret@localhost/learning_material_auth_test']) {
  test(`reject unsafe test DB location: ${String(url)}`, async () => {
    const { validateEnvironment } = await runner()
    assert.throws(() => validateEnvironment({ ...validEnv, DB_URL: url }, '24.19.0'), /DB_URL/)
  })
}

test('only actual dedicated schema or an underscore suffix is accepted', async () => {
  const { validateEnvironment } = await runner()
  for (const schema of ['learning_material_auth_test', 'learning_material_auth_test_d24']) {
    assert.doesNotThrow(() => validateEnvironment({ ...validEnv, DB_URL: `jdbc:mysql://localhost:3306/${schema}?connectionTimeZone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true` }, '24.19.0'))
  }
  for (const key of ['DB_USERNAME', 'DB_PASSWORD']) {
    assert.throws(() => validateEnvironment({ ...validEnv, [key]: '' }, '24.19.0'), new RegExp(key))
  }
  assert.throws(() => validateEnvironment(validEnv, '22.9.0'), /Node.*24/)
})

test('child environment disables vendors, processors, workers and admin across relaxed spellings', async () => {
  const { childEnvironment } = await runner()
  const env = { ...validEnv, PATH: 'keep-path', MODEL_PROVIDER: 'deepseek', MODEL_API_KEY: 'real-model-key',
    LMS_MODEL_API_KEY: 'real-model-key', 'lms.model.provider': 'deepseek', Lms_Task_Worker_Enabled: 'true',
    LMS_OCR_ENABLED: 'true', LMS_OCR_SECRET_KEY: 'real-ocr-key', ADMIN_BOOTSTRAP_PASSWORD: 'real-admin-password',
    SPRING_DATASOURCE_URL: 'jdbc:mysql://localhost/production',
    SPRING_APPLICATION_JSON: '{"lms":{"model":{"provider":"deepseek"}}}',
    MAVEN_ARGS: '-DskipTests', JAVA_TOOL_OPTIONS: '-Dlms.task.worker.enabled=true' }
  const child = childEnvironment(env)
  assert.equal(child.PATH, env.PATH)
  assert.equal(child.DB_URL, validEnv.DB_URL)
  assert.equal(child.SPRING_DATASOURCE_URL, validEnv.DB_URL)
  for (const key of ['MODEL_PROVIDER', 'LMS_MODEL_PROVIDER', 'TASK_PROCESSOR_MODE', 'LMS_TASK_PROCESSOR_MODE', 'OCR_PROVIDER']) assert.equal(child[key], 'disabled')
  for (const key of ['OCR_ENABLED', 'LMS_OCR_ENABLED', 'TASK_WORKER_ENABLED', 'LMS_TASK_WORKER_ENABLED', 'ADMIN_BOOTSTRAP_ENABLED', 'LMS_ADMINBOOTSTRAP_ENABLED']) assert.equal(child[key], 'false')
  for (const key of ['MODEL_API_KEY', 'LMS_MODEL_APIKEY', 'OCR_SECRET_KEY', 'LMS_OCR_SECRETKEY', 'ADMIN_BOOTSTRAP_PASSWORD', 'SPRING_APPLICATION_JSON', 'MAVEN_ARGS', 'JAVA_TOOL_OPTIONS']) assert.equal(child[key], '')
  for (const key of ['lms.model.provider', 'Lms_Task_Worker_Enabled', 'LMS_MODEL_API_KEY', 'LMS_OCR_SECRET_KEY']) assert.equal(child[key], undefined)
  assert.equal(env.MODEL_PROVIDER, 'deepseek')
})

test('Surefire requires complete fresh reports with positive tests', async t => {
  const { readSurefireReports } = await runner()
  const rootDir = await workspace(t)
  const dir = path.join(rootDir, 'backend', 'target', 'surefire-reports')
  await assert.rejects(readSurefireReports(dir, Date.now()), /report/i)
  await fs.mkdir(dir, { recursive: true })
  await assert.rejects(readSurefireReports(dir, Date.now()), /report/i)
  await report(rootDir, xml(), Date.now() - 10000)
  await assert.rejects(readSurefireReports(dir, Date.now()), /fresh|stale/i)
  await report(rootDir)
  assert.deepEqual(await readSurefireReports(dir, Date.now()), { reports: 1, tests: 2, failures: 0, errors: 0, skipped: 0 })
})

for (const content of [xml({ tests: 0 }), xml({ failures: 1 }), xml({ errors: 1 }), xml({ skipped: 1 }),
  '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase>',
  '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase></wrong></testsuite>',
  '<testsuite tests="1" failures="0" errors="0" skipped="0"/>',
  '<testsuite tests="1" failures="0" errors="0"><testcase/></testsuite>',
  xml().replace('tests="2"', 'tests="NaN"'),
  xml().replace('<testcase name="software check"/>', '<testcase><failure/></testcase>')]) {
  test('unusable or nonpassing Surefire report cannot pass: ' + content.slice(0, 90), async t => {
    const { readSurefireReports } = await runner()
    const dir = await report(await workspace(t), content)
    await assert.rejects(readSurefireReports(dir, Date.now()), /report|test|fail|skip|error|XML/i)
  })
}

test('successful stages run sequentially and summary stays automatic-only without credentials', async t => {
  const { runAcceptance } = await runner()
  const rootDir = await workspace(t)
  const seen = []
  const summary = await runAcceptance({ rootDir, env: validEnv, nodeVersion: '24.19.0', execute: async stage => {
    seen.push([stage.command, stage.args])
    if (stage.id === 'backend') await report(rootDir)
    return { exitCode: 0 }
  } })
  assert.deepEqual(seen, [['npm', ['--prefix', 'web', 'ci']], [process.execPath, ['--test', 'tests/*.test.js']], ['mvn', ['-f', 'backend/pom.xml', 'clean', 'verify']], ['npm', ['--prefix', 'web', 'run', 'build']]])
  assert.equal(summary.status, 'PASSED')
  assert.ok(summary.stages.every(stage => stage.status === 'PASSED' && stage.durationMs >= 0))
  assert.deepEqual(summary.backend, { reports: 1, tests: 2, failures: 0, errors: 0, skipped: 0 })
  assert.equal(summary.scope, 'AUTOMATED_ONLY')
  assert.equal(summary.releaseReady, false)
  assert.ok(summary.manualPending.length > 0)
  const saved = await fs.readFile(path.join(summary.outputDir, 'summary.json'), 'utf8')
  for (const secret of Object.values(validEnv)) assert.ok(!saved.includes(secret))
  assert.equal((await fs.readdir(summary.outputDir)).filter(file => file.endsWith('.log')).length, 4)
})

test('nonzero stage stops execution and retains failed and not-run statuses', async t => {
  const { runAcceptance } = await runner()
  const rootDir = await workspace(t)
  const calls = []
  const execute = async stage => { calls.push(stage.id); return { exitCode: 7 } }
  const first = await runAcceptance({ rootDir, env: validEnv, nodeVersion: '24.19.0', execute })
  const second = await runAcceptance({ rootDir, env: validEnv, nodeVersion: '24.19.0', execute })
  assert.notEqual(first.outputDir, second.outputDir)
  assert.deepEqual(calls, ['dependencies', 'dependencies'])
  assert.deepEqual(first.stages.map(stage => stage.status), ['PASSED', 'FAILED', 'NOT_RUN', 'NOT_RUN', 'NOT_RUN'])
  assert.equal(first.stages[1].exitCode, 7)
  assert.equal(first.status, 'FAILED')
  assert.equal(first.backend, null)
})

test('unsafe preflight never invokes children and redacts error context', async t => {
  const { runAcceptance } = await runner()
  const rootDir = await workspace(t)
  const summary = await runAcceptance({ rootDir, env: { ...validEnv, DB_URL: 'jdbc:mysql://localhost/production' }, nodeVersion: '24.19.0', execute: () => assert.fail('unsafe DB must not execute') })
  assert.deepEqual(summary.stages.map(stage => stage.status), ['FAILED', 'NOT_RUN', 'NOT_RUN', 'NOT_RUN', 'NOT_RUN'])
  assert.equal(summary.status, 'FAILED')
  assert.ok(!JSON.stringify(summary).includes('jdbc:mysql'))
})

test('Maven exit zero cannot hide skipped or missing reports', async t => {
  const { runAcceptance } = await runner()
  const rootDir = await workspace(t)
  const calls = []
  const summary = await runAcceptance({ rootDir, env: validEnv, nodeVersion: '24.19.0', execute: async stage => {
    calls.push(stage.id)
    if (stage.id === 'backend') await report(rootDir, xml({ skipped: 1 }))
    return { exitCode: 0 }
  } })
  assert.deepEqual(calls, ['dependencies', 'node-tests', 'backend'])
  assert.equal(summary.stages[3].status, 'FAILED')
  assert.equal(summary.stages[4].status, 'NOT_RUN')
  assert.equal(summary.status, 'FAILED')
})

test('child launch errors remain failures and redact credentials from summaries and logs', async t => {
  const { runAcceptance } = await runner()
  const rootDir = await workspace(t)
  const summary = await runAcceptance({ rootDir, env: validEnv, nodeVersion: '24.19.0', execute: async () => {
    throw new Error(`launch failed ${validEnv.DB_URL} ${validEnv.DB_PASSWORD} ${validEnv.DB_USERNAME}`)
  } })
  assert.equal(summary.status, 'FAILED')
  const saved = await fs.readFile(path.join(summary.outputDir, 'summary.json'), 'utf8')
  const log = await fs.readFile(path.join(summary.outputDir, 'dependencies.log'), 'utf8')
  for (const secret of Object.values(validEnv)) {
    assert.ok(!saved.includes(secret))
    assert.ok(!log.includes(secret))
  }
  assert.match(log, /launch failed/)
})

test('malformed XML entities and nested test cases are rejected', async t => {
  const { readSurefireReports } = await runner()
  const rootDir = await workspace(t)
  for (const content of [xml().replace('SoftwareTest', 'Software&broken;Test'),
    '<testsuite tests="1" failures="0" errors="0" skipped="0"><wrapper><testcase/></wrapper></testsuite>']) {
    const dir = await report(rootDir, content)
    await assert.rejects(readSurefireReports(dir, Date.now()), /XML|report/i)
  }
})

test('short credentials do not corrupt JSON keys or stage identifiers', async t => {
  const { runAcceptance } = await runner()
  const rootDir = await workspace(t)
  for (const password of ['a', 'false']) {
    const summary = await runAcceptance({ rootDir, env: { ...validEnv, DB_USERNAME: 'backend', DB_PASSWORD: password }, nodeVersion: '24.19.0', execute: async () => ({ exitCode: 3 }) })
    const saved = JSON.parse(await fs.readFile(path.join(summary.outputDir, 'summary.json'), 'utf8'))
    assert.equal(saved.status, 'FAILED')
    assert.equal(saved.stages[1].id, 'dependencies')
    assert.equal(saved.backend, null)
    assert.equal(saved.releaseReady, false)
  }
})

test('real child logs preserve diagnostics, join partial lines and redact credentials', async t => {
  const { runAcceptance, executeStage } = await runner()
  const rootDir = await workspace(t)
  const summary = await runAcceptance({ rootDir, env: validEnv, nodeVersion: '24.19.0', execute: async (_stage, options) => executeStage({
    id: 'probe', command: process.execPath,
    args: ['-e', "process.stdout.write('probe '+process.env.DB_URL.slice(0,12));process.stdout.write(process.env.DB_URL.slice(12)+'\\n');process.stderr.write(process.env.DB_PASSWORD+'\\n');process.exitCode=9"],
  }, options) })
  assert.equal(summary.stages[1].exitCode, 9)
  const log = await fs.readFile(path.join(summary.outputDir, 'dependencies.log'), 'utf8')
  assert.match(log, /probe \[REDACTED/)
  assert.match(log, /exit code 9/)
  assert.ok(!log.includes(validEnv.DB_URL))
  assert.ok(!log.includes(validEnv.DB_PASSWORD))
})

test('CLI help and unknown-argument paths never run verification', async () => {
  const { execFileSync, spawnSync } = require('node:child_process')
  const cli = path.join(__dirname, '../scripts/verify.mjs')
  assert.match(execFileSync(process.execPath, [cli, '--help'], { encoding: 'utf8' }), /Usage:.*verify.mjs/)
  const unknown = spawnSync(process.execPath, [cli, '--anything'], { encoding: 'utf8' })
  assert.equal(unknown.status, 1)
  assert.match(unknown.stderr, /Unknown arguments/)
})


for (const query of ['DBNAME=production', 'dbname=production', '%44%42%4e%41%4d%45=production',
  'connectionTimeZone=UTC&%64bname=production', 'user=production-user', 'useSSL=false']) {
  test(`JDBC query property cannot redirect database or escape the documented contract: ${query}`, async () => {
    const { validateEnvironment } = await runner()
    assert.throws(() => validateEnvironment({ ...validEnv, DB_URL: `jdbc:mysql://localhost/learning_material_auth_test?${query}` }, '24.19.0'), /DB_URL.*connectionTimeZone.*forceConnectionTimeZoneToSession/)
  })
}

test('documented decoded timezone query keys and URL without query remain valid', async () => {
  const { validateEnvironment } = await runner()
  for (const query of ['', '?connectionTimeZone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true', '?%63onnectionTimeZone=UTC']) {
    assert.doesNotThrow(() => validateEnvironment({ ...validEnv, DB_URL: `jdbc:mysql://localhost/learning_material_auth_test${query}` }, '24.19.0'))
  }
})

test('all inherited Spring datasource and Flyway overrides are removed before safe connection pins', async () => {
  const { childEnvironment } = await runner()
  const overrides = {
    SPRING_DATASOURCE_HIKARI_JDBC_URL: 'jdbc:mysql://localhost/production',
    'spring.datasource.hikari.jdbc-url': 'jdbc:mysql://localhost/production',
    SPRING_DATASOURCE_HIKARI_USERNAME: 'production-user',
    SPRING_DATASOURCE_HIKARI_PASSWORD: 'production-password',
    SPRING_DATASOURCE_HIKARI_CATALOG: 'production',
    SPRING_DATASOURCE_HIKARI_SCHEMA: 'production',
    SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL: 'USE production',
    SPRING_FLYWAY_URL: 'jdbc:mysql://localhost/production',
    SPRING_FLYWAY_USER: 'production-user',
    SPRING_FLYWAY_PASSWORD: 'production-password',
    SPRING_FLYWAY_SCHEMAS: 'production',
    'spring.flyway.default-schema': 'production',
  }
  const child = childEnvironment({ ...validEnv, ...overrides })
  assert.equal(child.SPRING_DATASOURCE_URL, validEnv.DB_URL)
  assert.equal(child.SPRING_DATASOURCE_HIKARI_JDBCURL, validEnv.DB_URL)
  assert.equal(child.SPRING_FLYWAY_URL, validEnv.DB_URL)
  assert.equal(child.SPRING_FLYWAY_USER, validEnv.DB_USERNAME)
  assert.equal(child.SPRING_FLYWAY_PASSWORD, validEnv.DB_PASSWORD)
  for (const key of Object.keys(overrides).filter(key => !['SPRING_FLYWAY_URL', 'SPRING_FLYWAY_USER', 'SPRING_FLYWAY_PASSWORD'].includes(key))) {
    assert.equal(child[key], undefined, `remove unsafe override ${key}`)
  }
})
