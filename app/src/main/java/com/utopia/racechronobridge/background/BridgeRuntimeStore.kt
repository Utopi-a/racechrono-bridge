package com.utopia.racechronobridge.background

import android.content.Context

object BridgeRuntimeStore {
    @Volatile
    private var runtime: BridgeRuntime? = null

    @Synchronized
    fun get(context: Context): BridgeRuntime {
        val current = runtime
        if (current != null) {
            return current
        }
        return BridgeRuntime(context.applicationContext).also { runtime = it }
    }

    @Synchronized
    fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }
}
