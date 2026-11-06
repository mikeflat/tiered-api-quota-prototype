import { type TierChangeFailure, TierChangeRefused } from '../domain/tierChange.ts'
import { RequestTimedOut } from './requestWithTimeout.ts'

const statusNotFound = 404
const statusBadRequest = 400

export function tierChangeFailureOf(error: unknown): TierChangeFailure {
  if (error instanceof RequestTimedOut) {
    return { kind: 'timedOut' }
  }
  if (error instanceof TierChangeRefused) {
    return failureForStatus(error.status)
  }
  return { kind: 'unreachable' }
}

function failureForStatus(status: number): TierChangeFailure {
  switch (status) {
    case statusNotFound:
      return { kind: 'notFound' }
    case statusBadRequest:
      return { kind: 'rejected' }
    default:
      return { kind: 'serverError', status }
  }
}
