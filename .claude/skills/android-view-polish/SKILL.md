---
name: android-view-polish
description: Make this app's (org.nasonmobile.immich) Android UI look more modern, polished, or "accattivante" — rounded cards, better buttons, color/spacing system, subtle animations, icons, dark mode. Use this whenever the user asks to improve the app's graphics, design, look, UI, style, or visual appeal, even if they paste in generic 2026 Android advice (Compose, Material 3, Lottie, Coil, Konfetti, MPAndroidChart) — that advice assumes Gradle and does NOT apply here; this skill gives the dependency-free equivalent that actually compiles with this repo's build.
---

# Android View polish, without Gradle

## The constraint that changes everything

This app (`apk/`) builds with **aapt2 + javac + d8 + apksigner only** (`apk/build.sh`) — no Gradle, no
Maven/AAR dependency resolution, no Kotlin, no Jetpack Compose. The whole UI is assembled by hand in Java
(`MainActivity.buildUi()`: `LinearLayout`/`TextView`/`Button`/`CheckBox`/`Spinner`/… created with `new` and
added with `addView`, no XML layouts). `minSdk` 26, `targetSdk` 28 on purpose (legacy storage).

This means: **any advice built around Compose, Lottie(-Compose), Coil, Konfetti, or MPAndroidChart does not
apply.** Those ship as AARs with their own resources and transitive dependencies — pulling one in means
first building AAR-extraction, resource-merging and manifest-merging into `apk/build.sh`, which is exactly
the complexity this project's build was designed to avoid. If the user pastes that kind of advice, say so
briefly, then reach for the plain-framework equivalent below — it gets a comparable visual result with zero
new dependencies, compiled the same way as everything else in `apk/src/`.

Everything below is plain `android.*`/`javax.*` API, available since API 21-26, draws with code (no bitmap
assets, no `res/` resource compiler step beyond what `aapt2` already does for the manifest). Favor drawing
with code (`GradientDrawable`, `Path`, `ColorStateList`) over adding `.png`/`.xml` resource files — it's one
less moving part in a build that has none.

## Where this applies in the repo

Everything lives in `apk/src/org/nasonmobile/immich/MainActivity.java`: the color constants (`C_TEXT`,
`C_MUTED`, `C_OK`, `C_WARN`, `C_ERR`, `C_ACCENT`) and the small helpers (`text()`, `lp()`, `button()`, `dp()`)
near the top of the class are the existing style system — extend those rather than inlining one-off styling
calls inside `buildUi()`. After any change: `apk/build.sh` compiles it (javac + d8, no Gradle needed), and
`apk/install.sh -s SERIAL` (or `adb install -r dist/immich-server.apk`) puts it on a phone. Installing
force-stops the running server for a short time — warn before doing it on a phone that's in daily use (see
the repo's own operational notes if present, e.g. a CLAUDE.md or memory file).

## Recipes

### 1. Rounded "card" sections instead of a flat list

Replace bare `LinearLayout` sections with a rounded, subtly-elevated container. One drawable builder, reused
everywhere:

```java
private Drawable cardBg(int fill, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.RECTANGLE);
    d.setColor(fill);
    d.setCornerRadius(dp(radiusDp));
    return d;
}
...
LinearLayout card = new LinearLayout(this);
card.setOrientation(LinearLayout.VERTICAL);
card.setPadding(dp(16), dp(14), dp(16), dp(14));
card.setBackground(cardBg(Color.WHITE, 16));
card.setElevation(dp(2)); // real framework elevation -> a real soft shadow, no asset needed
```

Group each existing section (Updates, Battery, Backup, …) into one of these instead of letting them run
together on the bare scroll background. Keep a consistent vertical rhythm: pick one spacing scale (e.g. 4 /
8 / 12 / 16 / 24dp, matching the existing `lp(n)` helper) and stop using ad hoc margins.

### 2. Buttons and checkboxes that feel pressed, not just clicked

A flat `GradientDrawable` has no feedback. Wrap it in a `RippleDrawable` (API 21+, so fine here) driven by a
`ColorStateList`:

```java
private Drawable pressable(int fill, int radiusDp) {
    GradientDrawable base = (GradientDrawable) cardBg(fill, radiusDp);
    int ripple = Color.argb(60, 0, 0, 0);
    return new RippleDrawable(ColorStateList.valueOf(ripple), base, null);
}
...
Button b = new Button(this);
b.setBackground(pressable(C_ACCENT, 12));
b.setTextColor(Color.WHITE);
```

For an outline/secondary button, use `GradientDrawable.setStroke(dp(1.5f), C_ACCENT)` with a transparent or
white fill instead of a solid one, same ripple wrapper.

For checkboxes/switches, `CompoundButton.setButtonTintList(ColorStateList.valueOf(C_ACCENT))` recolors the
stock widget without replacing it — cheap and consistent with the rest of the palette.

### 3. Micro-interactions with ObjectAnimator/ValueAnimator

No Compose animation APIs needed — `ObjectAnimator` on a plain `View` covers fades, color crossfades and
the progress bar:

```java
ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), newValue)
    .setDuration(300)
    .start(); // smooths the jumpy progress bar instead of snapping

ValueAnimator colorFade = ValueAnimator.ofArgb(fromColor, toColor);
colorFade.addUpdateListener(a -> status.setTextColor((int) a.getAnimatedValue()));
colorFade.setDuration(250);
colorFade.start(); // status color transition (e.g. C_WARN -> C_OK) instead of an instant cut
```

Use this sparingly on state changes that already happen in `refresh()` (status color, storage bar) — it's
the cheapest way to make the screen feel alive without new widgets.

### 4. Simple icons without bitmap assets

A compiled vector drawable (`res/drawable/*.xml` with `<vector>`, built by `aapt2` like the app's existing
`ic_launcher`/`ic_stat`) covers small glyphs (checkmark, warning triangle, arrow) without shipping PNGs or a
whole icon-font library. For anything more custom, draw directly with `Canvas`/`Path` in a small custom
`View` — e.g. a battery pill or a status dot — fully code, zero resources, trivially themeable by passing a
color.

### 5. Light/dark without AppCompat

There's no AndroidX here, so there's no `DayNight` theme. Check the system setting directly and pick palette
constants at runtime instead of relying on resource qualifiers:

```java
boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
    == Configuration.UI_MODE_NIGHT_YES;
```

Branch the existing `C_*` constants (and card/background fills) on that boolean once, at the top of
`buildUi()`/`onCreate()`, rather than threading a theme object through every helper.

### 6. A cohesive palette, not just "what looked okay at the time"

The existing constants (`C_TEXT #1F2430`, `C_MUTED #5B6376`, `C_OK #1B7F3B`, `C_WARN #B26A00`, `C_ERR
#B3261E`, `C_ACCENT #3B4BA8`) are a reasonable base — keep using semantic names (`C_OK`/`C_WARN`/`C_ERR`) for
state rather than literal colors, and reuse `C_ACCENT` for every primary action so the app reads as one
system rather than per-section choices. If the palette itself needs refreshing, change it in one place (the
constants block) and everything downstream follows.

## What "done" looks like

- Compiles with the unmodified `apk/build.sh` (no new jars, no manifest permission changes for this reason
  alone).
- No new asset files unless they're small compiled vector XML — prefer code-drawn shapes.
- The screen reads as grouped cards with one consistent spacing/color system, not a longer flat list.
- Something actually moves on a state change (progress, a color, a section appearing) — that's most of what
  "accattivante" means in practice for a utility screen like this one.
