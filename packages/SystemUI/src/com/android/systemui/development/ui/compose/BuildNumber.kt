/*
 * Copyright (C) 2025-2026 crDroid Android Project
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

package com.android.systemui.development.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
import android.text.format.Formatter
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.settingslib.net.DataUsageController
import com.android.systemui.communal.ui.compose.extensions.detectLongPressGesture
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

private const val WINDOW_DAILY = 0
private const val WINDOW_WEEKLY = 1

/**
 * Minimum interval between passive NetworkStats queries. Connectivity callbacks can fire in
 * bursts; usage figures change slowly, so throttled requests just reuse the last text. Explicit
 * user actions (tap to switch window, double tap to switch SIM) bypass the throttle.
 */
private const val QUERY_THROTTLE_MS = 30_000L

/**
 * QS footer readout. Priority:
 *   1. data usage, when [Settings.System.QS_SHOW_DATA_USAGE] is on;
 *   2. nothing, when [Settings.System.QS_HIDE_BUILD_NUMBER] is on;
 *   3. otherwise the build number (only non-null when developer options are enabled).
 * All toggles are observed, so flipping them takes effect without restarting SystemUI.
 */
@Composable
fun BuildNumber(
    viewModelFactory: BuildNumberViewModel.Factory,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // Evaluate both toggles unconditionally (stable composable calls) before branching.
    val showDataUsage = rememberShowDataUsage()
    val hideBuildNumber = rememberHideBuildNumber()
    when {
        showDataUsage -> DataUsageText(modifier, textColor)
        hideBuildNumber -> Spacer(modifier)
        else -> {
            val viewModel =
                rememberViewModel(traceName = "BuildNumber") { viewModelFactory.create() }
            BuildNumberText(viewModel, modifier, textColor)
        }
    }
}

@Composable
fun BuildNumber(
    viewModel: BuildNumberViewModel,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val showDataUsage = rememberShowDataUsage()
    val hideBuildNumber = rememberHideBuildNumber()
    when {
        showDataUsage -> DataUsageText(modifier, textColor)
        hideBuildNumber -> Spacer(modifier)
        else -> BuildNumberText(viewModel, modifier, textColor)
    }
}

/**
 * Whether the QS footer should show data usage instead of the build number. Observes
 * [Settings.System.QS_SHOW_DATA_USAGE] so the value stays live without restarting SystemUI.
 *
 * Exposed so call sites that gate the footer on the build number being present (e.g. only when
 * developer options are enabled) can also show it when data usage is turned on.
 */
@Composable
fun rememberShowDataUsage(): Boolean =
    rememberSystemBoolean(Settings.System.QS_SHOW_DATA_USAGE, default = false)

/**
 * Whether the build number should be hidden in the QS footer even when developer options are on.
 * Does not affect the data usage readout. Observed for live updates.
 */
@Composable
fun rememberHideBuildNumber(): Boolean =
    rememberSystemBoolean(Settings.System.QS_HIDE_BUILD_NUMBER, default = false)

/** Reads a Settings.System int-as-boolean for the current user and keeps it live via an observer. */
@Composable
private fun rememberSystemBoolean(key: String, default: Boolean): Boolean {
    val context = LocalContext.current
    val cr = context.contentResolver

    fun read(): Boolean =
        try {
            Settings.System.getIntForUser(
                cr,
                key,
                if (default) 1 else 0,
                UserHandle.USER_CURRENT,
            ) != 0
        } catch (_: Throwable) {
            default
        }

    var value by remember(key) { mutableStateOf(read()) }

    DisposableEffect(key) {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { value = read() }
                }
            }
        cr.registerContentObserver(
            Settings.System.getUriFor(key),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { cr.unregisterContentObserver(observer) }
    }

    return value
}

