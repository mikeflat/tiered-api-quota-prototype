import { describe, expect, it } from 'vitest'
import { parseIdInput } from '../../src/domain/idInput.ts'

describe('parseIdInput', () => {
  it('blank_is_empty', () => {
    expect(parseIdInput('')).toEqual({ kind: 'empty' })
    expect(parseIdInput('   ')).toEqual({ kind: 'empty' })
  })

  it('whole_number_from_one_is_valid', () => {
    expect(parseIdInput('1')).toEqual({ kind: 'valid', id: 1 })
    expect(parseIdInput('15')).toEqual({ kind: 'valid', id: 15 })
  })

  it('surrounding_spaces_are_ignored', () => {
    expect(parseIdInput(' 3 ')).toEqual({ kind: 'valid', id: 3 })
  })

  it('letters_are_invalid', () => {
    expect(parseIdInput('acme')).toEqual({ kind: 'invalid' })
    expect(parseIdInput('1a')).toEqual({ kind: 'invalid' })
  })

  it('zero_and_negative_are_invalid', () => {
    expect(parseIdInput('0')).toEqual({ kind: 'invalid' })
    expect(parseIdInput('-1')).toEqual({ kind: 'invalid' })
  })

  it('decimal_and_exponent_are_invalid', () => {
    expect(parseIdInput('1.5')).toEqual({ kind: 'invalid' })
    expect(parseIdInput('1e3')).toEqual({ kind: 'invalid' })
    expect(parseIdInput('+1')).toEqual({ kind: 'invalid' })
  })

  it('unsafe_integer_is_invalid', () => {
    expect(parseIdInput('9007199254740993')).toEqual({ kind: 'invalid' })
  })
})
