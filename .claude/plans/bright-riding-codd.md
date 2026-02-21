# Fix: Text vertically clipped when printing

## Root Cause

In `src/Label.tsx`, the `LabelSvg` component uses `getBoundingClientRect()` (line 33) to measure the text element and set the SVG dimensions. This creates a **shrinking feedback loop**:

1. Text is positioned at `y="0"` with `<tspan dy="1em">`, placing the baseline at y=16px. Ascenders start at ~y=2px, descenders end at ~y=20px.
2. `getBoundingClientRect()` returns the **visible** (clipped) bounds in screen coordinates.
3. The height is used for `viewBox="0, 0, W, H"` — if H=18, content at y=18–20 is clipped.
4. Next render: clipped content → smaller `getBoundingClientRect` → smaller viewBox → more clipping → **loop converges toward zero height**.
5. The shrunk SVG is sent to the canvas → printer outputs only the top sliver of text.

## Fix

Replace `getBoundingClientRect()` with `getBBox()`, which returns the **geometric** bounding box in SVG coordinates, independent of viewBox clipping. Also track the viewBox origin (`minX`, `minY`) so the viewBox frames the text precisely.

## Changes — `src/Label.tsx` only

### 1. Add `minX`/`minY` state (after line 26)
```tsx
const [minX, setMinX] = useState<number>(0);
const [minY, setMinY] = useState<number>(0);
```

### 2. Replace measurement useEffect (lines 28–38)
```tsx
useEffect(() => {
  if (ref.current) {
    const textElement = ref.current.getElementById("labelText") as SVGTextElement;
    const bbox = textElement.getBBox();
    setMinX(bbox.x);
    setMinY(bbox.y);
    setWidth(bbox.width);
    setHeight(bbox.height);
    onChange(ref.current.outerHTML, bbox.width, bbox.height);
  }
}, [text, minX, minY, width, height, align, font]);
```

### 3. Update x-position to be relative to viewBox origin (lines 40–51)
```tsx
case "left":   return [minX, "start"];
case "center": return [minX + width / 2, "middle"];
case "right":  return [minX + width, "end"];
default:       return [minX, "start"];
```

### 4. Update viewBox (line 59)
```tsx
viewBox={`${minX} ${minY} ${width} ${height}`}
```

## Verification

1. Run the dev server and open the web app
2. Type single-line and multi-line text
3. Verify the canvas preview shows full text (no clipping)
4. Print and confirm the full text appears on the label
5. Test all three alignments (left, center, right)
