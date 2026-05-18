import cv2
import numpy as np
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Tuple

try:
    import pytesseract
    TESSERACT_AVAILABLE = True
except ImportError:
    TESSERACT_AVAILABLE = False


@dataclass
class DetectedElement:
    label: str
    x: int
    y: int
    width: int
    height: int
    confidence: float
    center: Tuple[int, int] = field(init=False)

    def __post_init__(self):
        self.center = (self.x + self.width // 2, self.y + self.height // 2)


class ElementDetector:
    def __init__(self, confidence_threshold: float = 0.8):
        self.confidence_threshold = confidence_threshold

    # ------------------------------------------------------------------
    # Template matching
    # ------------------------------------------------------------------

    def find_by_template(
        self,
        screen: np.ndarray,
        template_path: str,
        label: str = "element",
    ) -> List[DetectedElement]:
        """Find all on-screen occurrences of a saved template image."""
        template = cv2.imread(str(template_path))
        if template is None:
            raise FileNotFoundError(f"Template not found: {template_path}")
        return self._match_template(screen, template, label)

    def find_by_template_image(
        self,
        screen: np.ndarray,
        template: np.ndarray,
        label: str = "element",
    ) -> List[DetectedElement]:
        """Find all on-screen occurrences of an in-memory template array."""
        return self._match_template(screen, template, label)

    def _match_template(
        self,
        screen: np.ndarray,
        template: np.ndarray,
        label: str,
    ) -> List[DetectedElement]:
        screen_gray = cv2.cvtColor(screen, cv2.COLOR_BGR2GRAY)
        tmpl_gray = cv2.cvtColor(template, cv2.COLOR_BGR2GRAY)
        h, w = tmpl_gray.shape

        result = cv2.matchTemplate(screen_gray, tmpl_gray, cv2.TM_CCOEFF_NORMED)
        ys, xs = np.where(result >= self.confidence_threshold)
        boxes = list(zip(xs.tolist(), ys.tolist()))
        boxes = self._nms(boxes, w, h, result)

        return [
            DetectedElement(
                label=label,
                x=x,
                y=y,
                width=w,
                height=h,
                confidence=float(result[y, x]),
            )
            for x, y in boxes
        ]

    def _nms(
        self,
        boxes: List[Tuple[int, int]],
        w: int,
        h: int,
        result: np.ndarray,
        overlap_thresh: float = 0.3,
    ) -> List[Tuple[int, int]]:
        """Non-maximum suppression to collapse overlapping matches."""
        if not boxes:
            return []

        rects = np.array([[x, y, x + w, y + h] for x, y in boxes], dtype=float)
        scores = np.array([result[y, x] for x, y in boxes])
        x1, y1, x2, y2 = rects[:, 0], rects[:, 1], rects[:, 2], rects[:, 3]
        areas = (x2 - x1 + 1) * (y2 - y1 + 1)
        order = scores.argsort()[::-1]

        keep = []
        while order.size > 0:
            i = order[0]
            keep.append(i)
            xx1 = np.maximum(x1[i], x1[order[1:]])
            yy1 = np.maximum(y1[i], y1[order[1:]])
            xx2 = np.minimum(x2[i], x2[order[1:]])
            yy2 = np.minimum(y2[i], y2[order[1:]])
            inter = np.maximum(0, xx2 - xx1 + 1) * np.maximum(0, yy2 - yy1 + 1)
            overlap = inter / areas[order[1:]]
            order = order[np.where(overlap <= overlap_thresh)[0] + 1]

        return [boxes[i] for i in keep]

    # ------------------------------------------------------------------
    # OCR text search
    # ------------------------------------------------------------------

    def find_by_text(
        self,
        screen: np.ndarray,
        text: str,
        case_sensitive: bool = False,
    ) -> List[DetectedElement]:
        """Find UI elements whose OCR-detected text contains the query string."""
        if not TESSERACT_AVAILABLE:
            raise RuntimeError(
                "pytesseract is not installed. Install it with: pip install pytesseract"
            )

        data = pytesseract.image_to_data(screen, output_type=pytesseract.Output.DICT)
        needle = text if case_sensitive else text.lower()
        elements = []

        for i, word in enumerate(data["text"]):
            haystack = word if case_sensitive else word.lower()
            if needle in haystack and int(data["conf"][i]) > 0:
                elements.append(DetectedElement(
                    label=word,
                    x=data["left"][i],
                    y=data["top"][i],
                    width=data["width"][i],
                    height=data["height"][i],
                    confidence=float(data["conf"][i]) / 100.0,
                ))

        return elements

    # ------------------------------------------------------------------
    # Color-based detection
    # ------------------------------------------------------------------

    def find_by_color(
        self,
        screen: np.ndarray,
        lower_hsv: Tuple[int, int, int],
        upper_hsv: Tuple[int, int, int],
        label: str = "color_element",
        min_area: int = 500,
    ) -> List[DetectedElement]:
        """Find bounding boxes of regions matching an HSV color range."""
        hsv = cv2.cvtColor(screen, cv2.COLOR_BGR2HSV)
        mask = cv2.inRange(hsv, np.array(lower_hsv), np.array(upper_hsv))

        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)

        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        elements = []
        for cnt in contours:
            if cv2.contourArea(cnt) < min_area:
                continue
            x, y, w, h = cv2.boundingRect(cnt)
            elements.append(DetectedElement(
                label=label, x=x, y=y, width=w, height=h, confidence=1.0
            ))

        return elements

    # ------------------------------------------------------------------
    # Debug visualization
    # ------------------------------------------------------------------

    def annotate(self, screen: np.ndarray, elements: List[DetectedElement]) -> np.ndarray:
        """Return a copy of the screen with bounding boxes and labels drawn."""
        out = screen.copy()
        for el in elements:
            cv2.rectangle(out, (el.x, el.y), (el.x + el.width, el.y + el.height), (0, 255, 0), 2)
            cv2.putText(
                out,
                f"{el.label} ({el.confidence:.2f})",
                (el.x, max(el.y - 8, 0)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.5,
                (0, 255, 0),
                1,
            )
        return out
