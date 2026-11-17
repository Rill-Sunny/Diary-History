import type { WritingDay } from './types'

const DAY_MS = 86_400_000

function utcDate(year: number, month: number, day: number): Date {
  if (![year, month, day].every(Number.isInteger) || year < 1000 || year > 9999) {
    throw new RangeError('Date must have an integer year between 1000 and 9999.')
  }
  return new Date(Date.UTC(year, month - 1, day))
}

export function dateKey(year: number, month: number, day: number): string {
  const date = utcDate(year, month, day)
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
    throw new RangeError('Invalid calendar date.')
  }
  return String(year).padStart(4, '0') + '-' + String(month).padStart(2, '0') + '-' + String(day).padStart(2, '0')
}

function parseDate(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const [year, month, day] = value.split('-').map(Number) as [number, number, number]
  try {
    return dateKey(year, month, day) === value ? utcDate(year, month, day) : null
  } catch {
    return null
  }
}

function requireDate(value: string): Date {
  const date = parseDate(value)
  if (!date) throw new RangeError('Expected a valid YYYY-MM-DD date.')
  return date
}

/** Calendar arithmetic uses UTC so local DST never adds or drops a day. */
export function offsetDate(value: string, days: number): string {
  if (!Number.isInteger(days)) throw new RangeError('Date offset must be an integer.')
  const date = requireDate(value)
  date.setUTCDate(date.getUTCDate() + days)
  return dateKey(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate())
}

/** Returned counts are actual writing activity, not journal-date coverage. */
export function sanitizeActivities(days: WritingDay[], today: string): Map<string, number> {
  requireDate(today)
  const result = new Map<string, number>()
  for (const { date, count } of days) {
    if (!parseDate(date) || date > today || !Number.isSafeInteger(count) || count <= 0) continue
    result.set(date, (result.get(date) ?? 0) + count)
  }
  return result
}

export function activityStats(activity: ReadonlyMap<string, number>, today: string): { current: number; longest: number } {
  const todayDate = requireDate(today)
  const dates = [...activity.entries()]
    .filter(([date, count]) => parseDate(date) && date <= today && count > 0)
    .map(([date]) => date)
    .sort()
  let longest = 0
  let run = 0
  let previousTime = Number.NEGATIVE_INFINITY
  for (const date of dates) {
    const time = requireDate(date).getTime()
    run = time - previousTime === DAY_MS ? run + 1 : 1
    longest = Math.max(longest, run)
    previousTime = time
  }
  const activeTimes = new Set(dates.map((date) => requireDate(date).getTime()))
  let cursor = todayDate.getTime()
  if (!activeTimes.has(cursor)) cursor -= DAY_MS
  let current = 0
  while (activeTimes.has(cursor)) {
    current += 1
    cursor -= DAY_MS
  }
  return { current, longest }
}

export interface YearGridCell {
  date: string | null
  future: boolean
}

export interface YearGrid {
  /** Columns of seven days, Monday first; padding outside the year has date:null. */
  weeks: YearGridCell[][]
  /** Zero-based week column containing the first of each month. */
  months: { label: string; column: number }[]
}

export function buildYearGrid(year: number, today: string): YearGrid {
  requireDate(today)
  const start = requireDate(dateKey(year, 1, 1))
  const last = requireDate(dateKey(year, 12, 31))
  const leading = (start.getUTCDay() + 6) % 7
  const daysInYear = (last.getTime() - start.getTime()) / DAY_MS + 1
  const weekCount = Math.ceil((leading + daysInYear) / 7)
  const weeks = Array.from({ length: weekCount }, (_, column) =>
    Array.from({ length: 7 }, (_, row): YearGridCell => {
      const dayIndex = column * 7 + row - leading
      if (dayIndex < 0 || dayIndex >= daysInYear) return { date: null, future: false }
      const date = offsetDate(dateKey(year, 1, 1), dayIndex)
      return { date, future: date > today }
    }),
  )
  const months = Array.from({ length: 12 }, (_, index) => ({
    label: String(index + 1) + '月',
    column: Math.floor((leading + (utcDate(year, index + 1, 1).getTime() - start.getTime()) / DAY_MS) / 7),
  }))
  return { weeks, months }
}

export function yearSummary(activity: ReadonlyMap<string, number>, year: number): { days: number; total: number } {
  const prefix = dateKey(year, 1, 1).slice(0, 4) + '-'
  let days = 0
  let total = 0
  for (const [date, count] of activity) {
    if (!date.startsWith(prefix) || !parseDate(date) || !Number.isSafeInteger(count) || count <= 0) continue
    days += 1
    total += count
  }
  return { days, total }
}
