package dev.nucleusframework.servicemanagement

/**
 * Thrown when an [AppServiceManager] operation fails.
 */
public class AppServiceException(
    message: String,
) : Exception(message)
