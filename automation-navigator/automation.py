import os
import re
import subprocess
import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

import pyautogui

from element_detection import DetectedElement, ElementDetector
from screen_capture import ScreenCapture

# Brief safety pause between every PyAutoGUI action.
# Moving the mouse to any screen corner aborts execution (FAILSAFE).
pyautogui.PAUSE = 0.1
pyautogui.FAILSAFE = True


@dataclass
class CommandResult:
    success: bool
    message: str
    element: Optional[DetectedElement] = None


class CommandParser:
    """
    Maps natural-language command strings to (action, args) pairs.

    Supported surface syntax:
      click <target>
      double click <target>
      right click <target>
      type <text>
      scroll up/down [N times]
      find <target>
      screenshot [path]
    """

    _PATTERNS: List[Tuple[str, str]] = [
        (r"^double[\s_]?click\s+(.+)$", "double_click"),
        (r"^right[\s_]?click\s+(.+)$", "right_click"),
        (r"^click\s+(.+)$", "click"),
        (r"^type\s+['\"]?(.+?)['\"]?$", "type"),
        (r"^scroll\s+(up|down)(?:\s+(\d+)\s+times?)?$", "scroll"),
        (r"^find\s+(.+)$", "find"),
        (r"^screenshot(?:\s+(.+))?$", "screenshot"),
        (r"^launch\s+(.+)$", "launch"),
        (r"^key\s+(.+)$", "key"),
        (r"^scan[\s_]?corners?$", "scan_corners"),
    ]

    def parse(self, command: str) -> Optional[Tuple[str, List[str]]]:
        """Return (action, [args]) or None when the command is unrecognized."""
        for pattern, action in self._PATTERNS:
            match = re.match(pattern, command.strip(), re.IGNORECASE)
            if match:
                args = [g for g in match.groups() if g is not None]
                return action, args
        return None


