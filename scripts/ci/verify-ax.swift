// CI probe: verifies the Tao macOS NSAccessibility projection end-to-end.
//
// Locates the tao-demo process (launched with NUCLEUS_DEMO_TAB=A11y), dumps
// its AX tree through the ApplicationServices client API — the same path
// VoiceOver uses — and asserts the expected elements are exposed. The first
// AX attribute read is also what flips the app's a11y pipeline active
// (query-timestamp heuristic), so this exercises activation too.
//
// AX clients need the Accessibility permission (TCC). GitHub runners cannot
// grant it, so when AXIsProcessTrusted() is false the probe prints SKIPPED
// and exits 0 — the hard e2e gates are the Windows/Linux jobs; run this
// locally (once the terminal is trusted) for the macOS leg.
//
// Usage: swift scripts/ci/verify-ax.swift <pid>
// Derived from scripts/axdump.swift / scripts/axtool.swift.
import ApplicationServices
import Cocoa

func attr(_ el: AXUIElement, _ name: String) -> CFTypeRef? {
    var value: CFTypeRef?
    let err = AXUIElementCopyAttributeValue(el, name as CFString, &value)
    return err == .success ? value : nil
}

func collectDescriptions(_ el: AXUIElement, depth: Int, into found: inout Set<String>) {
    if depth > 12 { return }
    if let desc = attr(el, kAXDescriptionAttribute) as? String, !desc.isEmpty {
        found.insert(desc)
    }
    if let title = attr(el, kAXTitleAttribute) as? String, !title.isEmpty {
        found.insert(title)
    }
    if let children = attr(el, kAXChildrenAttribute) as? [AXUIElement] {
        for child in children {
            collectDescriptions(child, depth: depth + 1, into: &found)
        }
    }
}

guard CommandLine.arguments.count >= 2, let pid = Int32(CommandLine.arguments[1]) else {
    FileHandle.standardError.write("usage: verify-ax.swift <pid>\n".data(using: .utf8)!)
    exit(2)
}

print("── AX a11y verification (pid \(pid)) ──")
guard AXIsProcessTrusted() else {
    print("SKIPPED: this process is not an AX-trusted client (TCC Accessibility permission missing).")
    print("Run locally from a trusted terminal for the macOS leg; Windows/Linux CI jobs are the hard gates.")
    exit(0)
}

let app = AXUIElementCreateApplication(pid)
let expected = ["Increment", "Tri-state checkbox", "Notifications switch", "click counter 0"]
var failures = 0
let deadline = Date().addingTimeInterval(120)
var found = Set<String>()
repeat {
    found.removeAll()
    if let windows = attr(app, kAXWindowsAttribute) as? [AXUIElement] {
        for window in windows {
            collectDescriptions(window, depth: 0, into: &found)
        }
    }
    if expected.allSatisfy({ found.contains($0) }) { break }
    Thread.sleep(forTimeInterval: 2)
} while Date() < deadline

for name in expected {
    let ok = found.contains(name)
    print("  [\(ok ? "PASS" : "FAIL")] element '\(name)' exposed")
    if !ok { failures += 1 }
}
print("── \(failures) failure(s) ── (saw \(found.count) named elements)")
exit(failures > 0 ? 1 : 0)
