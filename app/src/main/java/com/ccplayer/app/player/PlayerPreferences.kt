package com.ccplayer.app.player

import android.content.Context
import android.net.Uri
import com.ccplayer.core.media.color.ColorGrade
import org.json.JSONArray
import org.json.JSONObject

data class RecentMedia(
    val uri: Uri,
    val name: String,
    val lastOpenedMs: Long,
    val positionMs: Long,
)

/** Small local-only store; no account, cloud, database migration, or broad storage permission. */
class PlayerPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("player_preferences", Context.MODE_PRIVATE)

    @Synchronized
    fun recentMedia(): List<RecentMedia> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RecentMedia(
                        uri = Uri.parse(item.getString("uri")),
                        name = item.getString("name"),
                        lastOpenedMs = item.getLong("opened"),
                        positionMs = item.optLong("position"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun recordOpen(uri: Uri, name: String): List<RecentMedia> {
        val all = recentMedia()
        val previousPosition = all.firstOrNull { it.uri == uri }?.positionMs ?: 0
        val existing = all.filterNot { it.uri == uri }
        val updated = listOf(RecentMedia(uri, name, System.currentTimeMillis(), previousPosition)) + existing
        writeRecents(updated)
        return updated.take(MAX_RECENTS)
    }

    @Synchronized
    fun savePosition(uri: Uri, positionMs: Long) {
        val updated = recentMedia().map {
            if (it.uri == uri) it.copy(positionMs = positionMs.coerceAtLeast(0)) else it
        }
        writeRecents(updated)
    }

    @Synchronized
    fun removeRecent(uri: Uri): List<RecentMedia> {
        val updated = recentMedia().filterNot { it.uri == uri }
        writeRecents(updated)
        return updated
    }

    fun positionFor(uri: Uri): Long = recentMedia().firstOrNull { it.uri == uri }?.positionMs ?: 0

    @Synchronized
    fun savePlayback(speed: Float, repeat: Boolean, resizeMode: VideoResizeMode, grade: ColorGrade) {
        preferences.edit()
            .putFloat(KEY_SPEED, speed)
            .putBoolean(KEY_REPEAT, repeat)
            .putString(KEY_RESIZE, resizeMode.name)
            .putFloat(KEY_EXPOSURE, grade.exposureStops)
            .putFloat(KEY_CONTRAST, grade.contrast)
            .putFloat(KEY_SATURATION, grade.saturation)
            .putFloat(KEY_TEMPERATURE, grade.temperature)
            .putFloat(KEY_TINT, grade.tint)
            .apply()
    }

    fun savedSpeed(): Float = preferences.getFloat(KEY_SPEED, 1f).coerceIn(0.25f, 4f)
    fun savedRepeat(): Boolean = preferences.getBoolean(KEY_REPEAT, false)
    fun savedResizeMode(): VideoResizeMode = runCatching {
        VideoResizeMode.valueOf(preferences.getString(KEY_RESIZE, VideoResizeMode.FIT.name)!!)
    }.getOrDefault(VideoResizeMode.FIT)
    fun savedGrade(): ColorGrade = runCatching {
        ColorGrade(
            exposureStops = preferences.getFloat(KEY_EXPOSURE, 0f),
            contrast = preferences.getFloat(KEY_CONTRAST, 0f),
            saturation = preferences.getFloat(KEY_SATURATION, 1f),
            temperature = preferences.getFloat(KEY_TEMPERATURE, 0f),
            tint = preferences.getFloat(KEY_TINT, 0f),
        )
    }.getOrDefault(ColorGrade.IDENTITY)

    private fun writeRecents(items: List<RecentMedia>) {
        val array = JSONArray()
        items.take(MAX_RECENTS).forEach { recent ->
            array.put(
                JSONObject()
                    .put("uri", recent.uri.toString())
                    .put("name", recent.name)
                    .put("opened", recent.lastOpenedMs)
                    .put("position", recent.positionMs),
            )
        }
        preferences.edit().putString(KEY_RECENTS, array.toString()).apply()
    }

    private companion object {
        const val MAX_RECENTS = 8
        const val KEY_RECENTS = "recents"
        const val KEY_SPEED = "speed"
        const val KEY_REPEAT = "repeat"
        const val KEY_RESIZE = "resize"
        const val KEY_EXPOSURE = "exposure"
        const val KEY_CONTRAST = "contrast"
        const val KEY_SATURATION = "saturation"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TINT = "tint"
    }
}
