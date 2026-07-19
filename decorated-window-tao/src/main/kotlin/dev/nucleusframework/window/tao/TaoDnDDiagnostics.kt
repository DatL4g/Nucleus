package dev.nucleusframework.window.tao

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Stage 0 visibility helper — exposes counters and a recent-events log to
 * the sample so we can verify the Compose DnD plumbing without relying on
 * stdout (which gradle's run task buffers and IDEs filter).
 */
public object TaoDnDDiagnostics {
    public val constructed: MutableIntState = mutableIntStateOf(0)
    public val isRequiredQueries: MutableIntState = mutableIntStateOf(0)
    public val requests: MutableIntState = mutableIntStateOf(0)
    public val transfers: MutableIntState = mutableIntStateOf(0)
    public val lastMessage: MutableState<String?> = mutableStateOf(null)

    public fun log(msg: String) {
        lastMessage.value = msg
    }
}
