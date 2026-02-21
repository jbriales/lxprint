# Android: Font Size Slider + Full-Width Auto-Sizing

## Overview

Replace the numeric font size input with a slider, and add a "Full width" toggle that auto-computes font size so the widest text line fills the 384px canvas.

## Files to Modify

1. `android/app/src/main/java/com/example/lxprint/util/BitmapConverter.kt`
2. `android/app/src/main/java/com/example/lxprint/viewmodel/PrinterViewModel.kt`
3. `android/app/src/main/java/com/example/lxprint/ui/PrinterScreen.kt`

## Changes

### 1. BitmapConverter.kt — Add `computeFullWidthFontSize()`

Add a static helper that computes the font size needed for the widest line to fill `IMAGE_WIDTH` (384px):

```kotlin
fun computeFullWidthFontSize(text: String): Float {
    val refSize = 100f
    val paint = Paint().apply { textSize = refSize; typeface = Typeface.MONOSPACE }
    val lines = text.split("\n")
    val maxWidth = lines.maxOfOrNull { paint.measureText(it) } ?: return refSize
    if (maxWidth <= 0f) return refSize
    return refSize * IMAGE_WIDTH / maxWidth
}
```

Since `measureText` scales linearly with font size, measuring at a reference size and scaling gives the exact target.

### 2. PrinterViewModel.kt — State + Logic

**State**: Add `fullWidth: Boolean = true` to `PrinterUiState`.

**Methods**:
- `onFullWidthChanged(enabled: Boolean)` — updates flag, recomputes font size if enabling
- Modify `onFontSizeChanged()` — also sets `fullWidth = false`
- Modify `onTextChanged()` — when `fullWidth` is true, recompute font size before preview
- Modify `updatePreview()` — when `fullWidth` is true, compute font size from text, update state

### 3. PrinterScreen.kt — UI

Replace the font size `OutlinedTextField` section with:

- **Full width toggle**: `Switch` + "Full width" label, on by default
- **Slider**: `Slider` for manual font size (range ~16f–384f), disabled when fullWidth is on. Moving the slider sets `fullWidth = false`.
- **Size display**: Small read-only text showing current `fontSize` value in px

Layout: place the full-width toggle and slider in a column below the connect button, keeping the UI compact.

## Behavior

1. App launches with `fullWidth = true`, `fontSize = 190`
2. User types text → `onTextChanged()` recomputes font size via `computeFullWidthFontSize()`, updates preview
3. User drags slider → `onFontSizeChanged()` sets `fullWidth = false`, updates preview
4. User toggles fullWidth back on → font size recomputes from current text
5. Size value always visible as text label

## Verification

1. Build and run the Android app
2. Type short text (e.g. "Hi") — font size should be large, filling width
3. Type long text (e.g. "Hello World!") — font size should shrink
4. Move slider — "Full width" toggle turns off, font size is manual
5. Re-enable "Full width" — font size recomputes
6. Multi-line: widest line determines the size
