"""
Agent — the perception-reason-act loop.

Each iteration:
  1. Perceive  — capture screen → ScreenState
  2. Reason    — send state + task to Ollama → AgentAction
  3. Act       — execute the action
  4. Verify    — compare before/after states; flag if nothing changed

The agent stops when:
  - Ollama returns action="done"
  - Ollama returns action="fail" twice in a row
  - max_steps is reached
  - stop() is called externally
"""

import threading
import time
from dataclasses import dataclass, field
from typing import List, Optional

import pyautogui

from automation import AutomationEngine
from perception import PerceptionEngine, ScreenState
from reasoning import AgentAction, OllamaReasoner

pyautogui.PAUSE = 0.05
pyautogui.FAILSAFE = True

_UI_SETTLE_SECS = 1.0   # pause after every action for UI to settle
_MAX_NO_CHANGE = 2       # consecutive steps with no screen change → warn


@dataclass
class AgentStatus:
    running: bool = False
    task: str = ""
    step: int = 0
    max_steps: int = 0
    last_action: dict = field(default_factory=dict)
    last_state_summary: str = ""
    last_diff: dict = field(default_factory=dict)
    log: List[str] = field(default_factory=list)
    error: str = ""


class Agent:
    """
    Perception-driven agent.  Instantiate once; call start()/stop() as needed.
    All long-running work happens in a daemon thread; status is safe to read
    from any thread via the `status` property.
    """

    def __init__(
        self,
        templates_dir: str = "templates",
        ollama_model: str = "llama3",
        ollama_url: str = "http://localhost:11434",
        max_steps: int = 30,
    ):
        self.templates_dir = templates_dir
        self.max_steps = max_steps

        self._perception = PerceptionEngine(templates_dir)
        self._automation = AutomationEngine(templates_dir=templates_dir)
        self._reasoner = OllamaReasoner(model=ollama_model, base_url=ollama_url)

        self._lock = threading.Lock()
        self._stop_ev = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._status = AgentStatus()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def status(self) -> dict:
        with self._lock:
            s = self._status
            return {
                "running": s.running,
                "task": s.task,
                "step": s.step,
                "max_steps": s.max_steps,
                "last_action": s.last_action,
                "last_state_summary": s.last_state_summary,
                "last_diff": s.last_diff,
                "log": list(s.log[-30:]),
                "error": s.error,
            }

    def start(self, task: str, max_steps: Optional[int] = None) -> bool:
        self.stop()
        steps = max_steps or self.max_steps
        self._stop_ev.clear()
        with self._lock:
            self._status = AgentStatus(
                running=True, task=task, max_steps=steps
            )
        self._thread = threading.Thread(
            target=self._loop, args=(task, steps), daemon=True
        )
        self._thread.start()
        return True

    def stop(self):
        self._stop_ev.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=5)

    def set_model(self, model: str):
        self._reasoner.model = model

    def list_models(self) -> List[str]:
        return self._reasoner.list_models()

    def ollama_available(self) -> bool:
        return self._reasoner.is_available()

    # ------------------------------------------------------------------
    # Main loop (runs in daemon thread)
    # ------------------------------------------------------------------

    def _loop(self, task: str, max_steps: int):
        history: List[dict] = []
        consecutive_fails = 0
        no_change_streak = 0
        prev_state: Optional[ScreenState] = None

        try:
            for step in range(1, max_steps + 1):
                if self._stop_ev.is_set():
                    self._log("Stopped by user.")
                    break

                self._set_step(step)

                # ── 1. Perceive ──────────────────────────────────────────
                self._log(f"[{step}] Perceiving screen…")
                state = self._perception.capture_state(include_screenshot=False)
                summary = state.to_prompt_text()
                with self._lock:
                    self._status.last_state_summary = summary

                # Diff against previous state
                if prev_state is not None:
                    diff = self._perception.diff(prev_state, state)
                    with self._lock:
                        self._status.last_diff = diff
                    if not diff["changed"]:
                        no_change_streak += 1
                        if no_change_streak >= _MAX_NO_CHANGE:
                            self._log(
                                f"[{step}] Screen unchanged for {no_change_streak} steps "
                                "— may be stuck."
                            )
                    else:
                        no_change_streak = 0
                prev_state = state

                # ── 2. Reason ────────────────────────────────────────────
                self._log(f"[{step}] Asking Ollama ({self._reasoner.model})…")
                action = self._reasoner.decide(task, state, history)
                self._log(
                    f"[{step}] → {action.type.upper()}"
                    + (f" '{action.target}'" if action.target else "")
                    + f"  ({action.reasoning})"
                )
                with self._lock:
                    self._status.last_action = {
                        "step": step,
                        "type": action.type,
                        "target": action.target,
                        "reasoning": action.reasoning,
                    }

                # ── 3. Terminal conditions ───────────────────────────────
                if action.type == "done":
                    self._log(f"[{step}] Task complete: {action.reasoning}")
                    break

                if action.type == "fail":
                    consecutive_fails += 1
                    self._log(f"[{step}] Agent cannot proceed: {action.reasoning}")
                    if consecutive_fails >= 2:
                        self._log("Two consecutive failures — aborting.")
                        break
                    time.sleep(1.5)
                    continue

                consecutive_fails = 0

                # ── 4. Execute ───────────────────────────────────────────
                result = self._execute(action, state)
                self._log(f"[{step}] Result: {result}")
                history.append({
                    "action": action.type,
                    "target": action.target,
                    "result": result,
                })

                # Brief pause for UI to settle before next perception pass
                if not self._stop_ev.wait(timeout=_UI_SETTLE_SECS):
                    continue  # not stopped; proceed
                else:
                    self._log("Stopped by user.")
                    break

            else:
                self._log(f"Reached max steps ({max_steps}).")

        except Exception as exc:
            with self._lock:
                self._status.error = str(exc)
            self._log(f"Unhandled error: {exc}")

        finally:
            with self._lock:
                self._status.running = False

    # ------------------------------------------------------------------
    # Action execution
    # ------------------------------------------------------------------

    def _execute(self, action: AgentAction, state: ScreenState) -> str:
        try:
            if action.type == "click":
                result = self._automation.run_command(f"click {action.target}")
                return "ok" if result.success else f"not found: {action.target}"

            elif action.type == "type":
                pyautogui.typewrite(action.target, interval=0.04)
                return f"typed '{action.target}'"

            elif action.type == "scroll_up":
                pyautogui.scroll(5)
                return "scrolled up"

            elif action.type == "scroll_down":
                pyautogui.scroll(-5)
                return "scrolled down"

            elif action.type == "wait":
                time.sleep(1.5)
                return "waited"

            return f"unknown action type: {action.type}"

        except Exception as exc:
            return f"error: {exc}"

    # ------------------------------------------------------------------
    # Thread-safe helpers
    # ------------------------------------------------------------------

    def _log(self, msg: str):
        with self._lock:
            self._status.log.append(msg)

    def _set_step(self, step: int):
        with self._lock:
            self._status.step = step
