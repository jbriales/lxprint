import {
  useState,
  useEffect,
  useRef,
  use,
  type ChangeEventHandler,
} from "react";

import { PrinterContext } from "./context.tsx";

type AlignmentType = "left" | "center" | "right";

const CANVAS_WIDTH = 384;

// Load OCR-B font as base64 data URL for SVG embedding.
// The SVG-as-data-URI pipeline runs in an isolated context that cannot
// access page CSS @font-face, so the font must be embedded inside the SVG.
let ocrBFontDataUrl: string | null = null;
const ocrBFontPromise = fetch(
  `${import.meta.env.BASE_URL}fonts/OCR-B.otf`,
)
  .then((r) => r.arrayBuffer())
  .then((buf) => {
    const binary = Array.from(new Uint8Array(buf))
      .map((b) => String.fromCharCode(b))
      .join("");
    ocrBFontDataUrl = `data:font/opentype;base64,${btoa(binary)}`;
  })
  .catch((e) => console.error("Failed to load OCR-B font:", e));

function useOcrBFont(): string | null {
  const [dataUrl, setDataUrl] = useState<string | null>(ocrBFontDataUrl);
  useEffect(() => {
    if (dataUrl) return;
    ocrBFontPromise.then(() => setDataUrl(ocrBFontDataUrl));
  }, [dataUrl]);
  return dataUrl;
}

function LabelSvg({
  text,
  onChange,
  align,
  font,
  fontSize,
  padding,
  lineSpacing,
  fontDataUrl,
}: {
  text: string;
  onChange: (svg: string, height: number) => void;
  align: AlignmentType;
  font: string;
  fontSize: number;
  padding: number;
  lineSpacing: number;
  fontDataUrl: string | null;
}) {
  const ref = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!ref.current) return;
    // Wait for font data to load when using OCR-B
    if (font === "OCR-B" && !fontDataUrl) return;
    let cancelled = false;
    const svg = ref.current;
    const lineCount = text.split("\n").length;
    const generousHeight = Math.ceil(fontSize * lineCount * 2);

    // Set generous dimensions so all text is visible for measurement
    svg.setAttribute("width", String(CANVAS_WIDTH));
    svg.setAttribute("height", String(generousHeight));
    svg.setAttribute("viewBox", `0 0 ${CANVAS_WIDTH} ${generousHeight}`);
    svg.style.width = `${CANVAS_WIDTH}px`;
    svg.style.height = `${generousHeight}px`;

    const svgString = svg.outerHTML;
    const image = new Image();
    image.onload = () => {
      if (cancelled) return;

      // Render to temp canvas for pixel scanning
      const tempCanvas = document.createElement("canvas");
      tempCanvas.width = CANVAS_WIDTH;
      tempCanvas.height = generousHeight;
      const ctx = tempCanvas.getContext("2d");
      if (!ctx) return;
      ctx.drawImage(image, 0, 0, CANVAS_WIDTH, generousHeight);

      // Scan for actual ink bounds (alpha > 0 on transparent background)
      const data = ctx.getImageData(0, 0, CANVAS_WIDTH, generousHeight);
      let topRow = -1;
      let bottomRow = -1;

      for (let y = 0; y < generousHeight && topRow < 0; y++) {
        for (let x = 0; x < CANVAS_WIDTH; x++) {
          if (data.data[(y * CANVAS_WIDTH + x) * 4 + 3] > 0) {
            topRow = y;
            break;
          }
        }
      }

      for (let y = generousHeight - 1; y >= 0 && bottomRow < 0; y--) {
        for (let x = 0; x < CANVAS_WIDTH; x++) {
          if (data.data[(y * CANVAS_WIDTH + x) * 4 + 3] > 0) {
            bottomRow = y;
            break;
          }
        }
      }

      if (topRow < 0 || bottomRow < 0) {
        onChange(svgString, 1);
        return;
      }

      const inkHeight = bottomRow - topRow + 1;
      const croppedHeight = inkHeight + 2 * padding;
      const cropY = Math.max(0, topRow - padding);

      // Set tight viewBox directly on DOM, then serialize
      svg.setAttribute("height", String(croppedHeight));
      svg.setAttribute("viewBox", `0 ${cropY} ${CANVAS_WIDTH} ${croppedHeight}`);
      svg.style.height = `${croppedHeight}px`;

      onChange(svg.outerHTML, croppedHeight);
    };
    image.src = `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svgString)))}`;

    return () => {
      cancelled = true;
    };
  }, [text, align, font, fontSize, padding, lineSpacing, fontDataUrl]);

  const [xPos, textAnchor] = ((): [number, "start" | "middle" | "end"] => {
    switch (align) {
      case "left":
        return [0, "start"];
      case "center":
        return [CANVAS_WIDTH / 2, "middle"];
      case "right":
        return [CANVAS_WIDTH, "end"];
      default:
        return [0, "start"];
    }
  })();

  return (
    <div style={{ visibility: "hidden", position: "absolute" }}>
      <svg xmlns="http://www.w3.org/2000/svg" ref={ref}>
        {font === "OCR-B" && fontDataUrl && (
          <defs>
            <style>{`@font-face { font-family: 'OCR-B'; src: url('${fontDataUrl}') format('opentype'); }`}</style>
          </defs>
        )}
        <text
          x={xPos}
          y="0"
          id="labelText"
          style={{
            textAnchor: textAnchor,
            fontFamily: font,
            fontSize: `${fontSize}px`,
          }}
        >
          {text.split("\n").map((x, i) => (
            <tspan key={i} x={xPos} dy={i === 0 ? "1em" : `${lineSpacing}em`}>
              {x}
            </tspan>
          ))}
        </text>
      </svg>
    </div>
  );
}

