import cv2
import mss
import numpy as np
from dataclasses import dataclass
from pathlib import Path
from PIL import Image
from typing import Tuple


@dataclass
class CaptureRegion:
    left: int
    top: int
    width: int
    height: int


class ScreenCapture:
    def __init__(self):
        self.sct = mss.mss()

    def capture_full_screen(self, monitor_index: int = 1) -> np.ndarray:
        """Capture the full screen and return a BGR numpy array."""
        monitor = self.sct.monitors[monitor_index]
        screenshot = self.sct.grab(monitor)
        img = np.array(screenshot)
        return cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)

    def capture_region(self, region: CaptureRegion) -> np.ndarray:
        """Capture a rectangular region of the screen."""
        bounds = {
            "left": region.left,
            "top": region.top,
            "width": region.width,
            "height": region.height,
        }
        screenshot = self.sct.grab(bounds)
        img = np.array(screenshot)
        return cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)

    def save_screenshot(self, filepath: str, monitor_index: int = 1) -> Path:
        """Save a full-screen capture to disk and return the path."""
        img = self.capture_full_screen(monitor_index)
        path = Path(filepath)
        cv2.imwrite(str(path), img)
        return path

    def to_pil(self, img: np.ndarray) -> Image.Image:
        """Convert a BGR numpy array to a PIL Image."""
        return Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))

    def get_screen_size(self, monitor_index: int = 1) -> Tuple[int, int]:
        """Return (width, height) of the specified monitor."""
        monitor = self.sct.monitors[monitor_index]
        return monitor["width"], monitor["height"]
