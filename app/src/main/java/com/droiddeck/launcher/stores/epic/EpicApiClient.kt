package com.droiddeck.launcher.stores.epic

import org.json.JSONObject

object EpicApiClient {
    private fun request(token: String, namespace: String, catalogItemId: String) =
        JSONObject().put("accessToken", token).put("namespace", namespace).put("catalogItemId", catalogItemId)

    @JvmStatic
    fun getManifestApiJson(token: String, namespace: String, catalogItemId: String, appName: String): String? =
        EpicNative.call("build.info", request(token, namespace, catalogItemId).put("appName", appName))
            ?.optJSONObject("info")?.toString()

    @JvmStatic
    fun getCustomAttribute(token: String, namespace: String, catalogItemId: String, name: String): String? {
        val answer = EpicNative.call("catalog.attribute", request(token, namespace, catalogItemId).put("name", name)) ?: return null
        if (answer.optBoolean("unknown", false) || !answer.has("value")) return null
        return answer.optString("value", "")
    }

    internal fun catalogDetails(token: String, namespace: String, catalogItemId: String): JSONObject? =
        EpicNative.call("catalog.details", request(token, namespace, catalogItemId))?.optJSONObject("details")

    /** The ownership verification token (`-epicovt=`), raw bytes; null when Epic gave none. */
    internal fun ownershipToken(token: String, accountId: String, namespace: String, catalogItemId: String): ByteArray? {
        val hex = EpicNative.call("auth.ownershipToken", request(token, namespace, catalogItemId).put("accountId", accountId))
            ?.optString("hex", "")?.ifEmpty { null } ?: return null
        return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    internal fun exchangeCode(token: String): String? =
        EpicNative.call("auth.exchange", JSONObject().put("accessToken", token))?.optString("code", "")?.ifEmpty { null }
}