function LabelCanvas({
  text,
  align,
  font,
  fontSize,
  padding,
  lineSpacing,
  length,
  fontDataUrl,
  onChangeBitmap,
}: {
  text: string;
  align: AlignmentType;
  font: string;
  fontSize: number;
  padding: number;
  lineSpacing: number;
  length: number | null;
  fontDataUrl: string | null;
  onChangeBitmap: (x: ImageData) => void;
}) {
  const ref = useRef<HTMLCanvasElement>(null);

  const [svgData, setSvgData] = useState<string>("");
  const [height, setHeight] = useState<number>(0);

  const onSvgChange = (s: string, h: number) => {
    setSvgData(s);
    setHeight(h);
  };

  useEffect(() => {
    const image = new Image();

    if (ref.current) {
      const context = ref.current.getContext("2d");
      if (!context) throw new Error("No context from canvas");
      image.onload = () => {
        if (ref.current) {
          context.clearRect(0, 0, ref.current.width, ref.current.height);
          if (!length) {
            // Auto-length: draw 1:1 since SVG and canvas share the same width
            context.drawImage(
              image,
              0,
              0,
              ref.current.width,
              ref.current.height,
            );
          } else {
            // Fixed-length: center the content
            const svgAspect = CANVAS_WIDTH / height;
            const canvasAspect = ref.current.width / ref.current.height;
            if (svgAspect > canvasAspect) {
              // Content has wider aspect ratio, so center vertically
              const virtualHeight =
                (ref.current.width / CANVAS_WIDTH) * height;
              const offset = (ref.current.height - virtualHeight) / 2;
              context.drawImage(
                image,
                0,
                offset,
                ref.current.width,
                virtualHeight,
              );
            } else {
              // Content has taller aspect ratio. Position based on align prop.
              const virtualWidth =
                (ref.current.height / height) * CANVAS_WIDTH;
              const offset =
                align == "left"
                  ? 0
                  : (ref.current.width - virtualWidth) /
                    (align == "right" ? 1 : 2);
              context.drawImage(
                image,
                offset,
                0,
                virtualWidth,
                ref.current.height,
              );
            }
          }
          onChangeBitmap(
            context.getImageData(0, 0, ref.current.width, ref.current.height),
          );
        }
      };

      image.src = `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svgData)))}`;
    }
  }, [svgData, length]);

  return (
    <>
      <LabelSvg
        text={text}
        onChange={(s, h) => onSvgChange(s, h)}
        align={align}
        font={font}
        fontSize={fontSize}
        padding={padding}
        lineSpacing={lineSpacing}
        fontDataUrl={fontDataUrl}
      />
      <div
        style={{
          border: "1px solid black",
          paddingTop: "64px",
          paddingBottom: "20px",
          paddingLeft: "32px",
          paddingRight: "32px",
          backgroundColor: "lightgrey",
        }}
      >
        <canvas
          ref={ref}
          width={CANVAS_WIDTH}
          height={length || height}
          style={{
            margin: 0,
            backgroundColor: "white",
          }}
        />
      </div>
    </>
  );
}

