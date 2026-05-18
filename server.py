#!/usr/bin/env python3
"""
PC-side REST server — wraps AutomationEngine so the Android app can:
  POST /command                run a natural-language command
  GET  /templates              list saved template names
  POST /templates              upload a new template image (base64)
  DELETE /templates/<name>     remove a template
  POST /sequence/run           run a step sequence in the background
  POST /sequence/stop          stop the running sequence
  POST /sequence/pause         freeze the running sequence
  POST /sequence/resume        unfreeze the running sequence
  GET  /sequence/status        poll running/paused state + current step

Run with:
  python server.py              # auto-picks a free port starting at 5000
  python server.py --port 8080  # use a specific port
"""

import argparse
import base64
import os
import socket
import threading
import time

from flask import Flask, jsonify, request

from agent import Agent
from automation import AutomationEngine

app = Flask(__name__)
engine = AutomationEngine(templates_dir="templates")
_agent = Agent(templates_dir="templates")
TEMPLATES_DIR = "templates"

# ---------------------------------------------------------------------------
# Sequence runner — executes in a background daemon thread
# ---------------------------------------------------------------------------

_seq_lock = threading.Lock()
_seq_thread: threading.Thread | None = None
_seq_stop = threading.Event()
_seq_pause_ev = threading.Event()
_seq_pause_ev.set()  # initially not paused
_seq_status: dict = {"running": False, "paused": False, "current_step": 0, "total_steps": 0}


