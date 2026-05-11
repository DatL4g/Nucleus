package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskInfo
import dev.nucleusframework.scheduler.TaskRequest

/**
 * Platform-specific scheduler backend.
 *
 * Application code should not implement this interface directly.
 * Use the `scheduler-testing` module for test implementations.
 */
@InternalSchedulerApi
public interface PlatformScheduler {
    /** Registers or updates a task with the OS scheduler. Returns true on success. */
    fun enqueue(request: TaskRequest): Boolean

    /** Cancels a scheduled task. Returns true if it was found and removed. */
    fun cancel(taskId: TaskId): Boolean

    /** Cancels all tasks belonging to this application. */
    fun cancelAll()

    /** Returns true if the task is currently scheduled with the OS. */
    fun isScheduled(taskId: TaskId): Boolean

    /** Returns detailed info about a task, or null if not found. */
    fun getTaskInfo(taskId: TaskId): TaskInfo?

    /** Returns info for all tasks registered by this application. */
    fun getAllTasks(): List<TaskInfo>
}
