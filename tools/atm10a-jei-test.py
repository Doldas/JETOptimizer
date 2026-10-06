#!/usr/bin/env python3
"""Launch ATM10A, join the configured server, and verify JEI startup instrumentation."""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path


DEFAULT_INSTANCE = Path.home() / ".local/share/PrismLauncher/instances/All the Mods 10 Aeronautics ATM10A"
DEFAULT_SERVER = "play.gamitronservers.com"
WINDOW_PATTERN = "ATM10: Aeronautics"


def run(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=check, text=True, capture_output=True)


def read_log(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def profile_blocks(text: str) -> list[tuple[int, str]]:
    starts = list(re.finditer(
        r"^\[[^\n]+\[dev\.jetoptimizer\.JETOptimizer/\]: "
        r"\[JETOptimizer\] JEI initialization profile$",
        text,
        re.MULTILINE,
    ))
    blocks: list[tuple[int, str]] = []
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(text)
        block = text[match.end():end]
        generation = re.search(r"^Connection generation:\s*(\d+)\b", block, re.MULTILINE)
        if generation:
            blocks.append((int(generation.group(1)), block))
    return blocks


def report_has_generation(text: str, generation: int) -> bool:
    reports = re.split(r"\[JETOptimizer\] === OPTIMIZATION REPORT ===", text)
    expected = re.compile(rf"^\s*Generation {generation} \(", re.MULTILINE)
    return any(expected.search(report) for report in reports[1:])


def validate_profile(text: str, generation: int, require_clean_log: bool) -> dict[str, str]:
    if require_clean_log and "Mixin apply for mod jetoptimizer failed" in text:
        raise RuntimeError("A JETOptimizer Mixin failed; see latest.log for the exception.")

    profile = next((block for number, block in profile_blocks(text) if number == generation), None)
    if profile is None:
        raise RuntimeError(f"No JEI initialization profile found for generation {generation}.")

    gate = re.search(r"GUI runtime construction, gated blocks \(sum ([\d.]+) s of ([\d.]+) s, (\d+) gates\):", profile)
    split = re.search(r"fast whitespace splitting:\s*([\d.]+) s over (\d+) calls \((\d+) applied\)", profile)
    total = re.search(r"^Total JEI start:\s*([\d.]+) s$", profile, re.MULTILINE)
    filter_time = re.search(r"^Ingredient filter construction:\s*([\d.]+) s$", profile, re.MULTILINE)
    pipeline = re.search(r"Search-text pipeline .*?:\s*([\d.]+) s over (\d+) calls", profile)
    gui = re.search(r"^JEI GUI runtime construction:\s*([\d.]+) s$", profile, re.MULTILINE)
    kube_index = re.search(
        r"KubeJS remote item-removal ID index \(inside onRuntimeAvailable\):\n"
        r"  optimization: (enabled|disabled)\n"
        r"  filter trees/pattern entries: (\d+)/(\d+)\n"
        r"  leaf candidate stacks/item registry IDs: (\d+)/(\d+)\n"
        r"  source entries/candidate dense IDs: (\d+)/(\d+)\n"
        r"  KubeJS loop entries expected/observed: (\d+)/(\d+)\n"
        r"  Ingredient\.test calls bypassed/original fallback: (\d+)/(\d+)\n"
        r"  manager removal request entries: (\d+)\n"
        r"  index builds/fallback filters/full-scan fallbacks: (\d+)/(\d+)/(\d+)\n"
        r"  dense-ID candidate-index construction:\s*([\d.]+) s",
        profile,
    )
    if (
        gate is None
        or split is None
        or total is None
        or filter_time is None
        or pipeline is None
        or gui is None
        or kube_index is None
    ):
        raise RuntimeError(f"Generation {generation} profile is incomplete; see latest.log.")
    if int(gate.group(3)) != 11:
        raise RuntimeError(f"Expected 11 GUI intervals; found {gate.group(3)}.")
    if int(split.group(2)) == 0 or split.group(2) != split.group(3):
        raise RuntimeError(
            f"Whitespace fast path did not cover all calls: {split.group(2)} calls, "
            f"{split.group(3)} applied."
        )
    if "fallbacks to JEI implementation: none" not in profile:
        raise RuntimeError(f"Generation {generation} reported a fast-path fallback.")
    if kube_index.group(1) != "enabled":
        raise RuntimeError("KubeJS item-removal ID index optimization is disabled.")
    filter_trees = int(kube_index.group(2))
    index_builds = int(kube_index.group(13))
    if filter_trees == 0 and index_builds != 0:
        raise RuntimeError("KubeJS item-removal index was built without any filter tree.")
    if filter_trees > 0 and index_builds != 1:
        raise RuntimeError("KubeJS item-removal fast path was not exercised exactly once.")
    if int(kube_index.group(8)) != int(kube_index.group(7)):
        raise RuntimeError("KubeJS dense candidate loop expected count differs from selected IDs.")
    if int(kube_index.group(9)) != int(kube_index.group(7)):
        raise RuntimeError("KubeJS dense candidate loop did not visit the selected IDs exactly once.")
    if int(kube_index.group(10)) != int(kube_index.group(9)):
        raise RuntimeError("KubeJS candidate predicate bypass count differs from selected candidates.")
    if int(kube_index.group(11)) != 0:
        raise RuntimeError("KubeJS index fast path unexpectedly ran original predicate tests.")
    if int(kube_index.group(12)) != int(kube_index.group(7)):
        raise RuntimeError("KubeJS remote removal request differs from the selected dense candidate count.")
    if int(kube_index.group(14)) != 0 or int(kube_index.group(15)) != 0:
        raise RuntimeError("KubeJS remote item matcher fell back; inspect the reported reason and latest.log.")
    if "KubeJS onRuntimeAvailable phase timing:" not in profile:
        raise RuntimeError("KubeJS onRuntimeAvailable phase timing report is missing from the profile.")

    return {
        "total JEI start": f"{total.group(1)} s",
        "GUI runtime": f"{gui.group(1)} s",
        "ingredient filter": f"{filter_time.group(1)} s",
        "search-text pipeline": f"{pipeline.group(1)} s / {pipeline.group(2)} calls",
        "whitespace fast path": f"{split.group(3)} applied / {split.group(1)} s",
        "KubeJS item ID index": (
            f"{kube_index.group(7)}/{kube_index.group(6)} candidates/source; "
            f"{kube_index.group(10)} predicate calls bypassed; "
            f"{kube_index.group(12)} removals; index {kube_index.group(16)} s"
        ),
        "GUI gates": f"{gate.group(3)} intervals; {gate.group(1)} s covered",
    }


def wait_for_profile(
    log_path: Path,
    generation: int,
    timeout: int,
    not_before_ns: int = 0,
) -> tuple[str, dict[str, str]]:
    deadline = time.monotonic() + timeout
    latest = ""
    while time.monotonic() < deadline:
        try:
            fresh_log = log_path.stat().st_mtime_ns >= not_before_ns
        except FileNotFoundError:
            fresh_log = False
        latest = read_log(log_path) if fresh_log else ""
        if "Mixin apply for mod jetoptimizer failed" in latest:
            raise RuntimeError("A JETOptimizer Mixin failed; stopping before reconnect.")
        if report_has_generation(latest, generation):
            return latest, validate_profile(latest, generation, require_clean_log=True)
        time.sleep(2)
    raise TimeoutError(
        f"Timed out waiting for generation {generation} in {log_path}; "
        "inspect the latest log before retrying."
    )


def game_window(only_visible: bool = False) -> tuple[int, int, int, int, int]:
    command = ["xdotool", "search"]
    if only_visible:
        command.append("--onlyvisible")
    command.extend(("--name", WINDOW_PATTERN))
    result = run(*command, check=False)
    ids = result.stdout.split()
    if not ids:
        raise RuntimeError(f"Could not find Minecraft window matching {WINDOW_PATTERN!r}.")
    window_id = int(ids[-1])
    geometry = run("xdotool", "getwindowgeometry", str(window_id)).stdout
    position = re.search(r"Position:\s*(-?\d+),(-?\d+)", geometry)
    size = re.search(r"Geometry:\s*(\d+)x(\d+)", geometry)
    if not position or not size:
        raise RuntimeError(f"Could not read Minecraft window geometry: {geometry.strip()}")
    return window_id, int(position.group(1)), int(position.group(2)), int(size.group(1)), int(size.group(2))


def prepare_background_options(instance_dir: Path) -> tuple[Path, Path]:
    """Temporarily use windowed/no-pause client options, preserving the user's original file."""
    options_path = instance_dir / "minecraft/options.txt"
    backup_path = instance_dir / "minecraft/options.txt.jetoptimizer-backup"
    if backup_path.exists():
        options_path.write_bytes(backup_path.read_bytes())
        backup_path.unlink()

    original = options_path.read_bytes()
    text = original.decode("utf-8")
    for option in ("fullscreen", "pauseOnLostFocus"):
        expression = re.compile(rf"^{re.escape(option)}:(true|false)$", re.MULTILINE)
        if expression.search(text) is None:
            raise RuntimeError(f"Could not find vanilla {option} setting in {options_path}.")
        text = expression.sub(f"{option}:false", text, count=1)

    backup_path.write_bytes(original)
    options_path.write_text(text, encoding="utf-8")
    return options_path, backup_path


def wait_for_game_window(timeout: int) -> tuple[int, int, int, int, int]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = run("xdotool", "search", "--onlyvisible", "--name", WINDOW_PATTERN, check=False)
        if result.stdout.strip():
            return game_window(only_visible=True)
        time.sleep(0.25)
    raise TimeoutError("Timed out waiting for the ATM10A window to appear.")


def keep_game_behind_current_desktop(previous_focus: str | None, window_id: int) -> None:
    run("xdotool", "windowlower", str(window_id))
    if previous_focus and previous_focus != str(window_id):
        run("xdotool", "windowactivate", "--sync", previous_focus, check=False)


def close_test_game(timeout: int = 40) -> None:
    result = run("xdotool", "search", "--name", WINDOW_PATTERN, check=False)
    for window_id in result.stdout.split():
        run("xdotool", "windowclose", window_id, check=False)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not run("xdotool", "search", "--name", WINDOW_PATTERN, check=False).stdout.strip():
            return
        time.sleep(0.25)
    raise TimeoutError("ATM10A test client did not close cleanly.")


def restore_background_options(options_path: Path | None, backup_path: Path | None) -> None:
    if options_path is None or backup_path is None or not backup_path.exists():
        return
    options_path.write_bytes(backup_path.read_bytes())
    backup_path.unlink()


def reconnect(server_list_wait: float, focus_ui: bool) -> None:
    window_id, x, y, width, height = game_window()
    pointer = run("xdotool", "getmouselocation", "--shell").stdout
    pointer_x = re.search(r"^X=(\d+)$", pointer, re.MULTILINE)
    pointer_y = re.search(r"^Y=(\d+)$", pointer, re.MULTILINE)

    try:
        if focus_ui:
            run("xdotool", "windowactivate", "--sync", str(window_id))
        else:
            run("xdotool", "windowmap", str(window_id), check=False)
            run("xdotool", "windowlower", str(window_id), check=False)

        # Send key/click events directly to the game window, leaving the browser focused.
        run("xdotool", "key", "--window", str(window_id), "Escape")
        time.sleep(1)

        # Multiplayer pause menu's Disconnect button.
        run("xdotool", "mousemove", "--window", str(window_id), str(width // 2), str(int(height * 0.495)))
        run("xdotool", "click", "--window", str(window_id), "1")
        time.sleep(server_list_wait)

        # The saved server is the first row; select it and press the Join Server button.
        run("xdotool", "mousemove", "--window", str(window_id), str(width // 2), str(int(height * 0.092)))
        run("xdotool", "click", "--window", str(window_id), "1")
        time.sleep(0.4)
        run("xdotool", "mousemove", "--window", str(window_id), str(int(width * 0.392)), str(int(height * 0.918)))
        run("xdotool", "click", "--window", str(window_id), "1")
    finally:
        if pointer_x is not None and pointer_y is not None:
            run("xdotool", "mousemove", pointer_x.group(1), pointer_y.group(1))


def reconnect_verified(
    server_list_wait: float,
    focus_ui: bool,
    log_path: Path,
    settle: float = 8.0,
    attempts: int = 3,
) -> None:
    baseline = read_log(log_path).count("Connecting to ")
    last_error: str = ""
    for attempt in range(1, attempts + 1):
        print(f"Reconnect attempt {attempt}/{attempts} (waiting {settle:.0f}s for world entry)...")
        time.sleep(settle)
        reconnect(server_list_wait, focus_ui)
        deadline = time.monotonic() + 25.0
        while time.monotonic() < deadline:
            if read_log(log_path).count("Connecting to ") > baseline:
                return
            time.sleep(1.0)
        last_error = "no new connection started after synthetic input"
        settle = 2.0
    raise RuntimeError(f"Reconnect failed: {last_error}.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-dir", type=Path, default=DEFAULT_INSTANCE)
    parser.add_argument("--prism", default="/usr/bin/prismlauncher")
    parser.add_argument("--server", default=DEFAULT_SERVER)
    parser.add_argument("--reconnects", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=900, help="seconds to wait for each JEI profile")
    parser.add_argument("--attach", action="store_true", help="use a running game and reconnect from its latest profile")
    parser.add_argument("--server-list-wait", type=float, default=2.0)
    parser.add_argument("--focus-ui", action="store_true", help="raise the client during reconnect; this steals desktop focus")
    args = parser.parse_args()

    if args.reconnects < 0:
        parser.error("--reconnects must be zero or greater")
    prism = shutil.which(args.prism)
    if prism is None:
        raise RuntimeError(f"Prism Launcher executable not found: {args.prism}")
    if shutil.which("xdotool") is None:
        raise RuntimeError("xdotool is required for the same-process reconnect step.")

    instance_dir = args.instance_dir
    config = instance_dir / "minecraft/config/jetoptimizer-client.toml"
    log_path = instance_dir / "minecraft/logs/latest.log"
    options_path = instance_dir / "minecraft/options.txt"
    options_backup = instance_dir / "minecraft/options.txt.jetoptimizer-backup"
    if not config.is_file() or not log_path.parent.is_dir():
        raise RuntimeError(f"Not an ATM10A instance directory: {instance_dir}")
    restore_background_options(options_path, options_backup)
    config_text = config.read_text(encoding="utf-8", errors="replace").lower()
    if not re.search(r"^\s*profiling\s*=\s*true\s*$", config_text, re.MULTILINE):
        raise RuntimeError("JETOptimizer profiling must already be enabled; the runner will not edit configs.")
    if not re.search(r"^\s*fastsearchtext\s*=\s*true\s*$", config_text, re.MULTILINE):
        raise RuntimeError("fastSearchText must already be enabled; the runner will not edit configs.")

    launched_here = False
    options_path: Path | None = None
    options_backup: Path | None = None
    try:
        if args.attach:
            existing = read_log(log_path)
            blocks = profile_blocks(existing)
            if not blocks:
                raise RuntimeError("No completed JEI profile found to attach to.")
            last_generation = max(number for number, _ in blocks)
            validate_profile(existing, last_generation, require_clean_log=True)
            game_window()
            launch_started_at = 0
        else:
            if run("xdotool", "search", "--name", WINDOW_PATTERN, check=False).stdout.strip():
                raise RuntimeError("ATM10A is already running; use --attach to continue that session.")
            previous_focus = run("xdotool", "getwindowfocus", check=False).stdout.strip()
            options_path, options_backup = prepare_background_options(instance_dir)
            launch_started_at = time.time_ns()
            subprocess.Popen(
                [prism, "--launch", instance_dir.name, "--server", args.server],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            launched_here = True
            window_id, _, _, _, _ = wait_for_game_window(min(args.timeout, 240))
            keep_game_behind_current_desktop(previous_focus, window_id)
            last_generation = 0

        if args.attach:
            generations = range(last_generation + 1, last_generation + args.reconnects + 1)
        else:
            generations = range(1, args.reconnects + 2)
        for generation in generations:
            if args.attach or generation > 1:
                print(f"Requesting same-process reconnect to {args.server} without raising the client...")
                launch_started_at = time.time_ns()
                reconnect_verified(args.server_list_wait, args.focus_ui, log_path)
            print(f"Waiting for JEI profile generation {generation}...")
            _, summary = wait_for_profile(log_path, generation, args.timeout, launch_started_at)
            print(f"Generation {generation} ({args.server}):")
            for key, value in summary.items():
                print(f"  {key}: {value}")
        print(f"Full log: {log_path}")
        print("No mod list or mod configuration was changed; temporary vanilla window/focus options are restored.")
        return 0
    finally:
        try:
            if launched_here:
                close_test_game()
        finally:
            restore_background_options(options_path, options_backup)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, TimeoutError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
