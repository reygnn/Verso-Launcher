# MATERIAL3RULES.md

Binding rules for Material 3 conformance.

This file has two parts:

- **Part A — General**: applies to *every* Compose-M3 app. Copy 1:1 into
  any project.
- **Part B — Verso Launcher-specific**: extends and concretises Part A
  for this repo. When porting to another project, replace this part — the
  semantic role assignments, named colour constants, and documented hex
  exceptions are project-dependent.

This file supplements `CLAUDE.md` — it does not replace it.

---

# Part A — General (any Compose-M3 app)

## A1. Theme layers

There are **two layers** that act in this order:

1. **XML app theme** (`res/values/themes.xml`, `res/values-night/themes.xml`)
   — purely functional: only covers the pre-Compose window (splash, window
   background, edge-to-edge) and renders **no** UI in a pure Compose app.
   A lean platform theme as parent is therefore enough:
   `@android:style/Theme.DeviceDefault.Light.NoActionBar` in `values/`,
   `@android:style/Theme.DeviceDefault.NoActionBar` in `values-night/`.
   No `com.google.android.material`/`appcompat` needed. Never
   `android:Theme.Material.*` (Android-5-Material, obsolete — DeviceDefault
   is the modern OEM default). Carries the platform splash attributes
   (see A2). Only when real Material views / XML widgets enter the picture
   does `Theme.Material3.*` + `com.google.android.material` become necessary.
2. **Compose theme** (`MaterialTheme { ... }`) — fully populated
   `ColorScheme`. Compose paints over everything after `setContent {}` anyway,
   so the XML theme can stay purely functional (splash + edge-to-edge
   background).

## A2. Splash screen

- With `minSdk ≥ 31` (Android 12) the **platform** delivers the splash
  natively. No `androidx.core:core-splashscreen` — the library is only a
  backport for API < 31.
- Configuration exclusively via theme attributes (with the `android:`
  prefix) directly on the app theme: `android:windowSplashScreenBackground`,
  `android:windowSplashScreenAnimatedIcon`,
  `android:windowSplashScreenIconBackgroundColor`. No separate splash
  theme, no `installSplashScreen()`, no `postSplashScreenTheme`.
- The system splash fades automatically on the first Compose frame — no
  code in `onCreate` needed. Only if you want to deliberately hold it or
  customise the exit animation: platform `getSplashScreen()` (API 31+).
- The splash icon is its own vector drawable, **never**
  `@android:drawable/*`. Adaptive-icon foreground and splash icon can use
  the same drawable.
- Splash background colours in `res/values/colors.xml`, centralised,
  separate for light and dark, no hex directly in the theme.

## A3. Launcher icon

- Adaptive icon is mandatory: `mipmap-anydpi-v26/ic_launcher.xml` +
  `ic_launcher_round.xml`, each with `<background>`, `<foreground>`,
  **and** `<monochrome>` (themed icons on Android 13+).
- Foreground vector uses the 108×108 viewport convention and respects the
  safe zone (~66 dp centred).
- The manifest sets `android:icon` **and** `android:roundIcon`.
- With `minSdk ≥ 26`, `mipmap-*dpi` PNGs are redundant — adaptive icon
  everywhere.

## A4. ColorScheme — fully populate

`darkColorScheme()` / `lightColorScheme()` have defaults for unset roles
that land in purple tones and wreck the branding (typically visible on
OutlinedTextField borders, SegmentedButton, dividers, elevated cards).
**Every** one of these roles must be set in both schemes:

- Primary family: `primary`, `onPrimary`, `primaryContainer`,
  `onPrimaryContainer`, `inversePrimary`
- Secondary family: `secondary`, `onSecondary`, `secondaryContainer`,
  `onSecondaryContainer`
- Tertiary family: `tertiary`, `onTertiary`, `tertiaryContainer`,
  `onTertiaryContainer`
- Backgrounds: `background`, `onBackground`
- Surfaces: `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`,
  `surfaceTint`, `surfaceBright`, `surfaceDim`
- Surface container tiers: `surfaceContainerLowest`, `surfaceContainerLow`,
  `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`
- Inverse: `inverseSurface`, `inverseOnSurface`
- Structure lines: `outline`, `outlineVariant`, `scrim`
- Error family: `error`, `onError`, `errorContainer`, `onErrorContainer`

## A5. Hardcoded colours — rule

- `Color(0x…)` is allowed **exclusively** in the theme file
  (`ui/theme/Theme.kt` or equivalent).
- In screens, only `MaterialTheme.colorScheme.*` or named constants from
  the theme file.
- Exceptions must be **deliberately documented**, not silently present
  (see Part B).

Pre-commit check (adjust path as needed):
```bash
git grep -nE 'Color\(0x[0-9A-Fa-f]+\)' app/src/main/java/**/ui/screens
```
Expected: no matches.

