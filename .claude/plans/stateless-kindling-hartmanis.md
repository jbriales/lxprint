# Fix Android App Excessive Blank Space When Printing

## Problem

The Android app produces much more blank space before and after the text than the web app. The web app's canvas tightly wraps the text (measured via `getBoundingClientRect()`), while the Android app adds excess space above and below.

## Root Cause

In `BitmapConverter.textToBitmap()`, two issues:

1. **Top**: First baseline at `y = fontSpacing` leaves `descent + leading` (~50-60px) of blank above the text
2. **Bottom**: `+ fontSpacing * 0.5` adds ~100-115px extra below the text

## Fix

**Single file**: `android/app/src/main/java/com/example/lxprint/util/BitmapConverter.kt`

Modify `textToBitmap()` to use `paint.fontMetrics` for tight text wrapping:

```kotlin
val fm = paint.fontMetrics
val height = (-fm.ascent + lineHeight * (lines.size - 1) + fm.descent).toInt().coerceAtLeast(1)
```

```kotlin
val y = -fm.ascent + lineHeight * i   // was: lineHeight * (i + 1)
```

This places the first line's ascenders at y=0 and sizes the bitmap to exactly fit the text.

## Verification

1. Build and run the Android app
2. Compare preview and print output to the web app with the same text/font size
3. Test single-line and multi-line labels
