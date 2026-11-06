export function LastUpdatedNote({ id, secondsAgo }: { id: string; secondsAgo: number }) {
  return (
    <p id={id} className="last-updated muted">
      Showing last known data, last updated {secondsAgo} s ago
    </p>
  )
}
