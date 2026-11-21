package com.hindstv.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The newest published build: its tag ("v0.5.22"), versionCode and the Fire TV APK to download. */
data class ReleaseInfo(val tag: String, val versionCode: Int, val apkUrl: String) {
    val versionName: String get() = tag.removePrefix("v")

    companion object {
        const val APK_NAME = "hinds-tv.apk"

        /**
         * Reads GitHub's `releases/latest` JSON. CI tags every build `v0.5.<run number>` and uses the
         * run number as the versionCode, so the tag's last number is the build's versionCode.
         * Null when the release has no Fire TV APK or an unexpected tag. Pure, so it is unit-tested.
         */
        fun parse(json: String): ReleaseInfo? {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val url = (root["assets"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == APK_NAME }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                ?: return null
            return ReleaseInfo(tag, code, url)
        }
    }
}
