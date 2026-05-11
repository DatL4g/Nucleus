package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.notification.common.Notification
import dev.nucleusframework.notification.common.NotificationResult

internal interface PlatformDispatcher {
    fun isAvailable(): Boolean

    fun initialize()

    fun send(notification: Notification): NotificationResult

    fun dismiss(platformId: String)
}
