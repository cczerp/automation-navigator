"""
Accessibility Navigator — Android app (Kivy).

Two screens:
  HomeScreen       — one big button per saved template; tap to click it on the PC.
  TemplateScreen   — add/remove button screenshots; uploads them to the PC server.

A Settings popup on the home screen lets you set the PC's local IP address.
"""

import base64
import json
import os
import shutil
import threading

import requests
from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.scrollview import ScrollView
from kivy.uix.screenmanager import Screen, ScreenManager
from kivy.uix.textinput import TextInput
from plyer import filechooser

Window.clearcolor = (0.96, 0.96, 0.96, 1)

CONFIG_FILE = "server_config.json"
TEMPLATES_DIR = "local_templates"
SEQUENCES_DIR = "sequences"
DEFAULT_URL = "http://192.168.1.100:5000"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def load_config() -> dict:
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return {"server_url": DEFAULT_URL}


def save_config(cfg: dict) -> None:
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f)


def template_files() -> list:
    os.makedirs(TEMPLATES_DIR, exist_ok=True)
    return sorted(
        f for f in os.listdir(TEMPLATES_DIR)
        if f.lower().endswith((".png", ".jpg", ".jpeg"))
    )


def sequence_files() -> list:
    os.makedirs(SEQUENCES_DIR, exist_ok=True)
    return sorted(f for f in os.listdir(SEQUENCES_DIR) if f.endswith(".json"))


def load_sequence(name: str) -> dict:
    path = os.path.join(SEQUENCES_DIR, f"{name}.json")
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return {"name": name, "loop": False, "loop_count": 1, "steps": []}


def save_sequence_file(name: str, data: dict) -> None:
    os.makedirs(SEQUENCES_DIR, exist_ok=True)
    with open(os.path.join(SEQUENCES_DIR, f"{name}.json"), "w") as f:
        json.dump(data, f)


# ---------------------------------------------------------------------------
# Reusable widgets
# ---------------------------------------------------------------------------

def big_button(text, color, **kwargs) -> Button:
    return Button(
        text=text,
        font_size="20sp",
        bold=True,
        background_color=color,
        background_normal="",
        **kwargs,
    )


def status_label() -> Label:
    return Label(
        text="",
        font_size="16sp",
        size_hint_y=None,
        height=dp(44),
        halign="center",
    )


# ---------------------------------------------------------------------------
# Settings popup
# ---------------------------------------------------------------------------

class SettingsPopup(Popup):
    def __init__(self, on_save, **kwargs):
        super().__init__(
            title="PC Server Address",
            size_hint=(0.85, None),
            height=dp(260),
            **kwargs,
        )
        self._on_save = on_save
        cfg = load_config()

        layout = BoxLayout(orientation="vertical", padding=dp(16), spacing=dp(12))

        layout.add_widget(Label(
            text="Enter your PC's local IP address\n(shown when you run server.py)",
            font_size="16sp",
            halign="center",
            size_hint_y=None,
            height=dp(60),
        ))

        self.ip_input = TextInput(
            text=cfg["server_url"],
            font_size="18sp",
            multiline=False,
            size_hint_y=None,
            height=dp(48),
        )
        layout.add_widget(self.ip_input)

        row = BoxLayout(size_hint_y=None, height=dp(54), spacing=dp(10))
        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1))
        cancel.bind(on_press=self.dismiss)
        save = big_button("Save", (0.15, 0.6, 0.3, 1))
        save.bind(on_press=self._save)
        row.add_widget(cancel)
        row.add_widget(save)
        layout.add_widget(row)

        self.content = layout

    def _save(self, *_):
        url = self.ip_input.text.strip().rstrip("/")
        cfg = load_config()
        cfg["server_url"] = url
        save_config(cfg)
        self._on_save(url)
        self.dismiss()


# ---------------------------------------------------------------------------
# Home screen
# ---------------------------------------------------------------------------

class HomeScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._build_ui()

    def _build_ui(self):
        root = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(8))

        # ── title bar ───────────────────────────────────────────────────
        title_row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(8))
        title_row.add_widget(Label(
            text="Accessibility Navigator",
            font_size="22sp",
            bold=True,
            color=(0.1, 0.1, 0.1, 1),
            halign="left",
            valign="middle",
        ))
        settings_btn = big_button(
            "⚙", (0.55, 0.55, 0.55, 1),
            size_hint_x=None, width=dp(52),
        )
        settings_btn.bind(on_press=self._open_settings)
        title_row.add_widget(settings_btn)
        root.add_widget(title_row)

        # ── 2 × 2 navigation grid ───────────────────────────────────────
        nav = GridLayout(cols=2, spacing=dp(10), size_hint_y=None, height=dp(160))

        btn_my = big_button("My Buttons", (0.18, 0.48, 0.85, 1))
        btn_my.bind(on_press=lambda _: setattr(self.manager, "current", "templates"))
        nav.add_widget(btn_my)

        btn_seq = big_button("Sequences", (0.45, 0.15, 0.72, 1))
        btn_seq.bind(on_press=lambda _: setattr(self.manager, "current", "sequences"))
        nav.add_widget(btn_seq)

        btn_ai = big_button("AI Agent", (0.08, 0.48, 0.68, 1))
        btn_ai.bind(on_press=lambda _: setattr(self.manager, "current", "agent"))
        nav.add_widget(btn_ai)

        # placeholder to keep the grid balanced — leave empty or add future screen
        nav.add_widget(Label())

        root.add_widget(nav)

        # ── status ──────────────────────────────────────────────────────
        self.status = status_label()
        root.add_widget(self.status)

        # ── divider label ───────────────────────────────────────────────
        root.add_widget(Label(
            text="Quick Launch",
            font_size="15sp",
            color=(0.45, 0.45, 0.45, 1),
            size_hint_y=None,
            height=dp(24),
            halign="left",
            bold=True,
        ))

        # ── scrollable button grid ───────────────────────────────────────
        self.scroll = ScrollView()
        self.grid = GridLayout(
            cols=2,
            spacing=dp(14),
            padding=dp(6),
            size_hint_y=None,
        )
        self.grid.bind(minimum_height=self.grid.setter("height"))
        self.scroll.add_widget(self.grid)
        root.add_widget(self.scroll)

        self.add_widget(root)

    def on_enter(self):
        self._refresh_grid()

    def _refresh_grid(self):
        self.grid.clear_widgets()
        files = template_files()

        if not files:
            self.grid.cols = 1
            self.grid.add_widget(Label(
                text='No buttons yet.\nTap "My Buttons" to add some.',
                font_size="19sp",
                color=(0.55, 0.55, 0.55, 1),
                halign="center",
                size_hint_y=None,
                height=dp(120),
            ))
            return

        self.grid.cols = 2
        for fname in files:
            name = os.path.splitext(fname)[0]
            btn = big_button(
                name.replace("_", " ").title(),
                (0.18, 0.52, 0.82, 1),
                size_hint_y=None,
                height=dp(110),
            )
            btn.bind(on_press=lambda _, n=name: self._send_click(n))
            self.grid.add_widget(btn)

    def _send_click(self, name: str):
        self._set_status(f"Finding '{name}'…", (0.4, 0.4, 0.8, 1))
        cfg = load_config()
        try:
            resp = requests.post(
                f"{cfg['server_url']}/command",
                json={"command": f"click {name}"},
                timeout=6,
            )
            data = resp.json()
            if data["success"]:
                self._set_status(f"Done! {data['message']}", (0.1, 0.6, 0.15, 1))
            else:
                self._set_status(f"Not found: {name}", (0.8, 0.2, 0.2, 1))
        except requests.exceptions.ConnectionError:
            self._set_status("Cannot reach PC — check Wi-Fi and server IP.", (0.8, 0.2, 0.2, 1))
        except Exception as exc:
            self._set_status(f"Error: {exc}", (0.8, 0.2, 0.2, 1))

    def _open_settings(self, *_):
        SettingsPopup(on_save=lambda url: self._set_status(f"Server set to {url}", (0.3, 0.3, 0.3, 1))).open()

    def _set_status(self, text: str, color: tuple):
        self.status.text = text
        self.status.color = color


