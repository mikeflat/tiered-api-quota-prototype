import { describe, expect, it } from 'vitest'
import type { SecondTraffic } from '../../src/domain/snapshot.ts'
import { trendLinesOf, trendSlots } from '../../src/domain/trendGeometry.ts'

const size = { width: 590, height: 100 }

function secondsOf(count: number, traffic: (index: number) => { allowed: number; rejected: number }): SecondTraffic[] {
  return Array.from({ length: count }, (_, index) => ({ second: 1_000 + index, ...traffic(index) }))
}

describe('trendLinesOf', () => {
  it('full_minute_spans_left_to_right_edge', () => {
    const lines = trendLinesOf(secondsOf(trendSlots, () => ({ allowed: 5, rejected: 1 })), size)

    expect(lines.allowed).toHaveLength(60)
    expect(lines.rejected).toHaveLength(60)
    expect(lines.allowed[0]!.x).toBe(0)
    expect(lines.allowed[59]!.x).toBe(590)
    expect(lines.allowed[1]!.x).toBe(10)
  })

  it('lines_share_one_scale_from_zero', () => {
    const lines = trendLinesOf([{ second: 1, allowed: 200, rejected: 50 }], size)

    expect(lines.allowed[0]!.y).toBe(0)
    expect(lines.rejected[0]!.y).toBe(75)
  })

  it('peak_value_is_drawn_at_top', () => {
    const lines = trendLinesOf(secondsOf(3, (index) => ({ allowed: index * 10, rejected: 0 })), size)

    expect(lines.allowed.map((point) => point.y)).toEqual([100, 50, 0])
  })

  it('all_zero_minute_is_flat_on_bottom', () => {
    const lines = trendLinesOf(secondsOf(trendSlots, () => ({ allowed: 0, rejected: 0 })), size)

    expect(lines.allowed.every((point) => point.y === 100)).toBe(true)
    expect(lines.rejected.every((point) => point.y === 100)).toBe(true)
  })

  it('fewer_entries_are_right_aligned', () => {
    const lines = trendLinesOf(secondsOf(3, () => ({ allowed: 1, rejected: 0 })), size)

    expect(lines.allowed.map((point) => point.x)).toEqual([570, 580, 590])
  })

  it('more_than_sixty_entries_keep_newest_sixty', () => {
    const lines = trendLinesOf(secondsOf(61, (index) => ({ allowed: index === 0 ? 1_000 : 10, rejected: 0 })), size)

    expect(lines.allowed).toHaveLength(60)
    expect(lines.allowed[0]!.y).toBe(0)
  })

  it('coordinates_are_rounded_to_one_decimal', () => {
    const lines = trendLinesOf(secondsOf(trendSlots, (index) => ({ allowed: index === 0 ? 3 : 1, rejected: 0 })), {
      width: 600,
      height: 120,
    })

    expect(lines.allowed[1]!.x).toBe(10.2)
    expect(lines.allowed[1]!.y).toBe(80)
  })
})
