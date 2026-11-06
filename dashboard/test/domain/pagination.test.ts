import { describe, expect, it } from 'vitest'
import { correctedPageOf, pageCountOf } from '../../src/domain/pagination.ts'
import type { TenantPage } from '../../src/domain/snapshot.ts'

function pageOf(page: number, total: number): TenantPage {
  return { rowsAvailable: true, rows: [], total, page, size: 10 }
}

describe('pagination', () => {
  it('page_count_rounds_up', () => {
    expect(pageCountOf(15, 10)).toBe(2)
    expect(pageCountOf(20, 10)).toBe(2)
    expect(pageCountOf(21, 10)).toBe(3)
  })

  it('page_count_is_at_least_one', () => {
    expect(pageCountOf(0, 10)).toBe(1)
  })

  it('page_within_range_needs_no_correction', () => {
    expect(correctedPageOf(pageOf(1, 15))).toBeNull()
    expect(correctedPageOf(pageOf(0, 0))).toBeNull()
    expect(correctedPageOf({ rowsAvailable: false, rows: null, total: null, page: 3, size: 10 })).toBeNull()
  })

  it('page_past_end_moves_to_last_page', () => {
    expect(correctedPageOf(pageOf(1, 4))).toBe(0)
    expect(correctedPageOf(pageOf(5, 15))).toBe(1)
  })

  it('empty_result_moves_to_first_page', () => {
    expect(correctedPageOf(pageOf(2, 0))).toBe(0)
  })
})
