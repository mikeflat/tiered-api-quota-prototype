# Quota dashboard — design direction

**Direction: "operations console."** A dark, quiet, dense interface for people
watching live traffic. Neutral graphite surfaces, one blue for action and for
"traffic flowing", and status colors kept strictly for state (OK / Near / Over,
Connected / Degraded / Unreachable). Numbers are the content, so they get a
monospaced, tabular face and the most visual weight.

Not based on reference sites (UXKIN was not connected); it builds on the
existing token names in `src/ui/index.css` and the chart rules of the dataviz
method. Mockup: `design/mockup.html` (an early sketch; where it differs, this file and `src/ui/index.css` win).

## Color

Dark is the primary theme. Every color has one role.

| Token | Value | Role |
|---|---|---|
| `--bg` | `#0b0d10` | Page background |
| `--surface-1` | `#111418` | Panels, table, header bar |
| `--surface-2` | `#171b21` | Inputs, selects, segmented controls, table header |
| `--surface-3` | `#1e232b` | Row hover, pressed controls |
| `--border` | `#242a33` | Panel and row dividers |
| `--border-strong` | `#343c48` | Input borders, focus-adjacent edges |
| `--text` | `#e8ecf1` | Primary text and figures |
| `--text-muted` | `#a3adba` | Labels, column headers, secondary text |
| `--text-faint` | `#7d8794` | Units, captions, axis ticks (≥ 4.5:1 on surface-1) |
| `--primary` | `#4c8df6` | Focus ring, links, selected segment, "Allowed" series |
| `--primary-soft` | `rgb(76 141 246 / 0.14)` | Selected-segment fill, allowed area fill |
| `--good` | `#0ca30c` → text `#3ccf5a` | OK / Connected (dot + label) |
| `--warning` | `#fab219` | Near limit / Degraded (dot + label) |
| `--critical` | `#d03b3b` → text `#f0625f` | Over limit / Unreachable / Rejected series |

Rules:
- Status colors never decorate; each appears with a dot or icon **and** a text label.
- Status chips: tinted background at 12% of the status color, 1px border at 35%, text in the lighter text step.
- No gradients, no glass, no glow. Depth comes from the surface steps.
- The chart pair (`#4c8df6` allowed, `#e5484d` rejected) passes the dataviz validator on dark (CVD ΔE 23, contrast ≥ 3:1).
- A light theme can be derived later from the same roles; it is out of scope for this proposal.

## Typography

- **IBM Plex Sans** — UI text. **IBM Plex Mono** — every number, ID, countdown, and req/s.
  Self-host via `@fontsource/ibm-plex-sans` / `@fontsource/ibm-plex-mono`; fall back to `system-ui` / `ui-monospace`.
- All figures use `font-variant-numeric: tabular-nums` so live values don't jitter.

| Step | Size / line height | Weight | Use |
|---|---|---|---|
| `xs` | 12 / 16 | 500 | Axis ticks, captions, uppercase eyebrow labels (+0.06em tracking) |
| `sm` | 13 / 18 | 400–500 | Column headers, chips, helper text |
| `base` | 14 / 20 | 400 | Table cells, controls (deliberately dense: data tool, not a reading page) |
| `md` | 16 / 24 | 500 | Panel titles |
| `lg` | 20 / 28 | 600 | Page title |
| `xl` | 28 / 32 | 500 mono | KPI values |
| `2xl` | 36 / 40 | 500 mono | Hero live rate |

## Spacing & layout

- 4px scale: 4, 8, 12, 16, 20, 24, 32, 48.
- Content width 1280px, page gutter 24px; panels separated by 16px.
- Layout: sticky top bar → banners → Traffic panel (2/3) beside Period totals as 2×2 tiles (1/3) → tenants panel full width. Below 960px everything stacks.

## Shape & depth

- Radius: 6px controls, 10px panels and tiles, 999px status pills and meters.
- No shadows on panels. One shadow only for floating layers (chart tooltip): `0 8px 24px rgb(0 0 0 / 0.45)`.

## Components

Everything here uses only data the dashboard already receives (current snapshot, the loaded tenant page, period totals); no figure is derived across tenants the page hasn't loaded.

- **Top bar** — product mark + "Quota dashboard" left; Gateway, Enforcement and "Resets in" as pills on the right, the status pills with a colored dot.
- **Traffic panel** — current Allowed / Rejected req/s as large mono figures with a series-colored dot, then the chart.
- **Traffic chart** — 60s window; allowed as a 2px blue line over a 14% flat fill, rejected as a 2px red line; 3 recessive gridlines; "−60s / now" axis; legend; crosshair + tooltip on hover.
- **Period totals tiles** — eyebrow label with a dot (allowed / rejected series color, near = warning, over = critical) and a mono value.
- **Usage cell** (tenant rows) — `used / limit (pct%)` in mono, then a 6px meter on `--surface-3` filled in the row's status color, capped at 100%.
- **Filters** — the existing Name (search icon), ID, Tier and Status fields with small labels above them, in one toolbar; "Clear filters" as a quiet button on the right.
- **Tier selector** — borderless until hover/focus, shows the limit next to the tier name; "Saving…" inline; failure as a red inline note under the cell.
- **Status chip** — tinted pill with a dot and the label (OK / Near / Over).
- **Buttons** — secondary (surface-2 + border-strong) for pager and filter actions, quiet (text only) for "Clear filters". Hover lightens one surface step; focus is a 2px `--primary` ring offset 2px; disabled at 45% opacity.
- **Banners** — strip with 3px status-colored left edge and a status dot, one-line message.
- **Stale state** — data regions drop to 55% opacity and desaturate; the "last updated" note shows in warning color with a dot.
- **Pager** — mono "Page 1 of 2" between Previous / Next buttons with chevrons; the "Showing x of y tenants" count sits left of it.

## Icons

Lucide-style outline icons as inline SVG (1.5px stroke, 16px). No emoji.

## Not built (ideas from the mockup)

- Reset-window progress bar: the snapshot gives seconds left but not the period length.
- Live "updated 1s ago" indicator while connected.
- Single search field for name or ID, and a segmented status control with counts — would change filter behavior, not just styling.