# ---------------------------------------------------------------------------
# Template manager screen
# ---------------------------------------------------------------------------

class TemplateScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._build_ui()

    def _build_ui(self):
        root = BoxLayout(orientation="vertical", padding=dp(14), spacing=dp(10))

        # ── header ──────────────────────────────────────────────────────
        header = BoxLayout(size_hint_y=None, height=dp(68), spacing=dp(10))
        back = big_button("← Back", (0.5, 0.5, 0.5, 1), size_hint_x=None, width=dp(100))
        back.bind(on_press=lambda _: setattr(self.manager, "current", "home"))
        header.add_widget(back)
        header.add_widget(Label(
            text="Manage Buttons",
            font_size="22sp",
            bold=True,
            color=(0.1, 0.1, 0.1, 1),
        ))
        root.add_widget(header)

        # ── instructions ────────────────────────────────────────────────
        root.add_widget(Label(
            text=(
                "Take a screenshot of any button on the PC screen,\n"
                "give it a name, then tap Add — the app remembers it."
            ),
            font_size="15sp",
            color=(0.35, 0.35, 0.35, 1),
            size_hint_y=None,
            height=dp(64),
            halign="center",
        ))

        # ── name input + add button ──────────────────────────────────────
        input_row = BoxLayout(size_hint_y=None, height=dp(60), spacing=dp(10))
        self.name_input = TextInput(
            hint_text="Button name  e.g. submit",
            font_size="18sp",
            multiline=False,
        )
        add_btn = big_button(
            "Add\nButton", (0.1, 0.68, 0.3, 1),
            size_hint_x=None, width=dp(120),
        )
        add_btn.bind(on_press=self._pick_file)
        input_row.add_widget(self.name_input)
        input_row.add_widget(add_btn)
        root.add_widget(input_row)

        self.status = status_label()
        root.add_widget(self.status)

        # ── saved-buttons list ───────────────────────────────────────────
        root.add_widget(Label(
            text="Saved buttons:",
            font_size="17sp",
            bold=True,
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(34),
            halign="left",
        ))

        scroll = ScrollView()
        self.list_box = BoxLayout(
            orientation="vertical",
            spacing=dp(8),
            size_hint_y=None,
        )
        self.list_box.bind(minimum_height=self.list_box.setter("height"))
        scroll.add_widget(self.list_box)
        root.add_widget(scroll)

        self.add_widget(root)

    def on_enter(self):
        self._refresh_list()

    # ── file picker ─────────────────────────────────────────────────────

    def _pick_file(self, *_):
        name = self.name_input.text.strip()
        if not name:
            self._set_status("Enter a button name first.", (0.8, 0.2, 0.2, 1))
            return
        try:
            filechooser.open_file(
                on_selection=lambda sel: self._on_file_selected(sel, name),
                filters=["*.png", "*.jpg", "*.jpeg"],
                title="Choose a screenshot of the button",
            )
        except Exception as exc:
            self._set_status(f"File picker error: {exc}", (0.8, 0.2, 0.2, 1))

    def _on_file_selected(self, selection: list, name: str):
        if not selection:
            return
        src = selection[0]
        os.makedirs(TEMPLATES_DIR, exist_ok=True)
        ext = os.path.splitext(src)[1].lower() or ".png"
        dest = os.path.join(TEMPLATES_DIR, f"{name}{ext}")
        shutil.copy2(src, dest)
        self._upload_to_server(dest, name)
        self.name_input.text = ""
        self._set_status(f"'{name}' saved!", (0.1, 0.6, 0.15, 1))
        self._refresh_list()

    # ── server sync ─────────────────────────────────────────────────────

    def _upload_to_server(self, filepath: str, name: str):
        try:
            cfg = load_config()
            with open(filepath, "rb") as f:
                img_b64 = base64.b64encode(f.read()).decode()
            requests.post(
                f"{cfg['server_url']}/templates",
                json={"name": name, "image": img_b64},
                timeout=6,
            )
        except Exception:
            pass  # offline-first; sync on next connection

    def _delete_from_server(self, filename: str):
        try:
            cfg = load_config()
            requests.delete(f"{cfg['server_url']}/templates/{filename}", timeout=4)
        except Exception:
            pass

    # ── list management ─────────────────────────────────────────────────

    def _refresh_list(self):
        self.list_box.clear_widgets()
        files = template_files()

        if not files:
            self.list_box.add_widget(Label(
                text="No buttons saved yet.",
                font_size="17sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None,
                height=dp(54),
            ))
            return

        for fname in files:
            name = os.path.splitext(fname)[0]
            row = BoxLayout(size_hint_y=None, height=dp(62), spacing=dp(10))
            row.add_widget(Label(
                text=name.replace("_", " ").title(),
                font_size="18sp",
                color=(0.1, 0.1, 0.1, 1),
            ))
            remove = big_button(
                "Remove", (0.82, 0.18, 0.18, 1),
                size_hint_x=None, width=dp(100),
            )
            remove.bind(on_press=lambda _, f=fname, n=name: self._delete(f, n))
            row.add_widget(remove)
            self.list_box.add_widget(row)

    def _delete(self, fname: str, name: str):
        path = os.path.join(TEMPLATES_DIR, fname)
        if os.path.exists(path):
            os.remove(path)
        self._delete_from_server(fname)
        self._set_status(f"'{name}' removed.", (0.5, 0.5, 0.5, 1))
        self._refresh_list()

    def _set_status(self, text: str, color: tuple):
        self.status.text = text
        self.status.color = color


# ---------------------------------------------------------------------------
# Sequence popups
# ---------------------------------------------------------------------------

