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

package android.graphics.fonts;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opt-in, process-local hot updates for standard TextViews. Does not replace the global font map.
 * All UI entry points run on the main thread. Only font loading runs on the worker.
 * @hide
 */
public final class SystemFontRuntime {
    private static final String TAG = "SystemFontRuntime";
    private static volatile SystemFontRuntime sInstance;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "SystemFontRuntime"));
    private final FontManager mManager;
    private final Set<TextView> mViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final ArrayList<Runnable> mListeners = new ArrayList<>();
    private SystemFontSnapshot mSnapshot;
    private boolean mLoading;
    private boolean mReloadRequested;
    private boolean mLoadFailed;

    private SystemFontRuntime(Context context) {
        mManager = context.getSystemService(FontManager.class);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                requestReload();
            }
        }, new IntentFilter(FontManager.ACTION_CUSTOM_FONT_CHANGED),
                null, mMain, Context.RECEIVER_NOT_EXPORTED);
        // Register first, then fetch: a selection committed during startup cannot be missed.
        requestReload();
    }

    /** Enable before inflating UI. Currently only the Settings owner process opts in. */
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public static void install(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Install font runtime on the main thread");
        }
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM || sInstance != null) return;
        context.enforceCallingOrSelfPermission(Manifest.permission.UPDATE_FONTS, TAG);
        sInstance = new SystemFontRuntime(context.getApplicationContext());
    }

    /** For the TextView integration; the disabled path does no work beyond this check. */
    public static Typeface resolve(Typeface requested) {
        return sInstance == null || Looper.myLooper() != Looper.getMainLooper()
                || sInstance.mSnapshot == null ? requested
                : sInstance.mSnapshot.resolve(requested);
    }

    public static void attach(TextView view) {
        if (sInstance == null) return;
        sInstance.mViews.add(view);
        view.onSystemFontRuntimeChanged();
    }

    public static void detach(TextView view) {
        if (sInstance != null) sInstance.mViews.remove(view);
    }

    public static @Nullable String getAppliedFontId() {
        return sInstance == null || sInstance.mSnapshot == null ? null
                : sInstance.mSnapshot.selectedId;
    }

    public static boolean hasLoadFailed() {
        return sInstance != null && sInstance.mLoadFailed;
    }

    public static void addChangeListener(Runnable listener) {
        if (sInstance != null && !sInstance.mListeners.contains(listener)) {
            sInstance.mListeners.add(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        if (sInstance != null) sInstance.mListeners.remove(listener);
    }

    /** Also allows a foreground Settings page to retry a failed read/load without polling. */
    public static void refresh() {
        if (sInstance != null) sInstance.requestReload();
    }

    private void requestReload() {
        if (mLoading) {
            mReloadRequested = true;
            return;
        }
        mLoading = true;
        final long generation = mSnapshot == null ? 0 : mSnapshot.generation;
        mWorker.execute(() -> {
            SystemFontSnapshot next = null;
            boolean failed = false;
            try {
                CustomFontRuntimeConfig config = mManager.getCustomFontRuntimeConfig();
                if (config.getGeneration() > generation) next = SystemFontSnapshot.build(config);
            } catch (Exception e) {
                Log.w(TAG, "Keeping previous UI font snapshot", e);
                failed = true;
            }
            final SystemFontSnapshot result = next;
            final boolean error = failed;
            mMain.post(() -> finishReload(result, error));
        });
    }

    private void finishReload(SystemFontSnapshot next, boolean failed) {
        final long started = SystemClock.uptimeMillis();
        mLoading = false;
        if (mReloadRequested) {
            mReloadRequested = false;
            requestReload();
            return; // A newer notification arrived while loading; do not flash the stale result.
        }
        boolean retryViews = mLoadFailed && !failed && mSnapshot != null;
        mLoadFailed = failed;
        boolean publish = next != null
                && (mSnapshot == null || next.generation > mSnapshot.generation);
        if (publish) mSnapshot = next;
        if (publish || retryViews) {
            // Detached views are weakly tracked and resolve again when attached. Snapshot faces
            // already held by Paint/layouts retain their native resources independently.
            for (TextView view : new ArrayList<>(mViews)) {
                if (view != null && view.isAttachedToWindow()) {
                    try {
                        view.onSystemFontRuntimeChanged();
                    } catch (RuntimeException e) {
                        // A custom widget may override setTypeface/setText. Continue updating the
                        // remaining views; this widget can be recreated by its owner if needed.
                        Log.w(TAG, "Unable to refresh text in " + view.getClass().getName(), e);
                        mLoadFailed = true;
                    }
                }
            }
        }
        if (publish || retryViews) {
            Log.i(TAG, "Applied generation=" + mSnapshot.generation
                    + " selection=" + mSnapshot.selectedId + " trackedViews=" + mViews.size()
                    + " updateMs=" + (SystemClock.uptimeMillis() - started)
                    + " failed=" + mLoadFailed);
        }
        for (Runnable listener : new ArrayList<>(mListeners)) listener.run();
    }
}
