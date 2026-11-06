export interface TierOption {
  readonly name: string
  readonly limit: number
}

export interface ChangedTier {
  readonly id: number
  readonly name: string
  readonly tier: string
  readonly limit: number
}