class AddClickStepPopup(Popup):
    """Pick a saved template to click."""

    def __init__(self, on_add, **kwargs):
        super().__init__(title="Add Click Step", size_hint=(0.9, 0.82), **kwargs)
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        layout.add_widget(Label(
            text="Which button should it click?",
            font_size="16sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(36),
        ))

        scroll = ScrollView()
        grid = GridLayout(cols=1, spacing=dp(6), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))

        files = template_files()
        if files:
            for fname in files:
                name = os.path.splitext(fname)[0]
                btn = big_button(
                    name.replace("_", " ").title(),
                    (0.18, 0.52, 0.82, 1),
                    size_hint_y=None, height=dp(70),
                )
                btn.bind(on_press=lambda _, n=name: self._pick(n))
                grid.add_widget(btn)
        else:
            grid.add_widget(Label(
                text="No buttons saved yet.\nGo to My Buttons first.",
                font_size="16sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None,
                height=dp(80),
                halign="center",
            ))

        scroll.add_widget(grid)
        layout.add_widget(scroll)

        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1), size_hint_y=None, height=dp(54))
        cancel.bind(on_press=self.dismiss)
        layout.add_widget(cancel)
        self.content = layout

    def _pick(self, name):
        self._on_add({"type": "click", "target": name})
        self.dismiss()


class AddWaitStepPopup(Popup):
    """Enter a wait duration in seconds."""

    def __init__(self, on_add, **kwargs):
        super().__init__(
            title="Add Wait Step",
            size_hint=(0.85, None),
            height=dp(300),
            **kwargs,
        )
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(16), spacing=dp(12))
        layout.add_widget(Label(
            text="Wait how many seconds?\n(gives you time to read, etc.)",
            font_size="16sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(60),
            halign="center",
        ))

        self.secs_input = TextInput(
            text="5",
            font_size="26sp",
            input_filter="float",
            multiline=False,
            size_hint_y=None,
            height=dp(58),
            halign="center",
        )
        layout.add_widget(self.secs_input)

        row = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(10))
        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1))
        cancel.bind(on_press=self.dismiss)
        add = big_button("Add Wait", (0.7, 0.45, 0.0, 1))
        add.bind(on_press=self._add)
        row.add_widget(cancel)
        row.add_widget(add)
        layout.add_widget(row)

        self.content = layout

    def _add(self, *_):
        try:
            secs = float(self.secs_input.text.strip() or "1")
            secs = max(0.1, secs)
        except ValueError:
            secs = 1.0
        self._on_add({"type": "wait", "seconds": secs})
        self.dismiss()


class AddLaunchStepPopup(Popup):
    """Enter an app name or command to launch on the PC."""

    def __init__(self, on_add, **kwargs):
        super().__init__(
            title="Launch App",
            size_hint=(0.88, None),
            height=dp(320),
            **kwargs,
        )
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(16), spacing=dp(12))
        layout.add_widget(Label(
            text="App name or command to run on the PC.\nExamples:  thunderbird  |  firefox  |  gedit",
            font_size="15sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(56),
            halign="center",
        ))

        self.app_input = TextInput(
            hint_text="e.g.  thunderbird",
            font_size="20sp",
            multiline=False,
            size_hint_y=None,
            height=dp(54),
        )
        layout.add_widget(self.app_input)

        row = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(10))
        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1))
        cancel.bind(on_press=self.dismiss)
        add = big_button("Add Launch", (0.1, 0.58, 0.28, 1))
        add.bind(on_press=self._add)
        row.add_widget(cancel)
        row.add_widget(add)
        layout.add_widget(row)

        self.content = layout

    def _add(self, *_):
        target = self.app_input.text.strip()
        if not target:
            return
        self._on_add({"type": "launch", "target": target})
        self.dismiss()


class AddKeyStepPopup(Popup):
    """Enter a keyboard shortcut to press on the PC."""

    _PRESETS = [
        ("Super (Win key)", "super"),
        ("Alt + F4  (close window)", "alt+F4"),
        ("Ctrl + W  (close tab)", "ctrl+w"),
        ("Ctrl + T  (new tab)", "ctrl+t"),
        ("Alt + Tab  (switch window)", "alt+tab"),
        ("F5  (refresh)", "F5"),
    ]

    def __init__(self, on_add, **kwargs):
        super().__init__(
            title="Press Keys",
            size_hint=(0.9, 0.85),
            **kwargs,
        )
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(8))
        layout.add_widget(Label(
            text="Type a combo or tap a preset.\nUse + between keys:  ctrl+alt+t",
            font_size="15sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(50),
            halign="center",
        ))

        self.key_input = TextInput(
            hint_text="e.g.  ctrl+alt+t",
            font_size="20sp",
            multiline=False,
            size_hint_y=None,
            height=dp(52),
        )
        layout.add_widget(self.key_input)

        scroll = ScrollView()
        grid = GridLayout(cols=1, spacing=dp(5), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))
        for label, combo in self._PRESETS:
            btn = big_button(label, (0.25, 0.42, 0.62, 1), size_hint_y=None, height=dp(56))
            btn.bind(on_press=lambda _, c=combo: self._set_preset(c))
            grid.add_widget(btn)
        scroll.add_widget(grid)
        layout.add_widget(scroll)

        row = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(10))
        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1))
        cancel.bind(on_press=self.dismiss)
        add = big_button("Add Keys", (0.25, 0.42, 0.62, 1))
        add.bind(on_press=self._add)
        row.add_widget(cancel)
        row.add_widget(add)
        layout.add_widget(row)

        self.content = layout

    def _set_preset(self, combo: str):
        self.key_input.text = combo

    def _add(self, *_):
        combo = self.key_input.text.strip()
        if not combo:
            return
        self._on_add({"type": "key", "target": combo})
        self.dismiss()


class AddWatchCornersStepPopup(Popup):
    """Watch all four screen corners for a dismiss button and click it when found."""

    def __init__(self, on_add, **kwargs):
        super().__init__(
            title="Watch Corners for Dismiss Button",
            size_hint=(0.9, None),
            height=dp(420),
            **kwargs,
        )
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(16), spacing=dp(12))

        layout.add_widget(Label(
            text=(
                "Watches all 4 screen corners every 0.5 s.\n"
                "The moment a dismiss button appears\n"
                "(X, arrow, skip, close — anywhere in a corner)\n"
                "it clicks it automatically."
            ),
            font_size="15sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(100),
            halign="center",
        ))

        layout.add_widget(Label(
            text="Give up after how many seconds?",
            font_size="15sp",
            color=(0.3, 0.3, 0.3, 1),
            size_hint_y=None,
            height=dp(30),
            halign="center",
        ))

        self.timeout_input = TextInput(
            text="25",
            font_size="26sp",
            input_filter="int",
            multiline=False,
            size_hint_y=None,
            height=dp(58),
            halign="center",
        )
        layout.add_widget(self.timeout_input)

        layout.add_widget(Label(
            text="(if nothing is found in time, sequence continues anyway)",
            font_size="13sp",
            color=(0.55, 0.55, 0.55, 1),
            size_hint_y=None,
            height=dp(26),
            halign="center",
        ))

        row = BoxLayout(size_hint_y=None, height=dp(60), spacing=dp(10))
        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1))
        cancel.bind(on_press=self.dismiss)
        add = big_button("Add Step", (0.62, 0.1, 0.2, 1))
        add.bind(on_press=self._add)
        row.add_widget(cancel)
        row.add_widget(add)
        layout.add_widget(row)

        self.content = layout

    def _add(self, *_):
        try:
            timeout = max(1, int(self.timeout_input.text.strip() or "25"))
        except ValueError:
            timeout = 25
        self._on_add({"type": "watch_corners", "timeout": timeout})
        self.dismiss()


