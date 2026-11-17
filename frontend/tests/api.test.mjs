import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/lib/api.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
})
const { api, ApiError } = await import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'))

test('empty and non-JSON error responses explain the HTTP status', async (context) => {
  let response
  context.mock.method(globalThis, 'fetch', async () => response)
  for (const [status, body, message] of [
    [401, null, /重新登录/],
    [403, null, /HTTP 403/],
    [404, '{"error":"Not Found"}', /HTTP 404.*联系网站管理员/],
    [500, 'Internal Server Error', /HTTP 500.*请稍后重试/],
    [502, '<html>Bad Gateway</html>', /暂时无法连接服务.*HTTP 502/],
  ]) {
    response = new Response(body, { status })
    await assert.rejects(api.exportable(), (error) => error instanceof ApiError && error.status === status && message.test(error.message))
  }
})

test('an HTML, empty, malformed or null success response fails explicitly', async (context) => {
  let response
  context.mock.method(globalThis, 'fetch', async () => response)
  for (const body of ['<!doctype html><html>SPA</html>', '', '{"broken"', 'null']) {
    response = new Response(body)
    await assert.rejects(api.exportable(), (error) => {
      assert(error instanceof ApiError)
      assert.equal(error.status, 502)
      assert.match(error.message, /数据不完整，请刷新后重试/)
      assert.deepEqual(error.details, { responseStatus: 200 })
      return true
    })
  }
})

test('valid JSON, backend details and 204 responses preserve existing behavior', async (context) => {
  const conflict = { message: '版本已经变化。', current: { version: 3 } }
  const queued = [
    Response.json([]),
    Response.json(conflict, { status: 409 }),
    Response.json({ token: 'test-csrf' }),
    new Response(null, { status: 204 }),
  ]
  context.mock.method(globalThis, 'fetch', async (_path, init) => {
    if (init.method === 'POST') assert.equal(init.headers.get('X-XSRF-TOKEN'), 'test-csrf')
    return queued.shift()
  })
  assert.deepEqual(await api.exportable(), [])
  await assert.rejects(api.journal(42), (error) => {
    assert.equal(error.status, 409)
    assert.equal(error.message, conflict.message)
    assert.deepEqual(error.details, conflict)
    return true
  })
  assert.equal(await api.logout(), undefined)
})
