package io.github.kdroidfilter.nucleus.notification.common.internal

import io.github.kdroidfilter.nucleus.notification.ActionOption
import io.github.kdroidfilter.nucleus.notification.CategoryOption
import io.github.kdroidfilter.nucleus.notification.DeliveredNotification
import io.github.kdroidfilter.nucleus.notification.NotificationAction
import io.github.kdroidfilter.nucleus.notification.NotificationAttachment
import io.github.kdroidfilter.nucleus.notification.NotificationCategory
import io.github.kdroidfilter.nucleus.notification.NotificationCenter
import io.github.kdroidfilter.nucleus.notification.NotificationCenterDelegate
import io.github.kdroidfilter.nucleus.notification.NotificationContent
import io.github.kdroidfilter.nucleus.notification.NotificationRequest
import io.github.kdroidfilter.nucleus.notification.NotificationResponse
import io.github.kdroidfilter.nucleus.notification.PresentationOption
import io.github.kdroidfilter.nucleus.notification.common.DismissReason
import io.github.kdroidfilter.nucleus.notification.common.Notification
import io.github.kdroidfilter.nucleus.notification.common.NotificationHandle
import io.github.kdroidfilter.nucleus.notification.common.NotificationResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

internal class MacOsDispatcher private constructor() : PlatformDispatcher {
    private val logger = Logger.getLogger(MacOsDispatcher::class.java.simpleName)

    @Volatile
    private var delegateRegistered = false

    // Cache category registrations: button-titles-signature -> categoryId
    private val categoryCache = ConcurrentHashMap<String, String>()

    private val delegate =
        object : NotificationCenterDelegate {
            override fun willPresent(notification: DeliveredNotification): Set<PresentationOption> =
                setOf(PresentationOption.BANNER, PresentationOption.SOUND)

            override fun didReceive(response: NotificationResponse) {
                val id = response.notification.identifier
                val actionId = response.actionIdentifier
                val callbacks =
                    when (actionId) {
                        NotificationAction.DISMISS_ACTION_IDENTIFIER -> CallbackRegistry.remove(id)
                        else -> CallbackRegistry.get(id)
                    }
                callbacks ?: return

                try {
                    when {
                        actionId == NotificationAction.DEFAULT_ACTION_IDENTIFIER ->
                            callbacks.onActivated?.invoke()
                        actionId == NotificationAction.DISMISS_ACTION_IDENTIFIER ->
                            callbacks.onDismissed?.invoke(DismissReason.USER_DISMISSED)
                        actionId.startsWith("btn_") ->
                            callbacks.buttonCallbacks[actionId]?.invoke()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification callback", e)
                }
            }
        }

    companion object {
        fun createIfAvailable(): MacOsDispatcher? =
            try {
                Class.forName("io.github.kdroidfilter.nucleus.notification.NotificationCenter")
                MacOsDispatcher()
            } catch (_: ClassNotFoundException) {
                null
            }
    }

    override fun isAvailable(): Boolean = NotificationCenter.isAvailable

    override fun initialize() {
        // macOS does not require explicit initialization beyond delegate
    }

    override fun send(notification: Notification): NotificationResult {
        ensureDelegateRegistered()

        val identifier = UUID.randomUUID().toString()

        // Register category for buttons if needed
        val categoryId =
            if (notification.buttons.isNotEmpty()) {
                registerCategoryForButtons(notification)
            } else {
                // Even without buttons, register a category with CUSTOM_DISMISS_ACTION
                // so onDismissed fires
                if (notification.onDismissed != null) {
                    registerDismissOnlyCategory()
                } else {
                    ""
                }
            }

        val attachments =
            if (notification.largeImage != null) {
                listOf(NotificationAttachment(identifier = "largeImage", url = notification.largeImage))
            } else {
                emptyList()
            }

        val content =
            NotificationContent(
                title = notification.title,
                body = notification.message,
                categoryIdentifier = categoryId,
                attachments = attachments,
            )

        val request =
            NotificationRequest(
                identifier = identifier,
                content = content,
            )

        val buttonCallbacks = mutableMapOf<String, () -> Unit>()
        notification.buttons.forEachIndexed { index, button ->
            buttonCallbacks["btn_$index"] = button.onClick
        }

        CallbackRegistry.register(
            identifier,
            NotificationCallbacks(
                onActivated = notification.onActivated,
                onDismissed = notification.onDismissed,
                onFailed = notification.onFailed,
                buttonCallbacks = buttonCallbacks,
            ),
        )

        NotificationCenter.add(request) { error ->
            if (error != null) {
                CallbackRegistry.remove(identifier)
                notification.onFailed?.invoke()
            }
        }

        return NotificationResult.Success(NotificationHandle(identifier, this))
    }

    override fun dismiss(platformId: String) {
        NotificationCenter.removeDeliveredNotifications(listOf(platformId))
    }

    private fun registerCategoryForButtons(notification: Notification): String {
        val signature = notification.buttons.joinToString("|") { it.title }
        return categoryCache.getOrPut(signature) {
            val categoryId = "ncm_${categoryCache.size}"
            val actions =
                notification.buttons.mapIndexed { index, button ->
                    NotificationAction(
                        identifier = "btn_$index",
                        title = button.title,
                        options = setOf(ActionOption.FOREGROUND),
                    )
                }
            val category =
                NotificationCategory(
                    identifier = categoryId,
                    actions = actions,
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                )
            registerAllCategories(category)
            categoryId
        }
    }

    private fun registerDismissOnlyCategory(): String {
        val categoryId = "ncm_dismiss"
        return categoryCache.getOrPut(categoryId) {
            val category =
                NotificationCategory(
                    identifier = categoryId,
                    actions = emptyList(),
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                )
            registerAllCategories(category)
            categoryId
        }
    }

    private fun registerAllCategories(newCategory: NotificationCategory) {
        // Collect all registered categories and add the new one
        val allCategories =
            categoryCache.values
                .map { id ->
                    // We only have the ID here; rebuild from what we know
                    // For simplicity, re-register everything via a fresh set call
                    NotificationCategory(identifier = id, options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION))
                }.toMutableSet()
        allCategories.add(newCategory)
        NotificationCenter.setNotificationCategories(allCategories)
    }

    private fun ensureDelegateRegistered() {
        if (!delegateRegistered) {
            synchronized(this) {
                if (!delegateRegistered) {
                    NotificationCenter.setDelegate(delegate)
                    delegateRegistered = true
                }
            }
        }
    }
}
