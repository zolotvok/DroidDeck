package com.droiddeck.launcher.gpu

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONObject

object SteamVr {
    const val APP_ID = "250820"

    enum class Status {
        UNAVAILABLE,
        NOT_INSTALLED,
        INSTALLING,
        READY,
        RUNNING,
    }

    internal fun shouldPoll(status: Status?, cardboardEnabled: Boolean, pictureSwitched: Boolean,
                            glassesControl: Boolean, requested: Boolean): Boolean =
        requested || cardboardEnabled || pictureSwitched || glassesControl ||
            status == Status.RUNNING || status == Status.INSTALLING

    internal fun autoAction(
        status: Status, requested: Boolean, loading: Boolean, steamSession: Boolean,
        startTried: Boolean, installTried: Boolean,
    ): String? {
        if (!requested || loading || !steamSession || startTried) return null
        return when (status) {
            Status.READY -> "start"
            Status.NOT_INSTALLED -> if (installTried) null else "install"
            else -> null
        }
    }

    fun status(context: Context): Status = runCatching {
        parse(AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", "status"), STATUS_TIMEOUT_MS))
    }.getOrDefault(Status.UNAVAILABLE)

    fun run(context: Context, action: String): Boolean = runCatching {
        require(action in setOf("install", "start", "stop"))
        val answer = AgentGuest.call(context, JSONObject().put("kind", "vr").put("action", action), ACTION_TIMEOUT_MS)
        succeeded(action, answer).also { ok -> Log.i(TAG, describe(action, answer, ok)) }
    }.getOrElse { e ->
        Log.w(TAG, "SteamVR $action: no answer from the guest (${e.javaClass.simpleName})")
        false
    }

    /** One app.log line per SteamVR action: the helper's exit code and its last output line (the failure reason). */
    internal fun describe(action: String, answer: JSONObject, ok: Boolean): String =
        "SteamVR $action: " + (if (ok) "ok" else "FAILED") + ", exit " + answer.opt("exitCode") +
            (answer.optString("output").trim().lineSequence().lastOrNull()?.takeIf { it.isNotEmpty() }?.let { ": " + it.take(300) } ?: "")

    fun succeeded(action: String, answer: JSONObject): Boolean = when {
        !answer.optBoolean("ok", false) -> false
        action == "install" -> answer.optBoolean("started", false)
        else -> answer.optInt("exitCode", -1) == 0
    }

    fun parse(answer: JSONObject): Status = when {
        !answer.optBoolean("ok", false) -> Status.UNAVAILABLE
        answer.optInt("exitCode", 0) != 0 -> Status.UNAVAILABLE
        answer.optBoolean("installing") -> Status.INSTALLING
        !answer.optBoolean("driver") -> Status.UNAVAILABLE
        !answer.optBoolean("installed") -> Status.NOT_INSTALLED
        answer.optBoolean("running") -> Status.RUNNING
        else -> Status.READY
    }

    private const val TAG = "SteamVr"
    private const val STATUS_TIMEOUT_MS = 10_000L
    private const val ACTION_TIMEOUT_MS = 40_000L
}