function TextAlignButton({
  val,
  text,
  align,
  onChangeHandler,
}: {
  val: AlignmentType;
  text: string;
  align: AlignmentType;
  onChangeHandler: ChangeEventHandler<HTMLInputElement>;
}) {
  return (
    <label htmlFor={val}>
      <input
        type="radio"
        name="align"
        value={val}
        id={val}
        checked={align === val}
        onChange={onChangeHandler}
      />
      {text}
    </label>
  );
}

function LengthSelect({
  length,
  setLength,
}: {
  length: number | null;
  setLength: (x: number | null) => void;
}) {
  return (
    <select
      value={length || "auto"}
      onChange={(e) => setLength(parseInt(e.target.value) || null)}
    >
      <option value="auto">Auto</option>
      <option value="230">28mm</option>
    </select>
  );
}

function FontSelect({
  font,
  setFont,
}: {
  font: string;
  setFont: (x: string) => void;
}) {
  return (
    <select value={font} onChange={(e) => setFont(e.target.value)}>
      <option value="OCR-B">OCR-B</option>
      <option value="serif">serif</option>
      <option value="sans-serif">sans-serif</option>
      <option value="cursive">cursive</option>
      <option value="monospace">monospace</option>
      <option value="fantasy">fantasy</option>
    </select>
  );
}

function TextAlign({
  align,
  setAlign,
}: {
  align: AlignmentType;
  setAlign: (x: AlignmentType) => void;
}) {
  const onOptionChange: ChangeEventHandler<HTMLInputElement> = (e) => {
    if (align === "left" || align === "center" || align === "right")
      setAlign(e.target.value as AlignmentType);
  };

  return (
    <div>
      <TextAlignButton
        val="left"
        text="Left"
        align={align}
        onChangeHandler={onOptionChange}
      />
      <TextAlignButton
        val="center"
        text="Center"
        align={align}
        onChangeHandler={onOptionChange}
      />
      <TextAlignButton
        val="right"
        text="Right"
        align={align}
        onChangeHandler={onOptionChange}
      />
    </div>
  );
}

export function LabelMaker() {
  const [text, setText] = useState("Hello");
  const [align, setAlign] = useState<"left" | "center" | "right">("left");
  const [bitmap, setBitmap] = useState<ImageData>();
  const [font, setFont] = useState<string>("OCR-B");
  const [fontSize, setFontSize] = useState<number>(190);
  const [padding, setPadding] = useState<number>(4);
  const [lineSpacing, setLineSpacing] = useState<number>(1.1);
  const [length, setLength] = useState<number | null>(null);
  const fontDataUrl = useOcrBFont();

  const { printer, printerStatus } = use(PrinterContext);

  const canPrint = !!printer && printerStatus.state == "connected" && !!bitmap;

  const print = () => {
    if (canPrint) printer.print(bitmap);
  };

  return (
    <div style={{ clear: "both" }}>
      <LabelCanvas
        text={text}
        align={align}
        font={font}
        fontSize={fontSize}
        padding={padding}
        lineSpacing={lineSpacing}
        length={length}
        fontDataUrl={fontDataUrl}
        onChangeBitmap={(x: ImageData) => setBitmap(x)}
      />
      <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "0.5em" }}>
        <TextAlign align={align} setAlign={setAlign} />
        <FontSelect font={font} setFont={setFont} />
        <label>
          Size{" "}
          <input
            type="number"
            value={fontSize}
            onChange={(e) => setFontSize(Number(e.target.value))}
            min={8}
            max={200}
            style={{ width: "4em" }}
          />
          px
        </label>
        <label>
          Pad{" "}
          <input
            type="range"
            value={padding}
            onChange={(e) => setPadding(Number(e.target.value))}
            min={0}
            max={50}
          />{" "}
          {padding}px
        </label>
        <label>
          Line spacing{" "}
          <input
            type="range"
            value={lineSpacing}
            onChange={(e) => setLineSpacing(Number(e.target.value))}
            min={0.8}
            max={2}
            step={0.05}
          />{" "}
          {lineSpacing}em
        </label>
        <LengthSelect length={length} setLength={setLength} />
        <div>
          <textarea
            value={text}
            onChange={(x) => setText(x.target.value)}
            rows={4}
            cols={40}
          />
        </div>
        <button onClick={print} disabled={!canPrint}>
          Print
        </button>
      </div>
    </div>
  );
}