/** The original AOSP build number text with long-press-to-copy. */
@Composable
private fun BuildNumberText(
    viewModel: BuildNumberViewModel,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val buildNumber = viewModel.buildNumber

    if (buildNumber != null) {
        val haptics = LocalHapticFeedback.current
        val copyToClipboardActionLabel = stringResource(id = R.string.copy_to_clipboard_a11y_action)

        Text(
            text = buildNumber.value,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                modifier
                    .borderOnFocus(
                        color = MaterialTheme.colorScheme.secondary,
                        cornerSize = CornerSize(1.dp),
                    )
                    .focusable()
                    .wrapContentWidth()
                    // Using this instead of combinedClickable because this node should not support
                    // single click
                    .pointerInput(Unit) {
                        detectLongPressGesture {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.onBuildNumberLongPress()
                        }
                    }
                    .semantics {
                        onLongClick(copyToClipboardActionLabel) {
                            viewModel.onBuildNumberLongPress()
                            true
                        }
                    }
                    .basicMarquee(iterations = 1, initialDelayMillis = 2000)
                    .minimumInteractiveComponentSize(),
            color = textColor,
            maxLines = 1,
        )
    } else {
        Spacer(modifier)
    }
}

@Composable
private fun DataUsageText(
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val cr = context.contentResolver

    val subMgr = remember { SubscriptionManager.from(context) }
    val duc = remember { DataUsageController(context) }
    val connectivityManager =
        remember { context.getSystemService(ConnectivityManager::class.java) }
    val wifiManager = remember { context.getSystemService(WifiManager::class.java) }

    var usageText by remember { mutableStateOf<String?>(null) }

    var usageWindow by rememberSaveable {
        mutableIntStateOf(
            Settings.System.getIntForUser(
                cr, Settings.System.QS_SHOW_DATA_USAGE_WINDOW, WINDOW_DAILY, UserHandle.USER_CURRENT
            )
        )
    }

    fun setUsageWindow(newVal: Int) {
        usageWindow = newVal
        Settings.System.putIntForUser(
            cr, Settings.System.QS_SHOW_DATA_USAGE_WINDOW, newVal, UserHandle.USER_CURRENT
        )
    }

    var displaySubId by remember { mutableIntStateOf(currentDataSubId(context, subMgr)) }

    // Update requests carry a "force" flag; the single collector below throttles passive
    // requests and runs the actual NetworkStats query off the main thread.
    // replay = 1 so a request emitted before the collector below has subscribed (e.g. the
    // initial refresh) is not dropped.
    val updateRequests = remember {
        MutableSharedFlow<Boolean>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    fun requestUpdate(force: Boolean = false) {
        updateRequests.tryEmit(force)
    }

    /** True when the active network is validated wifi, regardless of SSID visibility. */
    fun isOnWifi(): Boolean {
        val cm = connectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun sanitizeSsid(raw: String?): String? {
        val ssid = raw?.replace("\"", "") ?: return null
        return ssid.takeUnless {
            it.isEmpty() ||
                it.equals("<unknown ssid>", ignoreCase = true) ||
                it.equals("<unknown>", ignoreCase = true)
        }
    }

    /**
     * SSID suffix for the wifi readout. Prefers [WifiManager.getConnectionInfo] (the source that
     * historically resolved on this platform) and falls back to the active network's
     * [NetworkCapabilities.getTransportInfo]; when both are redacted it uses a default label.
     * This is display-only — it must NOT gate whether wifi usage is queried.
     */
    fun wifiSuffix(): String {
        @Suppress("DEPRECATION")
        var ssid = sanitizeSsid(wifiManager?.connectionInfo?.ssid)
        if (ssid == null) {
            val cm = connectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            ssid = sanitizeSsid((caps?.transportInfo as? WifiInfo)?.ssid)
        }
        // Return the full SSID; the readout marquee-scrolls, so truncating with "..." would
        // needlessly hide characters that the scroll already handles (mobile shows its full
        // carrier name the same way).
        return ssid ?: context.getString(R.string.usage_wifi_default_suffix)
    }

    fun fallbackCarrierName(subId: Int): String {
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            val subInfo: SubscriptionInfo? = subMgr.getActiveSubscriptionInfo(subId)
            if (subInfo != null) {
                val name = subInfo.displayName?.toString()
                if (!name.isNullOrBlank()) return name
            }
        }
        val list = subMgr.activeSubscriptionInfoList
        if (!list.isNullOrEmpty()) {
            val name = list[0].displayName?.toString()
            if (!name.isNullOrBlank()) return name
        }
        return context.getString(R.string.usage_data_default_suffix)
    }

    fun formatDataUsage(bytes: Long, suffix: String, weekly: Boolean): String {
        // Whole sentence comes from a positional format string so translations control word order.
        val amount = Formatter.formatFileSize(context, bytes, Formatter.FLAG_IEC_UNITS)
        val fmt = if (weekly) R.string.usage_data_week else R.string.usage_data_today
        return context.getString(fmt, amount, suffix)
    }

    /**
     * Blocking NetworkStats query; runs on a background dispatcher. Returns null when there is
     * nothing to show (radios off / no SIM) so the last known text is kept.
     */
    fun queryUsageText(): String? {
        val weekly = (usageWindow == WINDOW_WEEKLY)

        if (isOnWifi()) {
            val info = if (weekly)
                duc.getWifiWeeklyDataUsageInfo(true) ?: duc.getWifiWeeklyDataUsageInfo(false)
            else
                duc.getWifiDailyDataUsageInfo(true) ?: duc.getWifiDailyDataUsageInfo(false)
            return info?.let { formatDataUsage(it.usageLevel, wifiSuffix(), weekly) }
        }

        if (subMgr.activeSubscriptionInfoCount > 0) {
            val subId = displaySubId.takeIf { SubscriptionManager.isValidSubscriptionId(it) }
                ?: currentDataSubId(context, subMgr)
            displaySubId = subId
            duc.setSubscriptionId(subId)
            val info = if (weekly) duc.getWeeklyDataUsageInfo() else duc.getDailyDataUsageInfo()
            val suffix = info?.carrier?.takeIf { !it.isNullOrBlank() }
                ?: fallbackCarrierName(subId)
            return info?.let { formatDataUsage(it.usageLevel, suffix, weekly) }
        }

        return null
    }

    LaunchedEffect(Unit) {
        var lastQueryTime = 0L
        updateRequests.collect { force ->
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastQueryTime < QUERY_THROTTLE_MS) return@collect
            lastQueryTime = now
            val text = withContext(Dispatchers.IO) {
                try {
                    queryUsageText()
                } catch (_: Throwable) {
                    null
                }
            }
            // Only reassign when the value actually changed: writing an equal-but-new String
            // would recompose the Text and restart the marquee from the beginning, which is
            // what makes the scroll "jump back" on periodic/forced refreshes (e.g. QS expand).
            if (text != null && text != usageText) usageText = text
        }
    }

    // Refresh immediately on first composition and whenever the user switches the usage window
    // or the displayed SIM.
    LaunchedEffect(usageWindow, displaySubId) { requestUpdate(force = true) }

    DisposableEffect(Unit) {
        // Wifi enable/disable and (dis)connect events; deliberately NOT listening to
        // RSSI_CHANGED_ACTION - signal strength changes are frequent and irrelevant to usage.
        val wifiFilter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                requestUpdate()
            }
        }
        context.registerReceiver(receiver, wifiFilter, Context.RECEIVER_NOT_EXPORTED)

        // Only react when the default network's transport actually changes (wifi <-> cell);
        // onCapabilitiesChanged fires far more often than that.
        val netCb = object : NetworkCallback() {
            private var lastWasWifi: Boolean? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (isWifi != lastWasWifi) {
                    lastWasWifi = isWifi
                    requestUpdate(force = true)
                }
            }

            override fun onLost(network: Network) {
                lastWasWifi = null
                requestUpdate()
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(netCb)

        val defaultDataSubObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    displaySubId = currentDataSubId(context, subMgr)
                    requestUpdate()
                }
            }
        }
        cr.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION),
            false,
            defaultDataSubObserver,
            UserHandle.USER_ALL,
        )

        val subListener = object : OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (!SubscriptionManager.isValidSubscriptionId(displaySubId) ||
                    subMgr.getActiveSubscriptionInfo(displaySubId) == null
                ) {
                    displaySubId = currentDataSubId(context, subMgr)
                }
                requestUpdate()
            }
        }
        subMgr.addOnSubscriptionsChangedListener(context.mainExecutor, subListener)

        val windowObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    usageWindow = Settings.System.getIntForUser(
                        cr,
                        Settings.System.QS_SHOW_DATA_USAGE_WINDOW,
                        WINDOW_DAILY,
                        UserHandle.USER_CURRENT,
                    )
                }
            }
        }
        cr.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_SHOW_DATA_USAGE_WINDOW),
            false,
            windowObserver,
            UserHandle.USER_ALL,
        )

        onDispose {
            context.unregisterReceiver(receiver)
            connectivityManager?.unregisterNetworkCallback(netCb)
            cr.unregisterContentObserver(defaultDataSubObserver)
            cr.unregisterContentObserver(windowObserver)
            subMgr.removeOnSubscriptionsChangedListener(subListener)
        }
    }

    val textToShow = usageText.orEmpty()

    val base = modifier
        .borderOnFocus(
            color = MaterialTheme.colorScheme.secondary,
            cornerSize = CornerSize(1.dp),
        )
        .focusable()
        .wrapContentWidth()
        .minimumInteractiveComponentSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    if (!usageText.isNullOrEmpty()) {
                        val next = if (usageWindow == WINDOW_DAILY) WINDOW_WEEKLY else WINDOW_DAILY
                        setUsageWindow(next)
                    }
                },
                onDoubleTap = {
                    if (!usageText.isNullOrEmpty()) {
                        val list = subMgr.activeSubscriptionInfoList
                        if (!list.isNullOrEmpty() && list.size > 1) {
                            val ids = list.sortedBy { it.simSlotIndex }.map { it.subscriptionId }
                            val idx = ids.indexOf(displaySubId).let { if (it < 0) 0 else it }
                            displaySubId = ids[(idx + 1) % ids.size]
                        }
                    }
                },
                onLongPress = {
                    if (!usageText.isNullOrEmpty()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        openDataUsageSettings(context)
                    }
                }
            )
        }
        .semantics {
            onLongClick("Open data usage settings") {
                if (!usageText.isNullOrEmpty()) {
                    openDataUsageSettings(context)
                    true
                } else false
            }
        }

    val marquee = if (textToShow.isNotEmpty()) {
        base.basicMarquee(iterations = 1, initialDelayMillis = 2000)
    } else {
        base
    }

    Text(
        text = textToShow,
        style = MaterialTheme.typography.bodySmall,
        modifier = marquee.alpha(if (textToShow.isNotEmpty()) 1f else 0f),
        color = textColor,
        maxLines = 1,
    )
}

fun openDataUsageSettings(context: Context) {
    val intent = Intent(Settings.ACTION_DATA_USAGE_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivityAsUser(intent, UserHandle.CURRENT)
    } catch (_: Throwable) {
        val fallback = Intent(Intent.ACTION_MAIN).apply {
            setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$DataUsageSummaryActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivityAsUser(fallback, UserHandle.CURRENT)
    }
}

private fun currentDataSubId(context: Context, subMgr: SubscriptionManager): Int {
    val fromSettings = Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    )
    if (SubscriptionManager.isValidSubscriptionId(fromSettings)) {
        return fromSettings
    }
    val fallback = SubscriptionManager.getDefaultDataSubscriptionId()
    if (SubscriptionManager.isValidSubscriptionId(fallback)) {
        return fallback
    }
    val active = subMgr.activeSubscriptionInfoList
    return if (!active.isNullOrEmpty()) active[0].subscriptionId
    else SubscriptionManager.INVALID_SUBSCRIPTION_ID
}
