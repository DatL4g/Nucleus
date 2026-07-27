/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal.validation

import dev.nucleusframework.desktop.application.internal.JvmApplicationContext
import dev.nucleusframework.desktop.application.internal.macBundleNameWarnings
import dev.nucleusframework.desktop.application.internal.resolveMacBundleName

/**
 * Warns about configurations whose macOS `.app` bundle name is ambiguous.
 *
 * Reported on every host OS, not just macOS, so a Linux or Windows developer configuring a macOS
 * distribution still sees the problem.
 */
internal fun JvmApplicationContext.validateMacBundleName() {
    val dist = app.nativeDistributions
    val mac = dist.macOS
    val resolved =
        resolveMacBundleName(
            bundleName = mac.bundleName,
            appName = dist.appName,
            packageName = mac.packageName ?: dist.packageName,
            fallback = project.name,
        )
    for (warning in macBundleNameWarnings(mac.bundleName, dist.appName, mac.packageName, resolved)) {
        project.logger.warn(warning)
    }
}
