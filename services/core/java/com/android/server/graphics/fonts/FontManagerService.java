/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.fonts.CustomFontConfig;
import android.graphics.fonts.CustomFontInfo;
import android.graphics.fonts.CustomFontRuntimeConfig;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.ServiceSpecificException;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.text.FontConfig;
import android.util.AndroidException;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.graphics.fonts.IFontManager;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.text.flags.Flags;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.DirectByteBuffer;
import java.nio.NioUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** A service for managing system fonts. */
public final class FontManagerService extends IFontManager.Stub {
    private static final String TAG = "FontManagerService";

    private static final String FONT_FILES_DIR = "/data/fonts/files";
    private static final String CONFIG_XML_FILE = "/data/fonts/config/config.xml";

    @android.annotation.EnforcePermission(android.Manifest.permission.UPDATE_FONTS)
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    @Override
    public FontConfig getFontConfig() {
        super.getFontConfig_enforcePermission();

        return getSystemFontConfig();
    }

    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    @Override
    public int updateFontFamily(@NonNull List<FontUpdateRequest> requests, int baseVersion) {
        try {
            Preconditions.checkArgumentNonnegative(baseVersion);
            Objects.requireNonNull(requests);
            getContext().enforceCallingPermission(Manifest.permission.UPDATE_FONTS,
                    "UPDATE_FONTS permission required.");
            try {
                update(baseVersion, requests);
                return FontManager.RESULT_SUCCESS;
            } catch (SystemFontException e) {
                Slog.e(TAG, "Failed to update font family", e);
                return e.getErrorCode();
            }
        } finally {
            closeFileDescriptors(requests);
        }
    }

