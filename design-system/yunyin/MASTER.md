# YunYin Design System

> Native Android / Jetpack Compose source of truth. Page-specific files in `pages/` may override this document.

## Product Direction

YunYin is a content-first music player for repeated daily use. It should feel calm, immediate, immersive around artwork, and neutral everywhere else. It is not a marketing page and must not use decorative dashboard cards, neon accents, arbitrary gradients, or novelty typefaces.

## Color

### Neutral chrome

Use `AppTheme.palette` semantic roles only:

| Role | Use |
| --- | --- |
| `background` | Page and grouped-list background |
| `secondaryBackground` | Rows, cards, sheets, controls |
| `tertiaryBackground` | Raised nested controls only |
| `label` | Primary text and meaningful icons |
| `secondaryLabel` | Supporting metadata |
| `tertiaryLabel` | Low-priority metadata, never essential instructions |
| `separator` | Dividers and subtle boundaries |
| `accent` | Selected state and primary action outside immersive pages |

Light and dark appearances remain supported. Normal text must meet 4.5:1 contrast; meaningful icons and control boundaries need 3:1.

### Artwork-driven surfaces

Artist, album and player hero surfaces use AuraPlayer's exact Material Color Utilities pipeline:

1. Sample artwork into a 64x64 grid.
2. Select the seed with `QuantizerCelebi + Score`.
3. Generate `CorePalette`.
4. Dark immersive surface: `a2.tone(14)`.
5. Primary action: `a1.tone(80)` with `n1.tone(12)` content.
6. Hero mix: lerp `a1.tone(30)` and `a3.tone(24)` at 35%.
7. Cards over immersive surfaces: white at 11% alpha.

Never choose button colors independently from the active artwork palette.

## Typography

Use the system sans family through `SFPro` fallback. Do not add web fonts.

| Level | Compose token | Use |
| --- | --- | --- |
| Page title | `displayLarge` / `displayMedium` | One title per page |
| Section title | `headlineLarge` / `headlineMedium` | Section boundaries |
| Row title | `titleMedium` | Song, album, setting name |
| Body | `bodyLarge` / `bodyMedium` | Descriptions and primary metadata |
| Supporting | `bodySmall` / `labelMedium` | Artist, album, duration |

Do not hardcode font sizes in feature screens when a typography token fits. Letter spacing is zero unless matching a platform token already established in `Type.kt`.

## Layout

- Phone horizontal gutter: 20dp; compact phone: 16dp; tablet: adaptive constrained content width.
- Spacing scale: 4, 8, 12, 16, 20, 24, 32, 40dp.
- Section gaps: 24-32dp. Row internal gaps: 8-12dp.
- Android touch targets: minimum 48x48dp; visual icons may be 20-24dp inside.
- Bottom content insets must account for mini-player, navigation and gesture areas.
- Cards are only for repeated items or framed tools. Do not wrap whole page sections in cards or nest cards.
- Stable dimensions are required for artwork, toolbars, buttons and progress controls to prevent layout shift.

## Shape

Use shared `AppleShapes` / continuous rounded rectangles. Standard hierarchy:

- Artwork and compact controls: 10-12dp.
- Repeated cards: 14-16dp.
- Sheets and large framed surfaces: 20-24dp.
- Capsules only for segmented controls, pills, chips, and media controls.

Do not invent per-screen radii.

## Components

- Use `Artwork`, `MediaRow`, `TrackRow`, shared empty/error/loading states, `AnimatedSheet`, and common glass controls instead of local copies.
- Use the established SF Symbols/Material Symbols vector families consistently by hierarchy.
- Standalone icon controls require a content description and selected/pressed semantics where applicable.
- Press feedback begins within 80-150ms. The visual bounds never change layout.
- Disabled controls use native disabled semantics and reduced emphasis.
- Loading preserves final layout dimensions; avoid blank screens and unbounded spinners.

## Motion

Motion explains continuity and confirms input. High-frequency navigation should be nearly immediate.

| Purpose | Duration/spec |
| --- | --- |
| Press feedback | 100-150ms, scale 0.97-0.99 or tint/elevation |
| Icon/state swap | 120-180ms |
| Content crossfade | 180-220ms |
| Small popover | 160-220ms |
| Page transition | 240-300ms |
| Sheet enter | 280-320ms |
| Sheet exit | 200-240ms |
| Drag/release | Interruptible spring, low or no bounce |

Rules:

- Enter uses strong ease-out; on-screen movement uses ease-in-out; exits are faster than entries.
- Animate `graphicsLayer` transform/alpha where possible. Avoid per-frame layout measurement and animated blur.
- Do not animate high-frequency data such as playback position or download progress.
- Do not stagger dense song/settings lists. A single section-level transition is enough.
- Respect Android animator scale through `Motion.duration` and `Motion.springOrSnap`.
- Infinite motion must stop when offscreen, paused, battery-saving, or reduced motion is active.

## Screen Priorities

1. Playback: artwork, controls, progress, queue and lyrics must feel immediate and spatially coherent.
2. Home and search: scan speed, loading stability and row consistency.
3. Library and collection: predictable navigation, dense music lists and batch actions.
4. Artist and album: artwork-driven immersion with readable metadata.
5. Settings/downloads: quiet operational UI with clear state, not decorative cards.

## Prohibited Patterns

- One-off hardcoded colors or gradients in feature screens.
- White text on a light artwork-derived surface.
- Nested cards, decorative floating sections, or excessive glass/blur.
- Icons smaller than 48dp hit targets.
- Screen-wide blank states while optional remote enrichment loads.
- `scale(0)` entrances, long bounce animations, or repeated list stagger.
- Animating width/height for frequently updated media controls.
- Silent fallback behavior for remote artwork without diagnostics.
