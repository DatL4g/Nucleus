package dev.nucleusframework.taskbarprogress.linux

import dev.nucleusframework.core.runtime.tools.LinuxDesktopFileDetector as CoreDetector

/** Delegates to [CoreDetector] in core-runtime. */
internal object LinuxDesktopFileDetector {
    val desktopFilename: String? get() = CoreDetector.desktopFilename
}
