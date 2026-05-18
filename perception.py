"""
PerceptionEngine — captures the screen and builds a structured ScreenState.

ScreenState is the shared language between detection and reasoning:
  - visible_text: all OCR'd words with enough confidence
  - detected_elements: template matches for all saved buttons
  - app_hint: best guess at current app derived from visible text
  - to_prompt_text(): renders state as a plain-English paragraph for the LLM
"""

import os
import time
from dataclasses import dataclass, field
from typing import List, Optional

import numpy as np

from element_detection import TESSERACT_AVAILABLE, DetectedElement, ElementDetector
from screen_capture import ScreenCapture

try:
    import pytesseract
except ImportError:
    pytesseract = None  # type: ignore


_APP_KEYWORDS = [
    ("gmail", "Gmail"),
    ("inbox", "Email / Inbox"),
    ("compose", "Email compose"),
    ("settings", "Settings"),
    ("chrome", "Chrome"),
    ("messages", "Messages"),
    ("youtube", "YouTube"),
    ("maps", "Maps"),
    ("files", "Files"),
    ("calendar", "Calendar"),
    ("contacts", "Contacts"),
    ("photos", "Photos"),
]


@dataclass
class ScreenState:
    timestamp: float
    visible_text: List[str]
    detected_elements: List[DetectedElement]
    app_hint: str
    screenshot: Optional[np.ndarray] = field(default=None, repr=False)

    def to_prompt_text(self) -> str:
        """Render state as a plain-English description for the LLM."""
        parts: List[str] = []

        if self.app_hint:
            parts.append(f"Current app / context: {self.app_hint}")

        if self.visible_text:
            # Deduplicate while preserving order, cap at 40 tokens
            seen, unique = set(), []
            for w in self.visible_text:
                if w.lower() not in seen:
                    seen.add(w.lower())
                    unique.append(w)
            parts.append(f"Text visible on screen: {', '.join(unique[:40])}")
        else:
            parts.append("No readable text detected on screen.")

        if self.detected_elements:
            els = [
                f"'{e.label}' at ({e.center[0]}, {e.center[1]})"
                for e in self.detected_elements
            ]
            parts.append(f"Known UI elements matched: {'; '.join(els)}")
        else:
            parts.append("No saved button templates matched on screen.")

        return "\n".join(parts)

    def has_text(self, fragment: str, case_sensitive: bool = False) -> bool:
        needle = fragment if case_sensitive else fragment.lower()
        return any(
            (w if case_sensitive else w.lower()) == needle
            for w in self.visible_text
        )

    def find_element(self, label: str) -> Optional[DetectedElement]:
        for el in self.detected_elements:
            if el.label == label:
                return el
        return None


class PerceptionEngine:
    def __init__(self, templates_dir: str = "templates", confidence: float = 0.75):
        self.templates_dir = templates_dir
        self._capture = ScreenCapture()
        self._detector = ElementDetector(confidence_threshold=confidence)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def capture_state(self, include_screenshot: bool = True) -> ScreenState:
        """Take a screenshot and build a ScreenState from it."""
        screenshot = self._capture.capture_full_screen()

        visible_text = self._ocr(screenshot)
        detected_elements = self._match_all_templates(screenshot)
        app_hint = self._infer_app(visible_text)

        return ScreenState(
            timestamp=time.time(),
            visible_text=visible_text,
            detected_elements=detected_elements,
            app_hint=app_hint,
            screenshot=screenshot if include_screenshot else None,
        )

    def diff(self, before: ScreenState, after: ScreenState) -> dict:
        """Summarise what changed between two states."""
        before_words = set(before.visible_text)
        after_words = set(after.visible_text)
        before_els = {e.label for e in before.detected_elements}
        after_els = {e.label for e in after.detected_elements}
        return {
            "changed": before_words != after_words or before_els != after_els,
            "text_appeared": sorted(after_words - before_words),
            "text_disappeared": sorted(before_words - after_words),
            "elements_appeared": sorted(after_els - before_els),
            "elements_disappeared": sorted(before_els - after_els),
        }

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _ocr(self, screenshot: np.ndarray) -> List[str]:
        if not TESSERACT_AVAILABLE or pytesseract is None:
            return []
        try:
            data = pytesseract.image_to_data(
                screenshot, output_type=pytesseract.Output.DICT
            )
            return [
                w.strip()
                for w, c in zip(data["text"], data["conf"])
                if w.strip() and int(c) > 40
            ]
        except Exception:
            return []

    def _match_all_templates(self, screenshot: np.ndarray) -> List[DetectedElement]:
        elements: List[DetectedElement] = []
        if not os.path.isdir(self.templates_dir):
            return elements
        for fname in sorted(os.listdir(self.templates_dir)):
            if fname.startswith("."):
                continue
            name = os.path.splitext(fname)[0]
            path = os.path.join(self.templates_dir, fname)
            try:
                hits = self._detector.find_by_template(screenshot, path, label=name)
                if hits:
                    # Keep only the best match per template
                    elements.append(max(hits, key=lambda e: e.confidence))
            except Exception:
                pass
        return elements

    def _infer_app(self, text_tokens: List[str]) -> str:
        joined = " ".join(text_tokens).lower()
        for keyword, label in _APP_KEYWORDS:
            if keyword in joined:
                return label
        return ""