## A6. Dark/light correctness

Three common traps:

1. **Using `Color.White` / `Color.Black` directly** — breaks in the other
   mode. Use `onSurface`, `inverseSurface`, or a themed colour instead.
2. **Alpha values on a light background** — `.copy(alpha = 0.x)` on a hex
   value without theme reference can become invisible in light mode.
   Prefer `onSurfaceVariant` as a base and use alpha sparingly.
3. **Canvas drawings** — `Color` must be read from `MaterialTheme`
   **outside** the canvas lambda (the canvas lambda is not a composable
   scope). Pattern:
   ```kotlin
   val lineColor = MaterialTheme.colorScheme.primary
   Canvas(...) { drawPath(path, lineColor, ...) }
   ```

## A7. Semantic roles — a choice

Material 3 has no `warning` / `success` / `info` role. Two ways to solve
this — decide **once** per app and document it (in Part B):

1. **Repurpose M3 roles**: e.g. `tertiary` = success, `secondary` =
   accent. Works when `primary` already carries the main action colour
   and the other roles are free.
2. **Named theme constants** for states that sit outside the M3 role set
   (e.g. amber for "in progress", blue for "mode B"). Must be centralised
   in the theme file, not in screens.

For a third semantic accent: **first** check whether an M3 role is
unused, **then** add it as a constant in the theme file.

## A8. Dependencies

These deps belong to the M3 baseline:

- `androidx.compose.material3:material3` (via Compose BOM)
- `androidx.compose.material:material-icons-extended` (optional, if
  Material icons are used)

Not needed in a pure Compose app:

- `com.google.android.material:material` — only required for a
  `Theme.Material3.*` XML parent; the lean platform theme (A1) does
  without it.
- `androidx.core:core-splashscreen` — with `minSdk ≥ 31`, the platform
  handles the splash (A2).

---

# Part B — Verso Launcher-specific

**Verso does not use Compose or Material 3.** The release APK carries no
dependencies at all (see `CLAUDE.md`, hard rule 1), so there is no `MaterialTheme`,
no `ColorScheme` and no `material3` artifact. Part A is kept verbatim for the
family, but in this repo it applies only where a rule has a platform-View
equivalent. Should Compose ever be introduced, Part A applies in full and this
part must be rewritten first.

## B1. Semantic role assignments

No M3 roles. The UI is text over the user's wallpaper, so colour comes from
contrast, not from a scheme:

| Use                                  | Source                                              |
|--------------------------------------|-----------------------------------------------------|
| Home text (clock, date, favorites)   | white, `HomeStyle.TEXT_COLOR`                       |
| Readability on any wallpaper         | tonal outline: `HomeStyle.tonalOutlineColor` (white rim for dark text, black for light, eased in between) |
| Drawer text, dimmed hints            | `fgColor` / `fgColorDim` in `MainActivity`          |
| Pressed state of a favorite          | `HomePage.pressableText` (derived from `TEXT_COLOR`) |
| Charging / battery-protection marks  | outlined vector drawables (`ic_bolt_*`, `ic_shield_*`) |

Colour constants live in `HomeStyle.kt` (home) and `MainActivity`'s `fgColor` /
`fgColorDim` (drawer) — not scattered as hex across views.

## B2. Documented exceptions to Part A

- **A1 (theme parent):** `VersoTheme` uses `@android:style/Theme.Material.NoActionBar`,
  which A1 calls obsolete in favour of `Theme.DeviceDefault.*`. Kept on purpose
  (decided 2026-10-13): the window is a transparent wallpaper surface and every text
  colour and typeface is set in code, so the parent only decides the accent of the
  command line's cursor and selection handles. The rename dialog does not follow the
  app theme at all — `RenameDialog` pins `Theme_Material_Dialog_Alert`, which keeps it
  dark on every device, as its light-grey field text requires. Moving the dialog to
  DeviceDefault would need that text colour tied to the theme and a check on both a
  Pixel and a Samsung (light dialog backgrounds).
- **A1 (no night theme):** there is no `values-night/`: the window is always the
  wallpaper with light text and an outline; dark/light mode does not change it.
- **A2 (splash):** no splash attributes are set; a HOME app is started by the system
  and shows the wallpaper immediately.
- **A3 (launcher icon):** `drawable/ic_launcher.xml`, not an adaptive `mipmap-anydpi`
  icon with background/foreground/monochrome.
- **A4–A8:** not applicable — no Compose, no `ColorScheme`, no M3 dependency. The
  spirit of A5/A6 still holds: no ad-hoc colours outside the style constants, and
  nothing that only works on one wallpaper brightness (the outline exists for that).

## B3. Dynamic colour policy

Not used. The wallpaper itself is the background; there is no themed surface to tint.