class AddCheckStepPopup(Popup):
    """Step 1 of 2: pick the template whose presence triggers the branch."""

    def __init__(self, on_add, **kwargs):
        super().__init__(
            title="If Found… — Pick Trigger",
            size_hint=(0.9, 0.82),
            **kwargs,
        )
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        layout.add_widget(Label(
            text="Which button/icon triggers the branch?\n(will be checked visually on screen)",
            font_size="15sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(52),
            halign="center",
        ))

        scroll = ScrollView()
        grid = GridLayout(cols=1, spacing=dp(6), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))

        files = template_files()
        if files:
            for fname in files:
                name = os.path.splitext(fname)[0]
                btn = big_button(
                    name.replace("_", " ").title(),
                    (0.45, 0.15, 0.72, 1),
                    size_hint_y=None, height=dp(70),
                )
                btn.bind(on_press=lambda _, n=name: self._pick(n))
                grid.add_widget(btn)
        else:
            grid.add_widget(Label(
                text="No buttons saved yet.\nGo to My Buttons first.",
                font_size="16sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None, height=dp(80),
                halign="center",
            ))

        scroll.add_widget(grid)
        layout.add_widget(scroll)

        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1), size_hint_y=None, height=dp(54))
        cancel.bind(on_press=self.dismiss)
        layout.add_widget(cancel)
        self.content = layout

    def _pick(self, template_name: str):
        self.dismiss()
        PickBranchSequencePopup(template_name=template_name, on_add=self._on_add).open()


class PickBranchSequencePopup(Popup):
    """Step 2 of 2: pick which saved sequence runs when the trigger is found."""

    def __init__(self, template_name: str, on_add, **kwargs):
        label = template_name.replace("_", " ").title()
        super().__init__(
            title=f"If '{label}' found → run…",
            size_hint=(0.9, 0.82),
            **kwargs,
        )
        self._template_name = template_name
        self._on_add = on_add

        layout = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))
        layout.add_widget(Label(
            text=f'When "{label}" appears on screen,\nrun which sequence?',
            font_size="15sp",
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None, height=dp(52),
            halign="center",
        ))

        scroll = ScrollView()
        grid = GridLayout(cols=1, spacing=dp(6), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))

        files = sequence_files()
        if files:
            for fname in files:
                name = os.path.splitext(fname)[0]
                btn = big_button(name, (0.18, 0.52, 0.82, 1), size_hint_y=None, height=dp(70))
                btn.bind(on_press=lambda _, n=name: self._pick(n))
                grid.add_widget(btn)
        else:
            grid.add_widget(Label(
                text="No other sequences saved yet.\nSave a branch sequence first.",
                font_size="16sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None, height=dp(80),
                halign="center",
            ))

        scroll.add_widget(grid)
        layout.add_widget(scroll)

        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1), size_hint_y=None, height=dp(54))
        cancel.bind(on_press=self.dismiss)
        layout.add_widget(cancel)
        self.content = layout

    def _pick(self, seq_name: str):
        self._on_add({
            "type": "check_branch",
            "target": self._template_name,
            "then_sequence": seq_name,
        })
        self.dismiss()


class LoadSequencePopup(Popup):
    """Pick or delete a saved sequence."""

    def __init__(self, on_load, on_deleted, **kwargs):
        super().__init__(title="My Sequences", size_hint=(0.9, 0.8), **kwargs)
        self._on_load = on_load
        self._on_deleted = on_deleted
        self._build()

    def _build(self):
        layout = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))

        scroll = ScrollView()
        grid = GridLayout(cols=1, spacing=dp(6), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))

        files = sequence_files()
        if files:
            for fname in files:
                name = os.path.splitext(fname)[0]
                row = BoxLayout(size_hint_y=None, height=dp(64), spacing=dp(8))
                btn = big_button(name, (0.18, 0.52, 0.82, 1))
                btn.bind(on_press=lambda _, n=name: self._load(n))
                del_btn = big_button("✕", (0.82, 0.18, 0.18, 1), size_hint_x=None, width=dp(56))
                del_btn.bind(on_press=lambda _, f=fname, n=name: self._delete(f, n))
                row.add_widget(btn)
                row.add_widget(del_btn)
                grid.add_widget(row)
        else:
            grid.add_widget(Label(
                text="No saved sequences yet.",
                font_size="16sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None,
                height=dp(60),
            ))

        scroll.add_widget(grid)
        layout.add_widget(scroll)

        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1), size_hint_y=None, height=dp(54))
        cancel.bind(on_press=self.dismiss)
        layout.add_widget(cancel)
        self.content = layout

    def _load(self, name):
        self._on_load(name)
        self.dismiss()

    def _delete(self, fname, name):
        path = os.path.join(SEQUENCES_DIR, fname)
        if os.path.exists(path):
            os.remove(path)
        self._on_deleted(name)
        self.dismiss()


# ---------------------------------------------------------------------------
# Sequence editor + runner screen
# ---------------------------------------------------------------------------

class SequenceScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._steps = []
        self._loop_on = False
        self._running = False
        self._paused = False
        self._poll_event = None
        self._build_ui()

    def _build_ui(self):
        root = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(8))

        # Header
        header = BoxLayout(size_hint_y=None, height=dp(64), spacing=dp(10))
        back = big_button("← Back", (0.5, 0.5, 0.5, 1), size_hint_x=None, width=dp(100))
        back.bind(on_press=lambda _: setattr(self.manager, "current", "home"))
        header.add_widget(back)
        header.add_widget(Label(
            text="Sequences",
            font_size="22sp",
            bold=True,
            color=(0.1, 0.1, 0.1, 1),
        ))
        root.add_widget(header)

        # Name row + load/save
        name_row = BoxLayout(size_hint_y=None, height=dp(54), spacing=dp(8))
        self.name_input = TextInput(
            hint_text="Sequence name",
            font_size="17sp",
            multiline=False,
        )
        load_btn = big_button("Load", (0.35, 0.35, 0.65, 1), size_hint_x=None, width=dp(80))
        load_btn.bind(on_press=self._open_load)
        save_btn = big_button("Save", (0.1, 0.65, 0.3, 1), size_hint_x=None, width=dp(80))
        save_btn.bind(on_press=self._save_sequence)
        name_row.add_widget(self.name_input)
        name_row.add_widget(load_btn)
        name_row.add_widget(save_btn)
        root.add_widget(name_row)

        # Loop config
        loop_row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(10))
        loop_row.add_widget(Label(
            text="Loop:",
            font_size="16sp",
            color=(0.2, 0.2, 0.2, 1),
            size_hint_x=None,
            width=dp(52),
        ))
        self.loop_btn = Button(
            text="OFF",
            font_size="15sp",
            bold=True,
            background_normal="",
            background_color=(0.6, 0.6, 0.6, 1),
            size_hint_x=None,
            width=dp(68),
        )
        self.loop_btn.bind(on_press=self._toggle_loop)
        loop_row.add_widget(self.loop_btn)
        loop_row.add_widget(Label(
            text="Times (0=∞):",
            font_size="15sp",
            color=(0.2, 0.2, 0.2, 1),
            size_hint_x=None,
            width=dp(110),
        ))
        self.times_input = TextInput(
            text="1",
            font_size="17sp",
            input_filter="int",
            multiline=False,
        )
        loop_row.add_widget(self.times_input)
        root.add_widget(loop_row)

        # Step list
        root.add_widget(Label(
            text="Steps:",
            font_size="16sp",
            bold=True,
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(28),
            halign="left",
        ))

        scroll = ScrollView(size_hint_y=1)
        self.steps_box = BoxLayout(orientation="vertical", spacing=dp(6), size_hint_y=None)
        self.steps_box.bind(minimum_height=self.steps_box.setter("height"))
        scroll.add_widget(self.steps_box)
        root.add_widget(scroll)

        # Add step buttons — row 1
        add_row1 = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(8))
        add_click = big_button("+ Click", (0.18, 0.52, 0.82, 1))
        add_click.bind(on_press=lambda _: AddClickStepPopup(on_add=self._add_step).open())
        add_wait = big_button("+ Wait", (0.68, 0.42, 0.0, 1))
        add_wait.bind(on_press=lambda _: AddWaitStepPopup(on_add=self._add_step).open())
        add_row1.add_widget(add_click)
        add_row1.add_widget(add_wait)
        root.add_widget(add_row1)

        # Add step buttons — row 2
        add_row2 = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(8))
        add_launch = big_button("+ Launch App", (0.1, 0.48, 0.28, 1))
        add_launch.bind(on_press=lambda _: AddLaunchStepPopup(on_add=self._add_step).open())
        add_keys = big_button("+ Press Keys", (0.25, 0.42, 0.62, 1))
        add_keys.bind(on_press=lambda _: AddKeyStepPopup(on_add=self._add_step).open())
        add_row2.add_widget(add_launch)
        add_row2.add_widget(add_keys)
        root.add_widget(add_row2)

        # Add step buttons — row 3
        add_row3 = BoxLayout(size_hint_y=None, height=dp(58), spacing=dp(8))
        add_corners = big_button("👁 Watch Corners", (0.62, 0.1, 0.2, 1))
        add_corners.bind(on_press=lambda _: AddWatchCornersStepPopup(on_add=self._add_step).open())
        add_row3.add_widget(add_corners)
        root.add_widget(add_row3)

        # Add step buttons — row 4 (branching)
        add_branch = big_button(
            "⤵  If Found → Branch to Sequence",
            (0.45, 0.15, 0.72, 1),
            size_hint_y=None, height=dp(52),
        )
        add_branch.bind(on_press=lambda _: AddCheckStepPopup(on_add=self._add_step).open())
        root.add_widget(add_branch)

        # Run / stop + pause / resume
        ctrl_row = BoxLayout(size_hint_y=None, height=dp(72), spacing=dp(8))
        self.run_btn = big_button("▶   RUN SEQUENCE", (0.1, 0.62, 0.18, 1))
        self.run_btn.bind(on_press=self._toggle_run)
        ctrl_row.add_widget(self.run_btn)

        self.pause_btn = big_button("⏸  PAUSE", (0.55, 0.52, 0.05, 1), size_hint_x=None, width=dp(118))
        self.pause_btn.disabled = True
        self.pause_btn.bind(on_press=self._toggle_pause)
        ctrl_row.add_widget(self.pause_btn)

        root.add_widget(ctrl_row)

        # Status
        self.status = status_label()
        root.add_widget(self.status)

        self.add_widget(root)
        self._refresh_steps()

    # -- steps ---------------------------------------------------------------

    def _add_step(self, step: dict):
        self._steps.append(step)
        self._refresh_steps()

    def _remove_step(self, index: int):
        if 0 <= index < len(self._steps):
            self._steps.pop(index)
            self._refresh_steps()

    def _refresh_steps(self):
        self.steps_box.clear_widgets()
        if not self._steps:
            self.steps_box.add_widget(Label(
                text="No steps yet — add Click or Wait steps below.",
                font_size="15sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None,
                height=dp(60),
                halign="center",
            ))
            return

        for i, step in enumerate(self._steps):
            row = BoxLayout(size_hint_y=None, height=dp(56), spacing=dp(8))
            stype = step.get("type")

            if stype == "click":
                desc = f"{i + 1}.  Click  →  {step['target'].replace('_', ' ').title()}"
                color = (0.1, 0.35, 0.75, 1)
            elif stype == "wait":
                secs = step.get("seconds", 1)
                desc = f"{i + 1}.  Wait  →  {secs}s"
                color = (0.6, 0.35, 0.0, 1)
            elif stype == "launch":
                desc = f"{i + 1}.  Launch  →  {step.get('target', '?')}"
                color = (0.05, 0.45, 0.22, 1)
            elif stype == "key":
                desc = f"{i + 1}.  Keys  →  {step.get('target', '?')}"
                color = (0.2, 0.38, 0.58, 1)
            elif stype == "watch_corners":
                t = step.get("timeout", 25)
                desc = f"{i + 1}.  Watch Corners  →  up to {t}s"
                color = (0.62, 0.1, 0.2, 1)
            else:  # check_branch
                tgt = step.get("target", "?").replace("_", " ").title()
                branch = step.get("then_sequence", "?")
                desc = f"{i + 1}.  If '{tgt}' found  →  run '{branch}'"
                color = (0.4, 0.1, 0.58, 1)

            lbl = Label(
                text=desc,
                font_size="17sp",
                color=color,
                halign="left",
                valign="middle",
            )
            lbl.bind(size=lbl.setter("text_size"))
            row.add_widget(lbl)

            del_btn = big_button("✕", (0.82, 0.18, 0.18, 1), size_hint_x=None, width=dp(50))
            del_btn.bind(on_press=lambda _, idx=i: self._remove_step(idx))
            row.add_widget(del_btn)

            self.steps_box.add_widget(row)

    # -- loop toggle ---------------------------------------------------------

    def _toggle_loop(self, *_):
        self._loop_on = not self._loop_on
        if self._loop_on:
            self.loop_btn.text = "ON"
            self.loop_btn.background_color = (0.1, 0.72, 0.3, 1)
        else:
            self.loop_btn.text = "OFF"
            self.loop_btn.background_color = (0.6, 0.6, 0.6, 1)

    # -- save / load ---------------------------------------------------------

    def _save_sequence(self, *_):
        name = self.name_input.text.strip()
        if not name:
            self._set_status("Enter a sequence name first.", (0.8, 0.2, 0.2, 1))
            return
        try:
            loop_count = int(self.times_input.text.strip() or "1")
        except ValueError:
            loop_count = 1
        save_sequence_file(name, {
            "name": name,
            "loop": self._loop_on,
            "loop_count": loop_count,
            "steps": self._steps,
        })
        self._set_status(f"'{name}' saved!", (0.1, 0.6, 0.15, 1))

    def _open_load(self, *_):
        LoadSequencePopup(
            on_load=self._load_sequence,
            on_deleted=lambda n: self._set_status(f"'{n}' deleted.", (0.5, 0.5, 0.5, 1)),
        ).open()

    def _load_sequence(self, name: str):
        data = load_sequence(name)
        self.name_input.text = data.get("name", name)
        self._steps = data.get("steps", [])
        self._loop_on = data.get("loop", False)
        self.loop_btn.text = "ON" if self._loop_on else "OFF"
        self.loop_btn.background_color = (0.1, 0.72, 0.3, 1) if self._loop_on else (0.6, 0.6, 0.6, 1)
        try:
            self.times_input.text = str(data.get("loop_count", 1))
        except Exception:
            self.times_input.text = "1"
        self._refresh_steps()
        self._set_status(f"Loaded '{name}'.", (0.3, 0.3, 0.3, 1))

    # -- run / stop ----------------------------------------------------------

    def _toggle_run(self, *_):
        if self._running:
            self._stop_sequence()
        else:
            self._start_sequence()

    def _build_sequences_dict(self) -> dict:
        """Collect all branch sequences referenced by check_branch steps."""
        out = {}
        for step in self._steps:
            if step.get("type") == "check_branch":
                name = step.get("then_sequence", "")
                if name and name not in out:
                    out[name] = load_sequence(name).get("steps", [])
        return out

    def _start_sequence(self):
        if not self._steps:
            self._set_status("Add some steps first!", (0.8, 0.2, 0.2, 1))
            return
        try:
            loop_count = int(self.times_input.text.strip() or "1")
        except ValueError:
            loop_count = 1
        payload = {
            "steps": self._steps,
            "loop": self._loop_on,
            "loop_count": loop_count,
            "sequences": self._build_sequences_dict(),
        }

        def _send():
            cfg = load_config()
            try:
                resp = requests.post(
                    f"{cfg['server_url']}/sequence/run",
                    json=payload,
                    timeout=6,
                )
                data = resp.json()
                Clock.schedule_once(lambda dt: self._on_start_result(data), 0)
            except requests.exceptions.ConnectionError:
                Clock.schedule_once(
                    lambda dt: self._set_status(
                        "Cannot reach PC — check Wi-Fi and server IP.", (0.8, 0.2, 0.2, 1)
                    ), 0
                )
            except Exception as exc:
                Clock.schedule_once(lambda dt: self._set_status(f"Error: {exc}", (0.8, 0.2, 0.2, 1)), 0)

        threading.Thread(target=_send, daemon=True).start()
        self._set_status("Starting…", (0.4, 0.4, 0.8, 1))

    def _on_start_result(self, data: dict):
        if data.get("success"):
            self._running = True
            self._paused = False
            self.run_btn.text = "■   STOP"
            self.run_btn.background_color = (0.8, 0.15, 0.15, 1)
            self.pause_btn.disabled = False
            self.pause_btn.text = "⏸  PAUSE"
            self.pause_btn.background_color = (0.55, 0.52, 0.05, 1)
            self._set_status("Running…", (0.1, 0.55, 0.1, 1))
            self._start_poll()
        else:
            self._set_status(f"Error: {data.get('message', '?')}", (0.8, 0.2, 0.2, 1))

    def _stop_sequence(self):
        def _send():
            cfg = load_config()
            try:
                requests.post(f"{cfg['server_url']}/sequence/stop", timeout=4)
            except Exception:
                pass

        threading.Thread(target=_send, daemon=True).start()
        self._running = False
        self._paused = False
        self.run_btn.text = "▶   RUN SEQUENCE"
        self.run_btn.background_color = (0.1, 0.62, 0.18, 1)
        self.pause_btn.disabled = True
        self.pause_btn.text = "⏸  PAUSE"
        self.pause_btn.background_color = (0.55, 0.52, 0.05, 1)
        self._set_status("Stopped.", (0.5, 0.5, 0.5, 1))
        self._stop_poll()

    # -- pause / resume ------------------------------------------------------

    def _toggle_pause(self, *_):
        if self._paused:
            self._resume_sequence()
        else:
            self._pause_sequence()

    def _pause_sequence(self):
        def _send():
            cfg = load_config()
            try:
                requests.post(f"{cfg['server_url']}/sequence/pause", timeout=4)
                Clock.schedule_once(lambda dt: self._on_paused(), 0)
            except Exception:
                pass
        threading.Thread(target=_send, daemon=True).start()

    def _on_paused(self):
        self._paused = True
        self.pause_btn.text = "▶  RESUME"
        self.pause_btn.background_color = (0.1, 0.52, 0.65, 1)
        self._set_status("Paused — tap Resume when you're ready.", (0.5, 0.4, 0.0, 1))

    def _resume_sequence(self):
        def _send():
            cfg = load_config()
            try:
                requests.post(f"{cfg['server_url']}/sequence/resume", timeout=4)
                Clock.schedule_once(lambda dt: self._on_resumed(), 0)
            except Exception:
                pass
        threading.Thread(target=_send, daemon=True).start()

    def _on_resumed(self):
        self._paused = False
        self.pause_btn.text = "⏸  PAUSE"
        self.pause_btn.background_color = (0.55, 0.52, 0.05, 1)
        self._set_status("Running…", (0.1, 0.55, 0.1, 1))

    # -- status polling ------------------------------------------------------

    def _start_poll(self):
        self._stop_poll()
        self._poll_event = Clock.schedule_interval(self._poll_status, 2.0)

    def _stop_poll(self):
        if self._poll_event:
            self._poll_event.cancel()
            self._poll_event = None

    def _poll_status(self, *_):
        def _fetch():
            cfg = load_config()
            try:
                resp = requests.get(f"{cfg['server_url']}/sequence/status", timeout=2)
                data = resp.json()
                Clock.schedule_once(lambda dt: self._handle_poll(data), 0)
            except Exception:
                pass

        threading.Thread(target=_fetch, daemon=True).start()

    def _handle_poll(self, data: dict):
        if not data.get("running"):
            self._running = False
            self._paused = False
            self.run_btn.text = "▶   RUN SEQUENCE"
            self.run_btn.background_color = (0.1, 0.62, 0.18, 1)
            self.pause_btn.disabled = True
            self.pause_btn.text = "⏸  PAUSE"
            self.pause_btn.background_color = (0.55, 0.52, 0.05, 1)
            self._set_status("Finished!", (0.1, 0.6, 0.15, 1))
            self._stop_poll()
            return

        # Sync pause state if server disagrees with local state
        server_paused = data.get("paused", False)
        if server_paused and not self._paused:
            self._on_paused()
        elif not server_paused and self._paused:
            self._on_resumed()

        if self._paused:
            return  # don't overwrite the paused status message

        cur = data.get("current_step", 0)
        total = data.get("total_steps", 0)
        step = self._steps[cur - 1] if 0 < cur <= len(self._steps) else {}
        stype = step.get("type", "")
        if stype == "wait":
            desc = f"Waiting {step.get('seconds', '?')}s…"
        elif stype == "click":
            desc = f"Clicking '{step.get('target', '?')}'…"
        elif stype == "launch":
            desc = f"Launching {step.get('target', '?')}…"
        elif stype == "key":
            desc = f"Pressing {step.get('target', '?')}…"
        elif stype == "watch_corners":
            t = step.get("timeout", 25)
            desc = f"Watching corners for dismiss button (up to {t}s)…"
        elif stype == "check_branch":
            tgt = step.get("target", "?").replace("_", " ").title()
            desc = f"Checking for '{tgt}'…"
        else:
            desc = f"Step {cur}/{total}…"
        self._set_status(f"[{cur}/{total}]  {desc}", (0.1, 0.55, 0.1, 1))

    def on_leave(self):
        self._stop_poll()

    # -- helpers -------------------------------------------------------------

    def _set_status(self, text: str, color: tuple):
        self.status.text = text
        self.status.color = color