def _wait_interruptible(seconds: float, stop_ev: threading.Event, pause_ev: threading.Event) -> bool:
    """Sleep for `seconds`, respecting stop and pause signals.
    Extends the deadline while paused so the full wait time is always served.
    Returns True if interrupted by stop."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if stop_ev.is_set():
            return True
        if not pause_ev.is_set():
            end += 0.1  # clock frozen while paused
        time.sleep(0.1)
    return False


def _run_steps(
    steps: list,
    sequences: dict,
    stop_ev: threading.Event,
    pause_ev: threading.Event,
    step_cb=None,
) -> None:
    """Execute a list of steps. Calls itself recursively for check_branch targets."""
    for i, step in enumerate(steps):
        if stop_ev.is_set():
            return

        # Respect pause between steps
        while not pause_ev.is_set():
            if stop_ev.is_set():
                return
            time.sleep(0.1)

        if step_cb:
            step_cb(i + 1)

        stype = step.get("type")

        if stype == "click":
            engine.run_command(f"click {step['target']}")

        elif stype == "launch":
            engine.run_command(f"launch {step['target']}")

        elif stype == "key":
            engine.run_command(f"key {step['target']}")

        elif stype == "wait":
            if _wait_interruptible(float(step.get("seconds", 1)), stop_ev, pause_ev):
                return

        elif stype == "watch_corners":
            # Poll corners every 0.5 s until something is found or timeout expires.
            timeout = float(step.get("timeout", 25))
            deadline = time.monotonic() + timeout
            found = False
            while time.monotonic() < deadline and not stop_ev.is_set():
                while not pause_ev.is_set():
                    if stop_ev.is_set():
                        return
                    time.sleep(0.1)
                result = engine.run_command("scan corners")
                if result.success:
                    found = True
                    break
                time.sleep(0.5)
            # Non-fatal if nothing found — sequence continues

        elif stype == "check_branch":
            target = step.get("target", "")
            then_seq = step.get("then_sequence", "")
            if target and engine.find_element(target) is not None:
                branch_steps = sequences.get(then_seq, [])
                if branch_steps:
                    _run_steps(branch_steps, sequences, stop_ev, pause_ev)
            # Restore main-sequence step counter after branch completes
            if step_cb:
                step_cb(i + 1)


def _execute_sequence(
    steps: list,
    loop: bool,
    loop_count: int,
    sequences: dict,
    stop_ev: threading.Event,
    pause_ev: threading.Event,
) -> None:
    total = len(steps)
    with _seq_lock:
        _seq_status.update({"running": True, "paused": False, "current_step": 0, "total_steps": total})

    def _update_step(n: int) -> None:
        with _seq_lock:
            _seq_status["current_step"] = n

    runs = 0
    try:
        while not stop_ev.is_set():
            _run_steps(steps, sequences, stop_ev, pause_ev, _update_step)
            runs += 1
            if not loop or (loop_count > 0 and runs >= loop_count):
                break
    finally:
        with _seq_lock:
            _seq_status.update({"running": False, "paused": False, "current_step": 0})


def find_free_port(start: int = 5000, attempts: int = 20) -> int:
    for port in range(start, start + attempts):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("", port))
                return port
            except OSError:
                continue
    raise RuntimeError(f"No free port found in range {start}–{start + attempts - 1}.")


@app.route("/command", methods=["POST"])
def run_command():
    data = request.get_json(silent=True) or {}
    command = data.get("command", "").strip()
    if not command:
        return jsonify({"success": False, "message": "Missing 'command' field."}), 400
    result = engine.run_command(command)
    return jsonify({"success": result.success, "message": result.message})


@app.route("/templates", methods=["GET"])
def list_templates():
    os.makedirs(TEMPLATES_DIR, exist_ok=True)
    files = [f for f in os.listdir(TEMPLATES_DIR) if not f.startswith(".")]
    return jsonify({"templates": files})


@app.route("/templates", methods=["POST"])
def upload_template():
    data = request.get_json(silent=True) or {}
    name = data.get("name", "").strip()
    img_b64 = data.get("image", "")
    if not name or not img_b64:
        return jsonify({"success": False, "message": "Missing 'name' or 'image'."}), 400

    os.makedirs(TEMPLATES_DIR, exist_ok=True)
    img_bytes = base64.b64decode(img_b64)
    dest = os.path.join(TEMPLATES_DIR, f"{name}.png")
    with open(dest, "wb") as f:
        f.write(img_bytes)

    return jsonify({"success": True, "path": dest})


@app.route("/templates/<filename>", methods=["DELETE"])
def delete_template(filename):
    path = os.path.join(TEMPLATES_DIR, filename)
    if os.path.exists(path):
        os.remove(path)
    return jsonify({"success": True})


@app.route("/sequence/run", methods=["POST"])
def sequence_run():
    global _seq_thread, _seq_stop, _seq_pause_ev
    data = request.get_json(silent=True) or {}
    steps = data.get("steps", [])
    loop = bool(data.get("loop", False))
    loop_count = int(data.get("loop_count", 1))
    sequences = data.get("sequences", {})  # name → step-list for branch targets
    if not steps:
        return jsonify({"success": False, "message": "No steps provided."}), 400

    _seq_stop.set()
    if _seq_thread and _seq_thread.is_alive():
        _seq_thread.join(timeout=2)

    _seq_stop = threading.Event()
    _seq_pause_ev = threading.Event()
    _seq_pause_ev.set()  # start unpaused

    _seq_thread = threading.Thread(
        target=_execute_sequence,
        args=(steps, loop, loop_count, sequences, _seq_stop, _seq_pause_ev),
        daemon=True,
    )
    _seq_thread.start()
    return jsonify({"success": True, "message": f"Sequence started ({len(steps)} steps)."})


@app.route("/sequence/stop", methods=["POST"])
def sequence_stop():
    _seq_stop.set()
    _seq_pause_ev.set()  # unblock any pause so the thread can see the stop
    return jsonify({"success": True, "message": "Stop signal sent."})


@app.route("/sequence/pause", methods=["POST"])
def sequence_pause():
    _seq_pause_ev.clear()
    with _seq_lock:
        _seq_status["paused"] = True
    return jsonify({"success": True})


@app.route("/sequence/resume", methods=["POST"])
def sequence_resume():
    _seq_pause_ev.set()
    with _seq_lock:
        _seq_status["paused"] = False
    return jsonify({"success": True})


@app.route("/sequence/status", methods=["GET"])
def sequence_status():
    with _seq_lock:
        return jsonify(_seq_status.copy())


# ---------------------------------------------------------------------------
# Perception-driven agent endpoints
# ---------------------------------------------------------------------------

@app.route("/agent/run", methods=["POST"])
def agent_run():
    data = request.get_json(silent=True) or {}
    task = data.get("task", "").strip()
    model = data.get("model", "").strip()
    max_steps = int(data.get("max_steps", 30))
    if not task:
        return jsonify({"success": False, "message": "Missing 'task' field."}), 400
    if model:
        _agent.set_model(model)
    _agent.start(task, max_steps=max_steps)
    return jsonify({"success": True, "message": f"Agent started: {task}"})


@app.route("/agent/stop", methods=["POST"])
def agent_stop():
    _agent.stop()
    return jsonify({"success": True})


@app.route("/agent/status", methods=["GET"])
def agent_status():
    return jsonify(_agent.status)


@app.route("/agent/models", methods=["GET"])
def agent_models():
    models = _agent.list_models()
    available = _agent.ollama_available()
    return jsonify({"available": available, "models": models})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=None, help="Port to listen on (default: auto)")
    args = parser.parse_args()

    port = args.port if args.port else find_free_port(5000)

    # Prefer a non-loopback address so the Android app can reach it
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
    except Exception:
        local_ip = socket.gethostbyname(socket.gethostname())

    print(f"\n  Server ready on port {port}")
    print(f"  Set your Android app to:  http://{local_ip}:{port}\n")
    app.run(host="0.0.0.0", port=port)
