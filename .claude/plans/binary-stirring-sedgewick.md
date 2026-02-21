# Replace text input with preview-only editing in Android app

## Context

Currently `PrinterScreen` has a separate `OutlinedTextField` ("Label text") and a bitmap preview below it. The user wants a single preview area that also acts as the text input — tapping it opens the keyboard, typing updates the preview in real-time, and no separate text field is visible.

## Plan

### File: `android/app/src/main/java/com/example/lxprint/ui/PrinterScreen.kt`

1. **Remove** the `OutlinedTextField` block (lines 122–130: the "Label text" field)
2. **Replace** the current preview `Image` with a `BasicTextField` that uses `decorationBox` to render only the preview bitmap:
   - `textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp)` — invisible text
   - `cursorBrush = SolidColor(Color.Transparent)` — invisible cursor
   - `decorationBox` renders the preview `Image` (or an empty placeholder box when no preview)
   - Call `innerTextField()` inside the decoration box (required for input to work)
3. **Add imports**: `BasicTextField`, `TextStyle`, `SolidColor`, `alpha`, `defaultMinSize`, `sp`

No changes to ViewModel or BitmapConverter.

## Verification

- Build the app
- No separate "Label text" field visible
- Tapping the preview area opens the keyboard
- Typing updates the preview in real-time