    private static void closeFileDescriptors(@Nullable List<FontUpdateRequest> requests) {
        // Make sure we close every passed FD, even if 'requests' is constructed incorrectly and
        // some fields are null.
        if (requests == null) return;
        for (FontUpdateRequest request : requests) {
            if (request == null) continue;
            ParcelFileDescriptor fd = request.getFd();
            if (fd == null) continue;
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to close fd", e);
            }
        }
    }

    /* package */ static class SystemFontException extends AndroidException {
        private final int mErrorCode;

        SystemFontException(@FontManager.ResultCode int errorCode, String msg, Throwable cause) {
            super(msg, cause);
            mErrorCode = errorCode;
        }

        SystemFontException(int errorCode, String msg) {
            super(msg);
            mErrorCode = errorCode;
        }

        @FontManager.ResultCode
        int getErrorCode() {
            return mErrorCode;
        }
    }

    /** Class to manage FontManagerService's lifecycle. */
    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;
        private final CompletableFuture<Void> mServiceStarted = new CompletableFuture<>();

        public Lifecycle(@NonNull Context context, boolean safeMode) {
            super(context);
            mService = new FontManagerService(context, safeMode, mServiceStarted);
        }

        @Override
        public void onStart() {
            LocalServices.addService(FontManagerInternal.class,
                    new FontManagerInternal() {
                        @Override
                        @Nullable
                        public SharedMemory getSerializedSystemFontMap() {
                            if (!Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                                return null;
                            }
                            mServiceStarted.join();
                            return mService.getCurrentFontMap();
                        }
                    });
            publishBinderService(Context.FONT_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_BOOT_COMPLETED) {
                synchronized (mService.mUpdatableFontDirLock) {
                    mService.mCustomFontStore.bootCompleted();
                }
            }
            final int latestFontLoadBootPhase =
                    (Flags.completeFontLoadInSystemServicesReady())
                            // Complete font load in the phase before PHASE_SYSTEM_SERVICES_READY
                            ? SystemService.PHASE_LOCK_SETTINGS_READY
                            : SystemService.PHASE_ACTIVITY_MANAGER_READY;
            if (phase == latestFontLoadBootPhase) {
                // Wait for FontManagerService to start since it will be needed after this point.
                mServiceStarted.join();
            }
        }
    }

    private static class FsverityUtilImpl implements UpdatableFontDir.FsverityUtil {

        private final String[] mDerCertPaths;

        FsverityUtilImpl(String[] derCertPaths) {
            mDerCertPaths = derCertPaths;
        }

        @Override
        public boolean isFromTrustedProvider(String fontPath, byte[] pkcs7Signature) {
            final byte[] digest = VerityUtils.getFsverityDigest(fontPath);
            if (digest == null) {
                Log.w(TAG, "Failed to get fs-verity digest for " + fontPath);
                return false;
            }
            for (String certPath : mDerCertPaths) {
                try (InputStream is = new FileInputStream(certPath)) {
                    if (VerityUtils.verifyPkcs7DetachedSignature(pkcs7Signature, digest, is)) {
                        return true;
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to read certificate file: " + certPath);
                }
            }
            return false;
        }

        @Override
        public void setUpFsverity(String filePath) throws IOException {
            VerityUtils.setUpFsverity(filePath);
        }

        @Override
        public boolean rename(File src, File dest) {
            // rename system call preserves fs-verity bit.
            return src.renameTo(dest);
        }
    }

    @NonNull
    private final Context mContext;

    private final boolean mIsSafeMode;

    private final CustomFontStore mCustomFontStore;
    private final CompletableFuture<Void> mServiceStarted;
    @GuardedBy("mUpdatableFontDirLock")
    private boolean mCustomFontsLoaded;

    // Separate from the boot map: only explicitly opted-in UI clients consume these snapshots.
    @GuardedBy("mUpdatableFontDirLock")
    private long mRuntimeGeneration = 1;
    @GuardedBy("mUpdatableFontDirLock")
    private CustomFontRuntimeConfig mRuntimeConfig;

    private final Object mUpdatableFontDirLock = new Object();

    private String mDebugCertFilePath = null;

    @GuardedBy("mUpdatableFontDirLock")
    @Nullable
    private UpdatableFontDir mUpdatableFontDir;

    // mSerializedFontMapLock can be acquired while holding mUpdatableFontDirLock.
    // mUpdatableFontDirLock should not be newly acquired while holding mSerializedFontMapLock.
    private final Object mSerializedFontMapLock = new Object();

    @GuardedBy("mSerializedFontMapLock")
    @Nullable
    private SharedMemory mSerializedFontMap = null;

    private FontManagerService(
            Context context, boolean safeMode, CompletableFuture<Void> serviceStarted) {
        if (safeMode) {
            Slog.i(TAG, "Entering safe mode. Deleting all font updates.");
            UpdatableFontDir.deleteAllFiles(new File(FONT_FILES_DIR), new File(CONFIG_XML_FILE));
        }
        mContext = context;
        mIsSafeMode = safeMode;
        mServiceStarted = serviceStarted;
        mCustomFontStore = new CustomFontStore(new File("/data/fonts/custom"), safeMode,
                VerityUtils.isFsVeritySupported());

        if (Flags.useOptimizedBoottimeFontLoading()) {
            Slog.i(TAG, "Using optimized boot-time font loading.");
            SystemServerInitThreadPool.submit(() -> {
                initialize();

                // The preinstalled-only path already loads the system map in initialize().
                // Updated/custom maps, including recovery from a custom map, need deserialization.
                synchronized (mUpdatableFontDirLock) {
                    if (mUpdatableFontDir != null
                            || !mCustomFontStore.activeId().isEmpty()
                            || mCustomFontStore.wasRecovered()) {
                        setSystemFontMap();
                    }
                }
                serviceStarted.complete(null);
            }, "FontManagerService_create");
        } else {
            Slog.i(TAG, "Not using optimized boot-time font loading.");
            initialize();
            setSystemFontMap();
            serviceStarted.complete(null);
        }
    }

    private void setSystemFontMap() {
        try {
            Typeface.setSystemFontMap(getCurrentFontMap());
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to set system font map of system_server");
        }
    }

    @Nullable
    private UpdatableFontDir createUpdatableFontDir() {
        // Never read updatable font files in safe mode.
        if (mIsSafeMode) return null;
        // If apk verity is supported, fs-verity should be available.
        if (!VerityUtils.isFsVeritySupported()) return null;

        String[] certs = mContext.getResources().getStringArray(
                R.array.config_fontManagerServiceCerts);

        if (mDebugCertFilePath != null && Build.IS_DEBUGGABLE) {
            String[] tmp = new String[certs.length + 1];
            System.arraycopy(certs, 0, tmp, 0, certs.length);
            tmp[certs.length] = mDebugCertFilePath;
            certs = tmp;
        }

        return new UpdatableFontDir(new File(FONT_FILES_DIR), new OtfFontFileParser(),
                new FsverityUtilImpl(certs), new File(CONFIG_XML_FILE));
    }

    /**
     * Add debug certificate to the cert list. This must be called only on debuggable build.
     *
     * @param debugCertPath a debug certificate file path
     */
    public void addDebugCertificate(@Nullable String debugCertPath) {
        mDebugCertFilePath = debugCertPath;
    }

    private void initialize() {
        synchronized (mUpdatableFontDirLock) {
            if (!mCustomFontsLoaded) {
                mCustomFontStore.load();
                mCustomFontsLoaded = true;
            }
            mUpdatableFontDir = createUpdatableFontDir();
            if (mUpdatableFontDir == null && mCustomFontStore.activeId().isEmpty()) {
                if (Flags.useOptimizedBoottimeFontLoading()) {
                    // If fs-verity is not supported, load preinstalled system font map and use it
                    // for all apps.
                    Typeface.loadPreinstalledSystemFontMap();
                }
                setSerializedFontMap(serializeSystemServerFontMap());
                return;
            }
            if (mUpdatableFontDir != null) mUpdatableFontDir.loadFontFileMap();
            updateSerializedFontMap();
        }
    }

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @Nullable /* package */ SharedMemory getCurrentFontMap() {
        synchronized (mSerializedFontMapLock) {
            return mSerializedFontMap;
        }
    }

    /* package */ void update(int baseVersion, List<FontUpdateRequest> requests)
            throws SystemFontException {
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED,
                        "The font updater is disabled.");
            }
            // baseVersion == -1 only happens from shell command. This is filtered and treated as
            // error from SystemApi call.
            if (baseVersion != -1 && mUpdatableFontDir.getConfigVersion() != baseVersion) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_VERSION_MISMATCH,
                        "The base config version is older than current.");
            }
            mUpdatableFontDir.update(requests);
            updateSerializedFontMap();
        }
        notifyCustomFontChanged();
    }

    /**
     * Clears all updates and restarts FontManagerService.
     *
     * <p>CAUTION: this method is not safe. Existing processes may crash due to missing font files.
     * This method is only for {@link FontManagerShellCommand}.
     */
    /* package */ void clearUpdates() {
        UpdatableFontDir.deleteAllFiles(new File(FONT_FILES_DIR), new File(CONFIG_XML_FILE));
        initialize();
    }

    /**
     * Restarts FontManagerService, removing not-the-latest font files.
     *
     * <p>CAUTION: this method is not safe. Existing processes may crash due to missing font files.
     * This method is only for {@link FontManagerShellCommand}.
     */
    /* package */ void restart() {
        initialize();
    }

    /* package */ Map<String, File> getFontFileMap() {
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                return Collections.emptyMap();
            }
            return mUpdatableFontDir.getPostScriptMap();
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        synchronized (mUpdatableFontDirLock) {
            mCustomFontStore.dump(writer);
            writer.println("  UI runtime generation=" + mRuntimeGeneration + " selection="
                    + (mRuntimeConfig == null ? "<not requested>" : mRuntimeConfig.getSelectedId()));
        }
        new FontManagerShellCommand(this).dumpAll(new IndentingPrintWriter(writer, "  "));
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in,
            @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args,
            @Nullable ShellCallback callback,
            @NonNull ResultReceiver result) {
        new FontManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    /**
     * Returns an active system font configuration.
     */
    public @NonNull FontConfig getSystemFontConfig() {
        synchronized (mUpdatableFontDirLock) {
            FontConfig base = getBaseFontConfig();
            try {
                return customFontConfig(base, mCustomFontStore.activeId());
            } catch (IOException | RuntimeException e) {
                Slog.e(TAG, "Cannot load selected system font; using ROM defaults", e);
                mCustomFontStore.recover();
                return base;
            }
        }
    }

    @GuardedBy("mUpdatableFontDirLock")
    private FontConfig getBaseFontConfig() {
        return mUpdatableFontDir == null ? SystemFonts.getSystemPreinstalledFontConfig()
                : mUpdatableFontDir.getSystemFontConfig();
    }

    private String[] builtInFontFamilies() {
        return mContext.getResources().getStringArray(R.array.config_customSystemFontFamilies);
    }

    private String[] customFontTargets() {
        List<String> targets = new ArrayList<>(Arrays.asList(mContext.getResources()
                .getStringArray(R.array.config_customSystemFontTargets)));
        targets.add(mContext.getString(R.string.config_bodyFontFamily));
        targets.add(mContext.getString(R.string.config_bodyFontFamilyMedium));
        targets.add(mContext.getString(R.string.config_headlineFontFamily));
        targets.add(mContext.getString(R.string.config_headlineFontFamilyMedium));
        return targets.toArray(new String[0]);
    }

    private FontConfig customFontConfig(FontConfig base, String id) throws IOException {
        return CustomFontConfigBuilder.build(base, id, mCustomFontStore, builtInFontFamilies(),
                customFontTargets());
    }

    private void enforceCustomFontAccess() {
        mContext.enforceCallingPermission(Manifest.permission.UPDATE_FONTS,
                "UPDATE_FONTS permission required");
        if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
            throw new SecurityException("System fonts are managed by the system user");
        }
        mServiceStarted.join();
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public CustomFontConfig getCustomFontConfig() {
        enforceCustomFontAccess();
        synchronized (mUpdatableFontDirLock) {
            return mCustomFontStore.snapshot(CustomFontConfigBuilder.builtIns(
                    getBaseFontConfig(), builtInFontFamilies()));
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public CustomFontRuntimeConfig getCustomFontRuntimeConfig() {
        enforceCustomFontAccess();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUpdatableFontDirLock) {
                int baseVersion = mUpdatableFontDir == null ? 0
                        : mUpdatableFontDir.getConfigVersion();
                if (mRuntimeConfig == null) {
                    FontConfig base = getBaseFontConfig();
                    FontConfig config = getSystemFontConfig();
                    mRuntimeConfig = new CustomFontRuntimeConfig(mRuntimeGeneration,
                            mCustomFontStore.activeId(), config,
                            CustomFontConfigBuilder.targetFamilies(base, customFontTargets())
                                    .toArray(new String[0]));
                } else if (mRuntimeConfig.getFontConfig().getConfigVersion() != baseVersion) {
                    // A trusted provider updated the fallback set. Recompose the same live
                    // selection against its new files instead of serving a stale FontConfig.
                    FontConfig base = getBaseFontConfig();
                    String id = mRuntimeConfig.getSelectedId();
                    CustomFontRuntimeConfig next = new CustomFontRuntimeConfig(
                            mRuntimeGeneration + 1, id, customFontConfig(base, id),
                            CustomFontConfigBuilder.targetFamilies(base, customFontTargets())
                                    .toArray(new String[0]));
                    mRuntimeConfig = next;
                    mRuntimeGeneration = next.getGeneration();
                }
                return mRuntimeConfig;
            }
        } catch (IOException e) {
            throw customFontError(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public CustomFontInfo importCustomFont(ParcelFileDescriptor font) {
        // Close the Binder-owned descriptor even on permission or argument failure.
        try (ParcelFileDescriptor owned = font) {
            enforceCustomFontAccess();
            Objects.requireNonNull(owned);
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mUpdatableFontDirLock) {
                    return mCustomFontStore.importFont(owned);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } catch (IOException e) {
            throw customFontError(e);
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public void selectCustomFont(String id) {
        enforceCustomFontAccess();
        Objects.requireNonNull(id);
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUpdatableFontDirLock) {
                if (id.equals(mCustomFontStore.selectedId())) return;
                FontConfig base = getBaseFontConfig();
                FontConfig config = customFontConfig(base, id);
                // Validate before persistence/publication. Never change the boot SharedMemory map.
                SharedMemory candidate = serializeFontMap(config);
                if (candidate == null) throw new IOException("Cannot serialize selected font");
                candidate.close();
                CustomFontRuntimeConfig next = new CustomFontRuntimeConfig(
                        mRuntimeGeneration + 1, id, config,
                        CustomFontConfigBuilder.targetFamilies(base, customFontTargets())
                                .toArray(new String[0]));
                mCustomFontStore.select(id);
                mRuntimeConfig = next;
                mRuntimeGeneration = next.getGeneration();
            }
            // Never dispatch callbacks while holding the font lock. A delayed/out-of-order
            // notification is harmless: clients always fetch the latest coherent snapshot.
            notifyCustomFontChanged();
        } catch (IOException e) {
            throw customFontError(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void notifyCustomFontChanged() {
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(new Intent(FontManager.ACTION_CUSTOM_FONT_CHANGED)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), UserHandle.SYSTEM);
        } catch (RuntimeException e) {
            // The selection/provider update is already committed. A foreground refresh can
            // recover a lost notification; do not report the durable operation as rolled back.
            Slog.w(TAG, "Unable to notify UI font clients", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public void deleteCustomFont(String id) {
        enforceCustomFontAccess();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUpdatableFontDirLock) {
                mCustomFontStore.delete(id);
            }
        } catch (IOException e) {
            throw customFontError(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public ParcelFileDescriptor openCustomFont(String id) {
        enforceCustomFontAccess();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUpdatableFontDirLock) {
                return mCustomFontStore.open(id);
            }
        } catch (IOException e) {
            throw customFontError(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static ServiceSpecificException customFontError(IOException exception) {
        Slog.w(TAG, "Custom font operation failed", exception);
        return new ServiceSpecificException(FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                exception.getMessage());
    }

    @Override
    @RequiresPermission(Manifest.permission.UPDATE_FONTS)
    public FontConfig getCustomFontPreviewConfig(String id) {
        enforceCustomFontAccess();
        Objects.requireNonNull(id);
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUpdatableFontDirLock) {
                return customFontConfig(getBaseFontConfig(), id);
            }
        } catch (IOException e) {
            throw customFontError(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Makes new serialized font map data and updates mSerializedFontMap.
     */
    private void updateSerializedFontMap() {
        SharedMemory serializedFontMap = serializeFontMap(getSystemFontConfig());
        if (serializedFontMap == null && !mCustomFontStore.activeId().isEmpty()) {
            mCustomFontStore.recover();
            serializedFontMap = serializeFontMap(getBaseFontConfig());
        }
        if (serializedFontMap == null) {
            // Fallback to the preloaded config.
            serializedFontMap = serializeSystemServerFontMap();
        }
        setSerializedFontMap(serializedFontMap);
    }

    @Nullable
    private static SharedMemory serializeFontMap(FontConfig fontConfig) {
        final ArrayMap<String, ByteBuffer> bufferCache = new ArrayMap<>();
        try {
            final Map<String, FontFamily[]> fallback =
                    SystemFonts.buildSystemFallback(fontConfig, bufferCache);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);
            return Typeface.serializeFontMap(typefaceMap);
        } catch (IOException | ErrnoException | RuntimeException e) {
            Slog.w(TAG, "Failed to serialize updatable font map. "
                    + "Retrying with system image fonts.", e);
            return null;
        } finally {
            // Unmap buffers promptly, as we map a lot of files and may hit mmap limit before
            // GC collects ByteBuffers and unmaps them.
            for (ByteBuffer buffer : bufferCache.values()) {
                if (buffer instanceof DirectByteBuffer) {
                    NioUtils.freeDirectBuffer(buffer);
                }
            }
        }
    }

    @Nullable
    private static SharedMemory serializeSystemServerFontMap() {
        try {
            return Typeface.serializeFontMap(Typeface.getSystemFontMap());
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
            return null;
        }
    }

    private void setSerializedFontMap(SharedMemory serializedFontMap) {
        SharedMemory oldFontMap = null;
        synchronized (mSerializedFontMapLock) {
            oldFontMap = mSerializedFontMap;
            mSerializedFontMap = serializedFontMap;
        }
        if (oldFontMap != null) {
            oldFontMap.close();
        }
    }
}
