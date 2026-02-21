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

function LabelSvg({
  text,
  onChange,
  align,
  font,
  fontSize,
}: {
  text: string;
  onChange: (svg: string, height: number) => void;
  align: AlignmentType;
  font: string;
  fontSize: number;
}) {
  const ref = useRef<SVGSVGElement>(null);
  const [height, setHeight] = useState<number>(0);

  useEffect(() => {
    if (ref.current) {
      const textElement = ref.current.getElementById(
        "labelText",
      ) as SVGTextElement;
      const bbox = textElement.getBoundingClientRect();
      const measuredHeight = Math.max(bbox.height, textElement.clientHeight);
      setHeight(measuredHeight);
      onChange(ref.current.outerHTML, measuredHeight);
    }
  }, [text, height, align, font, fontSize]);

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
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width={CANVAS_WIDTH}
        height={height}
        viewBox={`0, 0, ${CANVAS_WIDTH} ${height}`}
        ref={ref}
        style={{ width: CANVAS_WIDTH, height: height }}
      >
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
            <tspan key={i} x={xPos} dy="1em">
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
  length,
  onChangeBitmap,
}: {
  text: string;
  align: AlignmentType;
  font: string;
  fontSize: number;
  length: number | null;
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

      image.src = `data:image/svg+xml;base64,${btoa(svgData)}`;
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
  const [font, setFont] = useState<string>("sans-serif");
  const [fontSize, setFontSize] = useState<number>(190);
  const [length, setLength] = useState<number | null>(null);

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
        length={length}
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