# ---------------------------------------------------------------------------
# Agent screen — natural language task execution via Ollama
# ---------------------------------------------------------------------------

class PickModelPopup(Popup):
    """Shows available Ollama models fetched from the server."""

    def __init__(self, models: list, on_pick, **kwargs):
        super().__init__(title="Pick Ollama Model", size_hint=(0.88, 0.75), **kwargs)
        self._on_pick = on_pick

        layout = BoxLayout(orientation="vertical", padding=dp(10), spacing=dp(8))

        scroll = ScrollView()
        grid = GridLayout(cols=1, spacing=dp(6), size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))

        if models:
            for m in models:
                btn = big_button(m, (0.18, 0.52, 0.82, 1), size_hint_y=None, height=dp(64))
                btn.bind(on_press=lambda _, name=m: self._pick(name))
                grid.add_widget(btn)
        else:
            grid.add_widget(Label(
                text="No models found.\nMake sure Ollama is running\non your PC.",
                font_size="15sp",
                color=(0.6, 0.6, 0.6, 1),
                size_hint_y=None, height=dp(90),
                halign="center",
            ))

        scroll.add_widget(grid)
        layout.add_widget(scroll)

        cancel = big_button("Cancel", (0.55, 0.55, 0.55, 1), size_hint_y=None, height=dp(54))
        cancel.bind(on_press=self.dismiss)
        layout.add_widget(cancel)
        self.content = layout

    def _pick(self, model: str):
        self._on_pick(model)
        self.dismiss()


