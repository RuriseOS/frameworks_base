/*
 * Copyright (C) 2026 The RuriseOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import com.android.settingslib.net.DataUsageController
import com.android.systemui.res.R

/**
 * Shared helper for QS tiles that show daily data usage in their secondary label.
 *
 * Owns the [Settings.System.QS_SHOW_DATA_USAGE_TILE] state (observed while the tile is
 * listening, and re-read on every listening start, so toggling it takes effect without a
 * SystemUI restart) and caches the formatted usage text so signal-driven tile refreshes do
 * not trigger a NetworkStats query each time.
 */
class TileDataUsageHelper(
    private val context: Context,
    handler: Handler,
    private val tag: String,
    private val onSettingChanged: () -> Unit,
) : ContentObserver(handler) {

    private val dataController by lazy { DataUsageController(context) }

    // Written on the main handler (observer/onStartListening), read on the tile's background
    // handler (formattedUsage/handleUpdateState); volatile for cross-thread visibility.
    @Volatile
    var showDataUsage: Boolean = readSetting()
        private set

    @Volatile private var cachedText: String? = null
    @Volatile private var cachedKey = 0
    @Volatile private var cacheTimestamp = 0L

    fun onStartListening() {
        // Re-fetch immediately: if the setting was toggled while the tile wasn't listening,
        // the observer callback was missed entirely.
        showDataUsage = readSetting()
        invalidate()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_SHOW_DATA_USAGE_TILE),
            false,
            this,
            UserHandle.USER_ALL,
        )
    }

    fun onStopListening() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        showDataUsage = readSetting()
        invalidate()
        onSettingChanged()
    }

    /**
     * Formatted usage string, e.g. "1.2 GB used today", or "" when unavailable. Runs [query]
     * against NetworkStats at most once per [QUERY_CACHE_MS] for a given [key] (pass e.g. the
     * subscription id so a SIM switch bypasses the cache). Call on the tile's background
     * handler.
     */
    fun formattedUsage(
        key: Int = 0,
        query: DataUsageController.() -> DataUsageController.DataUsageInfo?,
    ): String {
        val now = SystemClock.elapsedRealtime()
        cachedText?.let {
            if (key == cachedKey && now - cacheTimestamp < QUERY_CACHE_MS) return it
        }
        val text =
            try {
                dataController.query()?.takeIf { it.usageLevel >= 0 }?.let { info ->
                    val size =
                        Formatter.formatFileSize(context, info.usageLevel, Formatter.FLAG_IEC_UNITS)
                    context.getString(R.string.usage_data_tile_today, size)
                } ?: ""
            } catch (e: Exception) {
                Log.w(tag, "Failed to get data usage", e)
                ""
            }
        cachedText = text
        cachedKey = key
        cacheTimestamp = now
        return text
    }

    private fun invalidate() {
        cachedText = null
    }

    private fun readSetting(): Boolean =
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.QS_SHOW_DATA_USAGE_TILE,
            1,
            UserHandle.USER_CURRENT,
        ) == 1

    private companion object {
        const val QUERY_CACHE_MS = 30_000L
    }
}
