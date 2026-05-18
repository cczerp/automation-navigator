"""
OllamaReasoner — sends the current ScreenState + task to a local Ollama model
and gets back a structured AgentAction (what to do next and why).

The model is instructed to output plain JSON only.  If JSON parsing fails the
reasoner returns an AgentAction(type="fail") so the agent loop can handle it
gracefully rather than crashing.
"""

import json
from dataclasses import dataclass, field
from typing import List

import requests

from perception import ScreenState

DEFAULT_MODEL = "llama3"
DEFAULT_BASE_URL = "http://localhost:11434"

_SYSTEM_PROMPT = """\
You are an AI agent controlling a PC desktop by vision.
You see a description of the current screen and must decide the single next action.

Respond with ONLY valid JSON — no other text — in this exact shape:
{
  "action": "<action>",
  "target": "<target>",
  "reasoning": "<one concise sentence>"
}

Valid actions:
  click        — click a named UI element that is currently visible
  type         — type the given text string (target = the text)
  scroll_up    — scroll up (target ignored)
  scroll_down  — scroll down (target ignored)
  wait         — wait 1-2 seconds for the UI to settle (target ignored)
  done         — the task is complete
  fail         — cannot proceed (explain in reasoning)

Rules:
- Only click elements explicitly listed as matched or in visible text.
- If the last two actions were identical and both failed, try something different.
- If unsure, prefer wait over a risky click.
- Keep reasoning under 20 words.
"""


@dataclass
class AgentAction:
    type: str               # click | type | scroll_up | scroll_down | wait | done | fail
    target: str = ""
    reasoning: str = ""
    raw_response: str = field(default="", repr=False)


class OllamaReasoner:
    def __init__(self, model: str = DEFAULT_MODEL, base_url: str = DEFAULT_BASE_URL):
        self.model = model
        self.base_url = base_url.rstrip("/")

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def is_available(self) -> bool:
        try:
            r = requests.get(f"{self.base_url}/api/tags", timeout=3)
            return r.status_code == 200
        except Exception:
            return False

    def list_models(self) -> List[str]:
        try:
            r = requests.get(f"{self.base_url}/api/tags", timeout=4)
            r.raise_for_status()
            return [m["name"] for m in r.json().get("models", [])]
        except Exception:
            return []

    def decide(
        self,
        task: str,
        state: ScreenState,
        history: List[dict],
    ) -> AgentAction:
        """Ask the model what to do next and parse its JSON reply."""
        prompt = self._build_prompt(task, state, history)
        raw = self._call_ollama(prompt)
        return self._parse(raw)

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _build_prompt(self, task: str, state: ScreenState, history: List[dict]) -> str:
        history_block = ""
        if history:
            lines = [
                f"  {i + 1}. {h['action']} '{h.get('target', '')}' → {h.get('result', '?')}"
                for i, h in enumerate(history[-6:])
            ]
            history_block = "\nRecent actions:\n" + "\n".join(lines)

        return (
            f"Task: {task}\n\n"
            f"Screen state:\n{state.to_prompt_text()}"
            f"{history_block}\n\n"
            "What is the single next action?"
        )

    def _call_ollama(self, prompt: str) -> str:
        try:
            resp = requests.post(
                f"{self.base_url}/api/generate",
                json={
                    "model": self.model,
                    "prompt": prompt,
                    "system": _SYSTEM_PROMPT,
                    "stream": False,
                    "format": "json",
                    "options": {"temperature": 0.2},
                },
                timeout=60,
            )
            resp.raise_for_status()
            return resp.json().get("response", "")
        except requests.exceptions.Timeout:
            return '{"action":"fail","target":"","reasoning":"Ollama timed out."}'
        except Exception as exc:
            return f'{{"action":"fail","target":"","reasoning":"Ollama error: {exc}"}}'

    def _parse(self, raw: str) -> AgentAction:
        try:
            data = json.loads(raw.strip())
            action_type = str(data.get("action", "fail")).lower()
            if action_type not in {
                "click", "type", "scroll_up", "scroll_down", "wait", "done", "fail"
            }:
                action_type = "fail"
            return AgentAction(
                type=action_type,
                target=str(data.get("target", "")),
                reasoning=str(data.get("reasoning", "")),
                raw_response=raw,
            )
        except json.JSONDecodeError:
            return AgentAction(
                type="fail",
                reasoning="Could not parse model response as JSON.",
                raw_response=raw,
            )
