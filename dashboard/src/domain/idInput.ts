export type IdInput =
  | { readonly kind: 'empty' }
  | { readonly kind: 'valid'; readonly id: number }
  | { readonly kind: 'invalid' }

const digitsOnly = /^\d+$/

export function parseIdInput(text: string): IdInput {
  const trimmed = text.trim()
  if (trimmed === '') {
    return { kind: 'empty' }
  }
  if (!digitsOnly.test(trimmed)) {
    return { kind: 'invalid' }
  }
  const id = Number(trimmed)
  return id >= 1 && Number.isSafeInteger(id) ? { kind: 'valid', id } : { kind: 'invalid' }
}
