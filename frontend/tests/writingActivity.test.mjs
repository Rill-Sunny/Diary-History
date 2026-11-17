import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/lib/writingActivity.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
})
const { dateKey, offsetDate, sanitizeActivities, activityStats, buildYearGrid, yearSummary } =
  await import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'))

const activity = (dates, today) => sanitizeActivities(dates.map((date) => ({ date, count: 1 })), today)

test('date offsets preserve civil dates across year, leap-day and DST boundaries', () => {
  assert.equal(offsetDate('2023-12-31', 1), '2024-01-01')
  assert.equal(offsetDate('2024-03-01', -1), '2024-02-29')
  assert.equal(offsetDate('2023-03-01', -1), '2023-02-28')
  assert.equal(offsetDate('2024-03-09', 2), '2024-03-11')
  assert.equal(offsetDate('2024-11-02', 2), '2024-11-04')
  assert.equal(dateKey(2000, 2, 29), '2000-02-29')
  assert.throws(() => dateKey(2100, 2, 29), RangeError)
  assert.throws(() => offsetDate('2024-02-30', 1), RangeError)
  assert.throws(() => offsetDate('2024-03-01', 0.5), RangeError)
})

test('sanitization combines duplicate dates and ignores invalid, nonpositive and future data', () => {
  const input = [
    { date: '2024-02-29', count: 2 }, { date: '2024-02-29', count: 3 },
    { date: '2024-03-01', count: 1 }, { date: '2024-03-02', count: 9 },
    { date: '2024-02-30', count: 1 }, { date: '2023-02-29', count: 1 },
    { date: '2024-2-28', count: 1 }, { date: '2024-02-28', count: 0 },
    { date: '2024-02-27', count: -2 }, { date: '2024-02-26', count: Number.NaN },
    { date: '2024-02-25', count: Number.POSITIVE_INFINITY }, { date: '2024-02-24', count: 0.5 },
  ]
  const snapshot = structuredClone(input)
  assert.deepEqual([...sanitizeActivities(input, '2024-03-01')], [['2024-02-29', 5], ['2024-03-01', 1]])
  assert.deepEqual(input, snapshot)
})

test('empty and broken streaks are zero while longest still represents history', () => {
  assert.deepEqual(activityStats(new Map(), '2026-11-11'), { current: 0, longest: 0 })
  const map = activity(['2026-11-07', '2026-11-05', '2026-11-06'], '2026-11-11')
  assert.deepEqual(activityStats(map, '2026-11-11'), { current: 0, longest: 3 })
})

test('today or yesterday may anchor a current streak, and duplicate writes add no streak day', () => {
  const yesterday = activity(['2026-11-10', '2026-11-09', '2026-11-09', '2026-11-08'], '2026-11-11')
  assert.deepEqual(activityStats(yesterday, '2026-11-11'), { current: 3, longest: 3 })
  yesterday.set('2026-11-11', 4)
  assert.deepEqual(activityStats(yesterday, '2026-11-11'), { current: 4, longest: 4 })
  assert.deepEqual(activityStats(activity(['2026-11-11'], '2026-11-11'), '2026-11-11'), { current: 1, longest: 1 })
})

test('streaks cross years and leap day but ignore future and nonpositive entries', () => {
  const crossYear = activity(['2024-01-02', '2023-12-31', '2024-01-01', '2023-12-30'], '2024-01-02')
  assert.deepEqual(activityStats(crossYear, '2024-01-02'), { current: 4, longest: 4 })
  const leap = new Map([['2024-02-28', 1], ['2024-02-29', 1], ['2024-03-01', 1], ['2024-03-02', 1], ['2024-02-27', 0]])
  assert.deepEqual(activityStats(leap, '2024-03-01'), { current: 3, longest: 3 })
})

test('year summaries count active days separately from activity totals', () => {
  const map = sanitizeActivities([
    { date: '2023-12-31', count: 7 }, { date: '2024-01-01', count: 3 },
    { date: '2024-01-01', count: 2 }, { date: '2024-02-29', count: 1 },
  ], '2024-03-01')
  assert.deepEqual(yearSummary(map, 2024), { days: 2, total: 6 })
  assert.deepEqual(yearSummary(map, 2023), { days: 1, total: 7 })
  assert.deepEqual(yearSummary(map, 2022), { days: 0, total: 0 })
})

test('a Monday-start leap year has all 366 unique dates with correct ending padding', () => {
  const { weeks, months } = buildYearGrid(2024, '2024-12-31')
  const cells = weeks.flat()
  const dates = cells.map((cell) => cell.date).filter(Boolean)
  assert.equal(weeks.length, 53)
  assert(weeks.every((week) => week.length === 7))
  assert.deepEqual(weeks[0].map((cell) => cell.date), [
    '2024-01-01', '2024-01-02', '2024-01-03', '2024-01-04', '2024-01-05', '2024-01-06', '2024-01-07',
  ])
  assert.deepEqual(weeks.at(-1).map((cell) => cell.date), ['2024-12-30', '2024-12-31', null, null, null, null, null])
  assert.equal(dates.length, 366)
  assert.equal(new Set(dates).size, 366)
  assert.equal(dates.filter((date) => date === '2024-02-29').length, 1)
  assert.equal(months.length, 12)
  assert.deepEqual(months[0], { label: '1月', column: 0 })
  assert.deepEqual(months[1], { label: '2月', column: 4 })
  assert.deepEqual(months[2], { label: '3月', column: 8 })
  assert(!cells.some((cell) => cell.future))
})

test('Sunday-start non-leap years align Sunday in row six', () => {
  const { weeks } = buildYearGrid(2023, '2026-11-11')
  assert.deepEqual(weeks[0].map((cell) => cell.date), [null, null, null, null, null, null, '2023-01-01'])
  assert.equal(weeks.at(-1)[6].date, '2023-12-31')
  assert.equal(weeks.flat().filter((cell) => cell.date).length, 365)
  assert(!weeks.flat().some((cell) => cell.future))
})

test('Sunday-start leap years retain the 54th week rather than clipping December 31', () => {
  const { weeks } = buildYearGrid(2012, '2026-11-11')
  assert.equal(weeks.length, 54)
  assert.deepEqual(weeks.at(-1).map((cell) => cell.date), ['2012-12-31', null, null, null, null, null, null])
  assert.equal(weeks.flat().filter((cell) => cell.date).length, 366)
})

test('future markers preserve today as usable and never mark year-padding cells', () => {
  const { weeks } = buildYearGrid(2024, '2024-02-28')
  const cells = weeks.flat()
  assert.equal(cells.find((cell) => cell.date === '2024-02-28').future, false)
  assert.equal(cells.find((cell) => cell.date === '2024-02-29').future, true)
  assert.equal(cells.find((cell) => cell.date === '2024-12-31').future, true)
  assert(cells.filter((cell) => cell.date === null).every((cell) => cell.future === false))
})