class AgentScreen(Screen):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._running = False
        self._poll_event = None
        self._model = "llama3"
        self._build_ui()

    def _build_ui(self):
        root = BoxLayout(orientation="vertical", padding=dp(12), spacing=dp(8))

        # Header
        header = BoxLayout(size_hint_y=None, height=dp(64), spacing=dp(10))
        back = big_button("← Back", (0.5, 0.5, 0.5, 1), size_hint_x=None, width=dp(100))
        back.bind(on_press=lambda _: setattr(self.manager, "current", "home"))
        header.add_widget(back)
        header.add_widget(Label(
            text="AI Agent",
            font_size="22sp",
            bold=True,
            color=(0.1, 0.1, 0.1, 1),
        ))
        root.add_widget(header)

        # Model row
        model_row = BoxLayout(size_hint_y=None, height=dp(50), spacing=dp(8))
        model_row.add_widget(Label(
            text="Model:",
            font_size="15sp",
            color=(0.2, 0.2, 0.2, 1),
            size_hint_x=None,
            width=dp(60),
        ))
        self.model_label = Label(
            text=self._model,
            font_size="15sp",
            color=(0.18, 0.52, 0.82, 1),
            bold=True,
            halign="left",
            valign="middle",
        )
        self.model_label.bind(size=self.model_label.setter("text_size"))
        model_row.add_widget(self.model_label)
        pick_model_btn = big_button(
            "Change", (0.35, 0.35, 0.65, 1),
            size_hint_x=None, width=dp(90),
        )
        pick_model_btn.bind(on_press=self._fetch_models)
        model_row.add_widget(pick_model_btn)
        root.add_widget(model_row)

        # Task input
        root.add_widget(Label(
            text="What should the agent do?",
            font_size="16sp",
            bold=True,
            color=(0.15, 0.15, 0.15, 1),
            size_hint_y=None,
            height=dp(30),
            halign="left",
        ))
        self.task_input = TextInput(
            hint_text='e.g. "open settings and enable dark mode"',
            font_size="17sp",
            multiline=True,
            size_hint_y=None,
            height=dp(90),
        )
        root.add_widget(self.task_input)

        # Max steps
        steps_row = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        steps_row.add_widget(Label(
            text="Max steps:",
            font_size="15sp",
            color=(0.2, 0.2, 0.2, 1),
            size_hint_x=None,
            width=dp(100),
        ))
        self.steps_input = TextInput(
            text="30",
            font_size="17sp",
            input_filter="int",
            multiline=False,
        )
        steps_row.add_widget(self.steps_input)
        root.add_widget(steps_row)

        # Run / Stop
        self.run_btn = big_button(
            "▶   RUN AGENT",
            (0.1, 0.52, 0.72, 1),
            size_hint_y=None,
            height=dp(72),
        )
        self.run_btn.bind(on_press=self._toggle_run)
        root.add_widget(self.run_btn)

        # Status bar
        self.status = status_label()
        root.add_widget(self.status)

        # Divider label
        root.add_widget(Label(
            text="Agent log:",
            font_size="14sp",
            bold=True,
            color=(0.3, 0.3, 0.3, 1),
            size_hint_y=None,
            height=dp(26),
            halign="left",
        ))

        # Scrollable log
        log_scroll = ScrollView()
        self.log_box = BoxLayout(
            orientation="vertical",
            spacing=dp(2),
            size_hint_y=None,
        )
        self.log_box.bind(minimum_height=self.log_box.setter("height"))
        log_scroll.add_widget(self.log_box)
        root.add_widget(log_scroll)

        self.add_widget(root)

    def on_leave(self):
        self._stop_poll()

    # -- model picker --------------------------------------------------------

    def _fetch_models(self, *_):
        self._set_status("Fetching models from server…", (0.4, 0.4, 0.8, 1))

        def _get():
            cfg = load_config()
            try:
                resp = requests.get(f"{cfg['server_url']}/agent/models", timeout=5)
                data = resp.json()
                Clock.schedule_once(lambda dt: self._show_model_picker(data), 0)
            except Exception as exc:
                Clock.schedule_once(
                    lambda dt: self._set_status(f"Error: {exc}", (0.8, 0.2, 0.2, 1)), 0
                )

        threading.Thread(target=_get, daemon=True).start()

    def _show_model_picker(self, data: dict):
        if not data.get("available"):
            self._set_status("Ollama not running on PC.", (0.8, 0.2, 0.2, 1))
            return
        models = data.get("models", [])
        PickModelPopup(
            models=models,
            on_pick=self._on_model_picked,
        ).open()
        self._set_status("", (0.5, 0.5, 0.5, 1))

    def _on_model_picked(self, model: str):
        self._model = model
        self.model_label.text = model
        self._set_status(f"Model set to {model}.", (0.3, 0.3, 0.3, 1))

    # -- run / stop ----------------------------------------------------------

    def _toggle_run(self, *_):
        if self._running:
            self._stop_agent()
        else:
            self._start_agent()

    def _start_agent(self):
        task = self.task_input.text.strip()
        if not task:
            self._set_status("Enter a task first.", (0.8, 0.2, 0.2, 1))
            return
        try:
            max_steps = int(self.steps_input.text.strip() or "30")
        except ValueError:
            max_steps = 30

        payload = {"task": task, "model": self._model, "max_steps": max_steps}

        def _send():
            cfg = load_config()
            try:
                resp = requests.post(
                    f"{cfg['server_url']}/agent/run",
                    json=payload,
                    timeout=6,
                )
                data = resp.json()
                Clock.schedule_once(lambda dt: self._on_start_result(data), 0)
            except requests.exceptions.ConnectionError:
                Clock.schedule_once(
                    lambda dt: self._set_status(
                        "Cannot reach PC — check Wi-Fi and server IP.", (0.8, 0.2, 0.2, 1)
                    ), 0
                )
            except Exception as exc:
                Clock.schedule_once(
                    lambda dt: self._set_status(f"Error: {exc}", (0.8, 0.2, 0.2, 1)), 0
                )

        threading.Thread(target=_send, daemon=True).start()
        self._set_status("Starting agent…", (0.4, 0.4, 0.8, 1))

    def _on_start_result(self, data: dict):
        if data.get("success"):
            self._running = True
            self.run_btn.text = "■   STOP AGENT"
            self.run_btn.background_color = (0.8, 0.15, 0.15, 1)
            self._set_status("Agent running…", (0.1, 0.52, 0.72, 1))
            self.log_box.clear_widgets()
            self._start_poll()
        else:
            self._set_status(f"Error: {data.get('message', '?')}", (0.8, 0.2, 0.2, 1))

    def _stop_agent(self):
        def _send():
            cfg = load_config()
            try:
                requests.post(f"{cfg['server_url']}/agent/stop", timeout=4)
            except Exception:
                pass

        threading.Thread(target=_send, daemon=True).start()
        self._running = False
        self.run_btn.text = "▶   RUN AGENT"
        self.run_btn.background_color = (0.1, 0.52, 0.72, 1)
        self._set_status("Stopped.", (0.5, 0.5, 0.5, 1))
        self._stop_poll()

    # -- polling -------------------------------------------------------------

    def _start_poll(self):
        self._stop_poll()
        self._poll_event = Clock.schedule_interval(self._poll_status, 2.0)

    def _stop_poll(self):
        if self._poll_event:
            self._poll_event.cancel()
            self._poll_event = None

    def _poll_status(self, *_):
        def _fetch():
            cfg = load_config()
            try:
                resp = requests.get(f"{cfg['server_url']}/agent/status", timeout=3)
                Clock.schedule_once(lambda dt: self._handle_poll(resp.json()), 0)
            except Exception:
                pass

        threading.Thread(target=_fetch, daemon=True).start()

    def _handle_poll(self, data: dict):
        # Update log
        log_entries = data.get("log", [])
        current_count = len(self.log_box.children)
        new_entries = log_entries[-(len(log_entries) - current_count):]
        for entry in new_entries:
            lbl = Label(
                text=entry,
                font_size="13sp",
                color=(0.15, 0.15, 0.15, 1),
                halign="left",
                valign="top",
                size_hint_y=None,
            )
            lbl.bind(texture_size=lambda w, v: setattr(w, "height", v[1] + dp(4)))
            lbl.bind(width=lambda w, v: setattr(w, "text_size", (v, None)))
            self.log_box.add_widget(lbl)

        if not data.get("running"):
            self._running = False
            self.run_btn.text = "▶   RUN AGENT"
            self.run_btn.background_color = (0.1, 0.52, 0.72, 1)
            err = data.get("error", "")
            if err:
                self._set_status(f"Error: {err}", (0.8, 0.2, 0.2, 1))
            else:
                step = data.get("step", 0)
                self._set_status(f"Done after {step} steps.", (0.1, 0.6, 0.15, 1))
            self._stop_poll()
            return

        step = data.get("step", 0)
        max_steps = data.get("max_steps", 0)
        last = data.get("last_action", {})
        if last:
            action_str = last.get("type", "").upper()
            target = last.get("target", "")
            reason = last.get("reasoning", "")
            display = f"Step {step}/{max_steps} — {action_str}"
            if target:
                display += f" '{target}'"
            self._set_status(display, (0.1, 0.52, 0.72, 1))
        else:
            self._set_status(f"Step {step}/{max_steps}…", (0.1, 0.52, 0.72, 1))

    def _set_status(self, text: str, color: tuple):
        self.status.text = text
        self.status.color = color


# ---------------------------------------------------------------------------
# App entry point
# ---------------------------------------------------------------------------

class AccessibilityNavigatorApp(App):
    def build(self):
        sm = ScreenManager()
        sm.add_widget(HomeScreen(name="home"))
        sm.add_widget(TemplateScreen(name="templates"))
        sm.add_widget(SequenceScreen(name="sequences"))
        sm.add_widget(AgentScreen(name="agent"))
        return sm


if __name__ == "__main__":
    AccessibilityNavigatorApp().run()
