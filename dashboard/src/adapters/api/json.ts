export type JsonObject = Record<string, unknown>

export class UnexpectedResponseBody extends Error {
  constructor(what: string) {
    super(`Unexpected response body: ${what}`)
    this.name = 'UnexpectedResponseBody'
  }
}

export function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function objectAt(source: JsonObject, key: string): JsonObject {
  const value = source[key]
  if (!isObject(value)) {
    throw new UnexpectedResponseBody(`${key} is not an object`)
  }
  return value
}

export function numberAt(source: JsonObject, key: string): number {
  const value = source[key]
  if (typeof value !== 'number') {
    throw new UnexpectedResponseBody(`${key} is not a number`)
  }
  return value
}

export function nullableNumberAt(source: JsonObject, key: string): number | null {
  return source[key] === null ? null : numberAt(source, key)
}

export function stringAt(source: JsonObject, key: string): string {
  const value = source[key]
  if (typeof value !== 'string') {
    throw new UnexpectedResponseBody(`${key} is not a string`)
  }
  return value
}

export function booleanAt(source: JsonObject, key: string): boolean {
  const value = source[key]
  if (typeof value !== 'boolean') {
    throw new UnexpectedResponseBody(`${key} is not a boolean`)
  }
  return value
}

export function arrayAt(source: JsonObject, key: string): unknown[] {
  const value = source[key]
  if (!Array.isArray(value)) {
    throw new UnexpectedResponseBody(`${key} is not an array`)
  }
  return value
}

export function asObject(value: unknown, what: string): JsonObject {
  if (!isObject(value)) {
    throw new UnexpectedResponseBody(`${what} is not an object`)
  }
  return value
}

export async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    throw new UnexpectedResponseBody('not JSON')
  }
}