class AutomationEngine:
    """
    High-level interface: accepts a natural-language command string,
    finds the target element on screen, and executes the requested action.
    """

    def __init__(
        self,
        confidence_threshold: float = 0.8,
        templates_dir: str = "templates",
    ):
        self.capture = ScreenCapture()
        self.detector = ElementDetector(confidence_threshold)
        self.parser = CommandParser()
        self.templates_dir = templates_dir

        self._handlers: Dict[str, Callable[[List[str]], CommandResult]] = {
            "click": self._handle_click,
            "double_click": self._handle_double_click,
            "right_click": self._handle_right_click,
            "type": self._handle_type,
            "scroll": self._handle_scroll,
            "find": self._handle_find,
            "screenshot": self._handle_screenshot,
            "launch": self._handle_launch,
            "key": self._handle_key,
            "scan_corners": self._handle_scan_corners,
        }

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run_command(self, command: str) -> CommandResult:
        """Parse and execute a natural-language command string."""
        parsed = self.parser.parse(command)
        if parsed is None:
            return CommandResult(
                success=False,
                message=(
                    f"Unrecognized command: '{command}'. "
                    "Supported: click, double click, right click, type, "
                    "scroll up/down, find, screenshot."
                ),
            )
        action, args = parsed
        return self._handlers[action](args)

    def find_element(self, target: str) -> Optional[DetectedElement]:
        """
        Locate a UI element by name.  Two strategies are tried in order:
        1. OCR text match against the live screen.
        2. Template image match (looks for templates/<target>.{png,jpg,bmp}).
        """
        screen = self.capture.capture_full_screen()

        # Strategy 1 — OCR
        try:
            hits = self.detector.find_by_text(screen, target)
            if hits:
                return max(hits, key=lambda e: e.confidence)
        except RuntimeError:
            pass  # pytesseract not available; fall through to template matching

        # Strategy 2 — template image
        for ext in (".png", ".jpg", ".jpeg", ".bmp"):
            path = os.path.join(self.templates_dir, target + ext)
            if os.path.exists(path):
                hits = self.detector.find_by_template(screen, path, label=target)
                if hits:
                    return max(hits, key=lambda e: e.confidence)

        return None

    # ------------------------------------------------------------------
    # Action handlers
    # ------------------------------------------------------------------

    def _handle_click(self, args: List[str]) -> CommandResult:
        element = self.find_element(args[0])
        if element is None:
            return CommandResult(success=False, message=f"Could not find '{args[0]}' on screen.")
        pyautogui.click(*element.center)
        return CommandResult(success=True, message=f"Clicked '{args[0]}' at {element.center}.", element=element)

    def _handle_double_click(self, args: List[str]) -> CommandResult:
        element = self.find_element(args[0])
        if element is None:
            return CommandResult(success=False, message=f"Could not find '{args[0]}' on screen.")
        pyautogui.doubleClick(*element.center)
        return CommandResult(success=True, message=f"Double-clicked '{args[0]}' at {element.center}.", element=element)

    def _handle_right_click(self, args: List[str]) -> CommandResult:
        element = self.find_element(args[0])
        if element is None:
            return CommandResult(success=False, message=f"Could not find '{args[0]}' on screen.")
        pyautogui.rightClick(*element.center)
        return CommandResult(success=True, message=f"Right-clicked '{args[0]}' at {element.center}.", element=element)

    def _handle_type(self, args: List[str]) -> CommandResult:
        pyautogui.typewrite(args[0], interval=0.05)
        return CommandResult(success=True, message=f"Typed: '{args[0]}'.")

    def _handle_scroll(self, args: List[str]) -> CommandResult:
        direction = args[0].lower()
        amount = int(args[1]) if len(args) > 1 else 3
        pyautogui.scroll(amount if direction == "up" else -amount)
        return CommandResult(success=True, message=f"Scrolled {direction} {amount} time(s).")

    def _handle_find(self, args: List[str]) -> CommandResult:
        element = self.find_element(args[0])
        if element is None:
            return CommandResult(success=False, message=f"'{args[0]}' not found on screen.")
        return CommandResult(
            success=True,
            message=f"Found '{args[0]}' at {element.center} (confidence: {element.confidence:.2f}).",
            element=element,
        )

    def _handle_screenshot(self, args: List[str]) -> CommandResult:
        path = args[0] if args else f"screenshot_{int(time.time())}.png"
        saved = self.capture.save_screenshot(path)
        return CommandResult(success=True, message=f"Screenshot saved to '{saved}'.")

    def _handle_launch(self, args: List[str]) -> CommandResult:
        app = args[0].strip()
        try:
            subprocess.Popen(app.split(), start_new_session=True)
            time.sleep(1.5)  # give the app a moment to start
            return CommandResult(success=True, message=f"Launched '{app}'.")
        except FileNotFoundError:
            # fall back to xdg-open which handles app names, URLs, file paths
            try:
                subprocess.Popen(["xdg-open", app], start_new_session=True)
                time.sleep(1.5)
                return CommandResult(success=True, message=f"Opened '{app}' via xdg-open.")
            except Exception as exc:
                return CommandResult(success=False, message=f"Could not launch '{app}': {exc}")
        except Exception as exc:
            return CommandResult(success=False, message=f"Could not launch '{app}': {exc}")

    def _handle_key(self, args: List[str]) -> CommandResult:
        combo = args[0].strip()
        try:
            keys = [k.strip() for k in combo.replace("+", " ").split()]
            pyautogui.hotkey(*keys)
            return CommandResult(success=True, message=f"Pressed '{combo}'.")
        except Exception as exc:
            return CommandResult(success=False, message=f"Key error for '{combo}': {exc}")

    def _handle_scan_corners(self, args: List[str]) -> CommandResult:
        """
        Scan the four screen corners for a small dismiss-style element
        (close button, X, arrow, skip button) and click the best match.

        Two strategies run in parallel and the highest-confidence hit wins:
          1. OCR — looks for dismiss text (X, ×, Skip, Close, …) in each corner
          2. Contour — finds small, roughly square, high-contrast blobs
        """
        import cv2
        import numpy as np

        screen = self.capture.capture_full_screen()
        h, w = screen.shape[:2]
        crop = min(w, h) // 6          # corner box size (~15 % of shorter edge)
        crop = max(crop, 120)          # floor so we don't go too small

        # Corner definitions: name → (slice_y, slice_x, origin_x, origin_y)
        corners = {
            "top_right":    (slice(0, crop),    slice(w - crop, w),  w - crop, 0),
            "top_left":     (slice(0, crop),    slice(0, crop),      0,        0),
            "bottom_right": (slice(h - crop, h), slice(w - crop, w), w - crop, h - crop),
            "bottom_left":  (slice(h - crop, h), slice(0, crop),     0,        h - crop),
        }

        # Text patterns that signal a dismiss element
        _DISMISS_TEXT = {"x", "×", "✕", "✗", "✖", "skip", "close", "done", "dismiss"}

        best_sx, best_sy, best_score, best_label = None, None, 0.0, ""

        for name, (sy, sx, ox, oy) in corners.items():
            region = screen[sy, sx]
            rh, rw = region.shape[:2]

            # ── Strategy 1: OCR ──────────────────────────────────────────
            try:
                import pytesseract
                data = pytesseract.image_to_data(
                    region, output_type=pytesseract.Output.DICT
                )
                for i, word in enumerate(data["text"]):
                    conf = int(data["conf"][i])
                    if conf < 20 or not word.strip():
                        continue
                    if word.strip().lower() in _DISMISS_TEXT:
                        cx = ox + data["left"][i] + data["width"][i] // 2
                        cy = oy + data["top"][i] + data["height"][i] // 2
                        score = 0.6 + conf / 200.0   # 0.6–1.1 range
                        if score > best_score:
                            best_sx, best_sy, best_score, best_label = cx, cy, score, f"OCR '{word}' in {name}"
            except Exception:
                pass

            # ── Strategy 2: Contour detection ────────────────────────────
            gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
            blurred = cv2.GaussianBlur(gray, (3, 3), 0)
            edges = cv2.Canny(blurred, 40, 120)
            contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

            for cnt in contours:
                area = cv2.contourArea(cnt)
                if area < 80 or area > 6000:        # ignore tiny noise & huge blobs
                    continue
                bx, by, bw, bh = cv2.boundingRect(cnt)
                aspect = max(bw, bh) / max(min(bw, bh), 1)
                if aspect > 3.0:                    # too elongated to be a button
                    continue

                # Compactness (circle/square score)
                peri = cv2.arcLength(cnt, True)
                compact = 4 * np.pi * area / (peri * peri + 1e-6)

                # Contrast vs surrounding area
                pad = 8
                y1 = max(0, by - pad);  y2 = min(rh, by + bh + pad)
                x1 = max(0, bx - pad);  x2 = min(rw, bx + bw + pad)
                inner = float(gray[by:by + bh, bx:bx + bw].mean())
                outer = float(gray[y1:y2, x1:x2].mean())
                contrast = abs(inner - outer) / 255.0

                score = compact * contrast * min(area, 2500) / 2500
                if score > best_score:
                    best_sx = ox + bx + bw // 2
                    best_sy = oy + by + bh // 2
                    best_score = score
                    best_label = f"contour in {name} (score {score:.2f})"

        if best_sx is not None and best_score > 0.04:
            pyautogui.click(best_sx, best_sy)
            return CommandResult(
                success=True,
                message=f"Clicked dismiss element at ({best_sx}, {best_sy}) — {best_label}.",
            )

        return CommandResult(success=False, message="No dismiss element found in corners.")
