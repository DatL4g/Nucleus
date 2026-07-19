#!/usr/bin/env python3
"""CI probe: verifies the Tao Linux AT-SPI projection (AccessKit) end-to-end.

Walks the AT-SPI desktop through pyatspi (the same bus Orca uses), locates the
tao-demo window (launched with NUCLEUS_DEMO_TAB=A11y), and asserts:
  1. the expected named elements are projected,
  2. performing the Increment action updates the click counter.

Requires: at-spi2-core running (registryd), org.a11y.Status.IsEnabled=true
(the CI job sets it with busctl, mirroring scripts/a11y-linux-iter.sh), and
python3-pyatspi. Exit 0 = all assertions hold.
"""
import sys
import time

import pyatspi

TIMEOUT_S = 120
EXPECTED = ["Increment", "Tri-state checkbox", "Notifications switch"]


def walk(node, depth=0, out=None):
    if out is None:
        out = []
    try:
        out.append(node)
        if depth < 14:
            for i in range(node.childCount):
                child = node.getChildAtIndex(i)
                if child is not None:
                    walk(child, depth + 1, out)
    except Exception:
        pass
    return out


def find_app():
    desktop = pyatspi.Registry.getDesktop(0)
    for i in range(desktop.childCount):
        app = desktop.getChildAtIndex(i)
        if app is None:
            continue
        nodes = walk(app)
        names = {n.name for n in nodes if n.name}
        if "Increment" in names:
            return app, nodes
    return None, []


def main():
    failures = 0
    print("── AT-SPI a11y verification ──")
    deadline = time.time() + TIMEOUT_S
    app, nodes = None, []
    while time.time() < deadline:
        app, nodes = find_app()
        if app:
            break
        time.sleep(2)
    if not app:
        print("  [FAIL] no AT-SPI application exposing 'Increment' found")
        sys.exit(1)
    print(f"application: '{app.name}' ({len(nodes)} accessibles)")

    names = {n.name for n in nodes if n.name}
    for expected in EXPECTED:
        ok = expected in names
        print(f"  [{'PASS' if ok else 'FAIL'}] element '{expected}' projected")
        failures += 0 if ok else 1

    # Action round-trip: Increment -> "click counter 1" appears.
    increment = next((n for n in nodes if n.name == "Increment"), None)
    acted = False
    if increment is not None:
        try:
            action = increment.queryAction()
            for i in range(action.nActions):
                action.doAction(i)
                acted = True
                break
        except Exception as e:  # noqa: BLE001 - report and fail below
            print(f"  action error: {e}")
    if acted:
        updated = False
        act_deadline = time.time() + 15
        while time.time() < act_deadline and not updated:
            _, nodes = find_app()
            updated = any(n.name == "click counter 1" for n in nodes if n.name)
            if not updated:
                time.sleep(1)
        print(f"  [{'PASS' if updated else 'FAIL'}] doAction(Increment) -> 'click counter 1'")
        failures += 0 if updated else 1
    else:
        print("  [FAIL] Increment exposes no performable action")
        failures += 1

    print(f"── {failures} failure(s) ──")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
