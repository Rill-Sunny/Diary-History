import assert from 'node:assert/strict'
import { File } from 'node:buffer'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { zipSync } from 'fflate'
import ts from 'typescript'

const source = await readFile(new URL('../src/lib/markdownImport.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
})
const moduleText = outputText.replace(/from ['"]fflate['"]/, 'from ' + JSON.stringify(import.meta.resolve('fflate')))
const { readSource, scanSections, parseBody } = await import('data:text/javascript;base64,' + Buffer.from(moduleText).toString('base64'))

test('reads the same text from plain Markdown and ZIP with supported encodings', async () => {
  const text = '# 2024-05-03\r\n测试中文。\r\n'
  const utf16le = Buffer.from(text, 'utf16le')
  const utf16be = Buffer.from(utf16le).swap16()
  const gb18030 = Buffer.concat([
    Buffer.from('# 2024-05-03\r\n'),
    Buffer.from([0xb2, 0xe2, 0xca, 0xd4, 0xd6, 0xd0, 0xce, 0xc4, 0xa1, 0xa3, 13, 10]),
  ])
  const samples = new Map([
    ['UTF-8', Buffer.from(text)],
    ['UTF-8 with BOM', Buffer.from('\ufeff' + text)],
    ['GB18030', gb18030],
    ['UTF-16 LE with BOM', Buffer.concat([Buffer.from([0xff, 0xfe]), utf16le])],
    ['UTF-16 BE with BOM', Buffer.concat([Buffer.from([0xfe, 0xff]), utf16be])],
    ['UTF-16 LE without BOM', utf16le],
    ['UTF-16 BE without BOM', utf16be],
  ])
  for (const [encoding, bytes] of samples) {
    const plain = await readSource(new File([bytes], '日记.md'))
    const zipped = await readSource(new File([zipSync({ '笔记/日记.md': bytes })], '日记.zip'))
    assert.equal(plain.markdown, text, encoding + ' plain')
    assert.equal(zipped.markdown, text, encoding + ' ZIP')
    assert.equal(scanSections(zipped.markdown).sections.length, 1)
  }
})

test('explains malformed archives and unreadable text while preserving path validation', async () => {
  const binary = Uint8Array.of(0xff, 0x00, 0x1f)
  await assert.rejects(readSource(new File([binary], '日记.md')), /另存为 UTF-8/)
  await assert.rejects(readSource(new File([zipSync({ '日记.md': binary })], '日记.zip')), /另存为 UTF-8/)
  await assert.rejects(readSource(new File([binary], '损坏.zip')), /无法打开 ZIP/)
  await assert.rejects(readSource(new File([zipSync({ '../日记.md': Buffer.from('测试') })], '日记.zip')), /不安全的文件路径/)
})

test('recognizes Chinese date variants and leaves incidental dates in body blocks', () => {
  const text = [
    '##　２０２４ 年 ５ 月 ３ 日',
    '正文',
    '> ## 2024年5月4日',
    '~~~~markdown',
    '# 2024年5月5日',
    '~~~~',
    '### 第①.②节中的 2024年5月6日',
    '#### ①.②',
    '# 5 月 ７ 号',
    '# ２０２４－５－８',
  ].join('\n')
  const { sections, prelude } = scanSections(text)
  assert.equal(prelude, '')
  assert.deepEqual(sections.map(({ date }) => [date.year, date.month, date.day]), [
    [2024, 5, 3], [undefined, 5, 7], [2024, 5, 8],
  ])
  assert.match(sections[0].body, /> ## 2024年5月4日/)
  assert.match(sections[0].body, /# 2024年5月5日/)
  assert.match(sections[0].body, /#### ①.②/)
})

test('an undated Markdown file stays available for manual date assignment without losing code indentation', async () => {
  const text = '\n    第一个代码行\n    第二个代码行\n\n# 普通标题\n正文。\n'
  const input = await readSource(new File([text], '普通日记.md'))
  const { sections, prelude } = scanSections(input.markdown)
  assert.equal(sections.length, 0)
  const result = parseBody(prelude, input, new Map(), false)
  assert.deepEqual(result.content.content.map((block) => block.type), ['codeBlock', 'heading', 'paragraph'])
  assert.equal(result.content.content[0].content[0].text, '第一个代码行\n第二个代码行')
  assert.equal(scanSections('\n \t\n').prelude, '')
})

test('a ZIP image with parentheses in its Windows relative file name is available in preview', async () => {
  const text = '![照片](..\\配图\\火车 (1).png)'
  const bytes = zipSync({ '日记/周记.md': Buffer.from(text), '配图/火车 (1).png': Uint8Array.of(1, 2, 3) })
  const input = await readSource(new File([bytes], '周记.zip'))
  const parsed = parseBody(input.markdown, input, new Map(), false)
  assert.deepEqual(parsed.paths, ['配图/火车 (1).png'])
  assert.deepEqual(parsed.missing, [])
})
