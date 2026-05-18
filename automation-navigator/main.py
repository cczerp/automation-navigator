#!/usr/bin/env python3
"""
Accessibility Navigator — interactive command loop.

Usage:
    python main.py

Commands:
    click <element>            — click a UI element by text or template name
    double click <element>     — double-click a UI element
    right click <element>      — right-click a UI element
    type <text>                — type a string at the current cursor position
    scroll up/down [N times]   — scroll the mouse wheel
    find <element>             — locate an element and report its position
    screenshot [path]          — save a full-screen capture
    quit / exit                — exit the tool
"""

from automation import AutomationEngine


def main() -> None:
    print(__doc__)
    print("=" * 50)

    engine = AutomationEngine(confidence_threshold=0.8, templates_dir="templates")

    while True:
        try:
            command = input("\n> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting.")
            break

        if not command:
            continue

        if command.lower() in ("quit", "exit", "q"):
            print("Goodbye.")
            break

        result = engine.run_command(command)
        tag = "OK  " if result.success else "FAIL"
        print(f"[{tag}] {result.message}")


if __name__ == "__main__":
    main()
