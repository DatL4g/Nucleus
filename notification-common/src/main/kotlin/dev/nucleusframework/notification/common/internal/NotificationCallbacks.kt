package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.notification.common.DismissReason

internal data class NotificationCallbacks(
    val onActivated: (() -> Unit)?,
    val onDismissed: ((DismissReason) -> Unit)?,
    val onFailed: (() -> Unit)?,
    val buttonCallbacks: Map<String, () -> Unit>,
)
