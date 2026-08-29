/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.credentials;

import static com.android.server.bitmapoffload.BitmapOffload.BITMAP_SOURCE_CREDENTIALS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.selection.AuthenticationEntry;
import android.credentials.selection.CreateCredentialProviderData;
import android.credentials.selection.DisabledProviderData;
import android.credentials.selection.Entry;
import android.credentials.selection.GetCredentialProviderData;
import android.credentials.selection.IntentCreationResult;
import android.credentials.selection.IntentFactory;
import android.credentials.selection.ProviderData;
import android.credentials.selection.RequestInfo;
import android.credentials.selection.UserSelectionDialogResult;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.service.credentials.CredentialProviderInfoFactory;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.bitmapoffload.BitmapOffloadInternal;
import com.android.server.credentials.metrics.RequestSessionMetric;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Initiates the Credential Manager UI and receives results. */
public class CredentialManagerUi {

    private static final String TAG = "CredentialManagerUi";

    // Activity launches are one-way Binder transactions and share a limited asynchronous buffer.
    // Leave ample room for the rest of LaunchActivityItem and for other outstanding transactions.
    private static final long MAX_SELECTOR_BITMAP_BYTES = 256 * 1024;
    private static final long OFFLOADED_ICON_TTL_MILLIS = 10 * 60 * 1000;

    private static final Map<Uri, Integer> sAllowedIconUids = new ConcurrentHashMap<>();
    private static final BitmapOffloadInternal.PermissionHandler sIconPermissionHandler =
            (uri, callingUid, owningUid) -> {
                final Integer allowedUid = sAllowedIconUids.get(uri);
                return allowedUid != null && allowedUid == callingUid;
            };

    private static final String SESSION_ID_TRACK_ONE =
            "com.android.server.credentials.CredentialManagerUi.SESSION_ID_TRACK_ONE";
    private static final String SESSION_ID_TRACK_TWO =
            "com.android.server.credentials.CredentialManagerUi.SESSION_ID_TRACK_TWO";

    @NonNull
    private final CredentialManagerUiCallback mCallbacks;
    @NonNull
    private final Context mContext;

    private final int mUserId;

    @NonNull
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private final BitmapOffloadInternal mBitmapOffloader;
    private final Set<Uri> mOffloadedIconUris = new HashSet<>();
    private final Object mOffloadedIconCleanupToken = new Object();

    private UiStatus mStatus;

    private final Set<ComponentName> mEnabledProviders;

    enum UiStatus {
        IN_PROGRESS,
        USER_INTERACTION,
        NOT_STARTED, TERMINATED
    }

    @NonNull
    private final ResultReceiver mResultReceiver = new ResultReceiver(
            mHandler) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            handleUiResult(resultCode, resultData);
        }
    };

    private void handleUiResult(int resultCode, Bundle resultData) {
        cleanupOffloadedIcons();

        switch (resultCode) {
            case UserSelectionDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION:
                mStatus = UiStatus.IN_PROGRESS;
                UserSelectionDialogResult selection = UserSelectionDialogResult
                        .fromResultData(resultData);
                if (selection != null) {
                    mCallbacks.onUiSelection(selection);
                }
                break;
            case UserSelectionDialogResult.RESULT_CODE_DIALOG_USER_CANCELED:

                mStatus = UiStatus.TERMINATED;
                mCallbacks.onUiCancellation(/* isUserCancellation= */ true);
                break;
            case UserSelectionDialogResult.RESULT_CODE_CANCELED_AND_LAUNCHED_SETTINGS:

                mStatus = UiStatus.TERMINATED;
                mCallbacks.onUiCancellation(/* isUserCancellation= */ false);
                break;
            case UserSelectionDialogResult.RESULT_CODE_DATA_PARSING_FAILURE:
                mStatus = UiStatus.TERMINATED;
                mCallbacks.onUiSelectorInvocationFailure();
                break;
            default:
                mStatus = UiStatus.IN_PROGRESS;
                mCallbacks.onUiSelectorInvocationFailure();
                break;
        }
    }

    /** Creates intent that is ot be invoked to cancel an in-progress UI session. */
    public Intent createCancelIntent(IBinder requestId, String packageName) {
        return IntentFactory.createCancelUiIntent(mContext, requestId,
                /*shouldShowCancellationUi=*/ true, packageName, mUserId);
    }

    /**
     * Interface to be implemented by any class that wishes to get callbacks from the UI.
     */
    public interface CredentialManagerUiCallback {
        /** Called when the user makes a selection. */
        void onUiSelection(UserSelectionDialogResult selection);

        /** Called when the UI is canceled without a successful provider result. */
        void onUiCancellation(boolean isUserCancellation);

        /** Called when the selector UI fails to come up (mostly due to parsing issue today). */
        void onUiSelectorInvocationFailure();
    }

    public CredentialManagerUi(Context context, int userId,
            CredentialManagerUiCallback callbacks, Set<ComponentName> enabledProviders) {
        mContext = context;
        mUserId = userId;
        mCallbacks = callbacks;
        mEnabledProviders = enabledProviders;
        mStatus = UiStatus.IN_PROGRESS;
        mBitmapOffloader = LocalServices.getService(BitmapOffloadInternal.class);
        if (mBitmapOffloader != null) {
            mBitmapOffloader.registerPermissionHandler(
                    BITMAP_SOURCE_CREDENTIALS, sIconPermissionHandler);
        }
    }

    /** Set status for credential manager UI */
    public void setStatus(UiStatus status) {
        mStatus = status;
    }

    /** Returns status for credential manager UI */
    public UiStatus getStatus() {
        return mStatus;
    }

    /** Releases any temporary icon data owned by this UI session. */
    public void destroy() {
        cleanupOffloadedIcons();
    }

    /**
     * Creates a {@link PendingIntent} to be used to invoke the credential manager selector UI,
     * by the calling app process. The bottom-sheet navigates to the default page when the intent
     * is invoked.
     *
     * @param requestInfo      the information about the request
     * @param providerDataList the list of provider data from remote providers
     */
    public PendingIntent createPendingIntent(
            RequestInfo requestInfo, ArrayList<ProviderData> providerDataList,
            RequestSessionMetric requestSessionMetric) {
        List<CredentialProviderInfo> allProviders =
                CredentialProviderInfoFactory.getCredentialProviderServices(
                        mContext,
                        mUserId,
                        CredentialManager.PROVIDER_FILTER_USER_PROVIDERS_ONLY,
                        mEnabledProviders,
                        // Don't need primary providers here.
                        new HashSet<ComponentName>());

        List<DisabledProviderData> disabledProviderDataList = allProviders.stream()
                .filter(provider -> !provider.isEnabled())
                .map(disabledProvider -> new DisabledProviderData(
                        disabledProvider.getComponentName().flattenToString())).toList();

        IntentCreationResult intentCreationResult = IntentFactory
                .createCredentialSelectorIntentForCredMan(mContext, requestInfo, providerDataList,
                        new ArrayList<>(disabledProviderDataList), mResultReceiver, mUserId);
        requestSessionMetric.collectUiConfigurationResults(
                mContext, intentCreationResult, mUserId);
        Intent intent = intentCreationResult.getIntent();
        final int selectorUid = getSelectorUid(intent);
        if (selectorUid != -1) {
            offloadProviderIconsIfNecessary(providerDataList, selectorUid);
        }
        scaleDownProviderIconsIfNecessary(providerDataList);
        intent.putParcelableArrayListExtra(
                ProviderData.EXTRA_ENABLED_PROVIDER_DATA_LIST, providerDataList);
        intent.setAction(UUID.randomUUID().toString());
        intent.putExtra(SESSION_ID_TRACK_ONE,
                requestSessionMetric.getInitialPhaseMetric().getSessionIdCaller());
        intent.putExtra(SESSION_ID_TRACK_TWO, requestSessionMetric.getSessionIdTrackTwo());
        //TODO: Create unique pending intent using request code and cancel any pre-existing pending
        // intents
        return PendingIntent.getActivityAsUser(
                mContext, /*requestCode=*/0, intent,
                PendingIntent.FLAG_MUTABLE, /*options=*/null,
                UserHandle.of(mUserId));
    }

    private int getSelectorUid(@NonNull Intent intent) {
        final ComponentName componentName = intent.getComponent();
        if (componentName == null) {
            Slog.w(TAG, "Cannot offload credential icons without an explicit selector component");
            return -1;
        }
        try {
            return mContext.getPackageManager().getPackageUidAsUser(
                    componentName.getPackageName(), mUserId);
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            Slog.w(TAG, "Cannot resolve credential selector UID for " + componentName, e);
            return -1;
        }
    }

    private void offloadProviderIconsIfNecessary(
            @NonNull ArrayList<ProviderData> providerDataList, int selectorUid) {
        if (mBitmapOffloader == null) {
            return;
        }

        final Set<Icon> bitmapIcons = Collections.newSetFromMap(new IdentityHashMap<>());
        final long totalBitmapBytes = collectProviderIcons(providerDataList, bitmapIcons);
        if (totalBitmapBytes <= MAX_SELECTOR_BITMAP_BYTES || bitmapIcons.isEmpty()) {
            return;
        }

        final Map<Icon, Icon> replacements = new IdentityHashMap<>();
        // Different Icon wrappers can share the same pixel allocation. Compress it only once.
        final Map<Bitmap, Uri> offloadedBitmaps = new IdentityHashMap<>();
        final Set<Uri> batchUris = new HashSet<>();
        for (Icon icon : bitmapIcons) {
            try {
                final Bitmap bitmap = icon.getBitmap();
                Uri uri = offloadedBitmaps.get(bitmap);
                if (uri == null) {
                    uri = mBitmapOffloader.offloadBitmap(BITMAP_SOURCE_CREDENTIALS, bitmap);
                    if (uri != null) {
                        offloadedBitmaps.put(bitmap, uri);
                        batchUris.add(uri);
                        sAllowedIconUids.put(uri, selectorUid);
                    }
                }
                if (uri == null) {
                    continue;
                }
                final Icon uriIcon = icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP
                        ? Icon.createWithAdaptiveBitmapContentUri(uri)
                        : Icon.createWithContentUri(uri);
                uriIcon.setTintList(icon.getTintList());
                uriIcon.setTintBlendMode(icon.getTintBlendMode());
                replacements.put(icon, uriIcon);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Unable to offload a credential selector icon", e);
            }
        }

        if (replacements.isEmpty()) {
            deleteOffloadedIcons(batchUris);
            return;
        }

        try {
            replaceProviderIcons(providerDataList, replacements);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to replace credential selector icons with content URIs", e);
            deleteOffloadedIcons(batchUris);
            return;
        }

        synchronized (mOffloadedIconUris) {
            mOffloadedIconUris.addAll(batchUris);
        }
        mHandler.postDelayed(
                () -> cleanupOffloadedIcons(batchUris), mOffloadedIconCleanupToken,
                OFFLOADED_ICON_TTL_MILLIS);
        Slog.i(TAG, "Offloaded credential selector bitmap icons count=" + replacements.size()
                + " originalBytes=" + totalBitmapBytes);
    }

    private static void replaceProviderIcons(
            @NonNull ArrayList<ProviderData> providerDataList,
            @NonNull Map<Icon, Icon> replacements) {
        final ArrayList<ProviderData> replacedProviderDataList =
                new ArrayList<>(providerDataList);
        for (int i = 0; i < providerDataList.size(); i++) {
            final ProviderData providerData = providerDataList.get(i);
            if (providerData instanceof GetCredentialProviderData getData) {
                replacedProviderDataList.set(i, new GetCredentialProviderData(
                        getData.getProviderFlattenedComponentName(),
                        replaceEntryIcons(getData.getCredentialEntries(), replacements),
                        replaceEntryIcons(getData.getActionChips(), replacements),
                        replaceAuthenticationEntryIcons(
                                getData.getAuthenticationEntries(), replacements),
                        replaceNullableEntryIcon(getData.getRemoteEntry(), replacements)));
            } else if (providerData instanceof CreateCredentialProviderData createData) {
                replacedProviderDataList.set(i, new CreateCredentialProviderData(
                        createData.getProviderFlattenedComponentName(),
                        replaceEntryIcons(createData.getSaveEntries(), replacements),
                        replaceNullableEntryIcon(createData.getRemoteEntry(), replacements)));
            }
        }
        providerDataList.clear();
        providerDataList.addAll(replacedProviderDataList);
    }

    @NonNull
    private static List<Entry> replaceEntryIcons(
            @NonNull List<Entry> entries, @NonNull Map<Icon, Icon> replacements) {
        final List<Entry> replacedEntries = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            replacedEntries.add(entry.copyWithSlice(
                    replaceSliceIcons(entry.getSlice(), replacements)));
        }
        return replacedEntries;
    }

    @Nullable
    private static Entry replaceNullableEntryIcon(
            @Nullable Entry entry, @NonNull Map<Icon, Icon> replacements) {
        if (entry == null) {
            return null;
        }
        return entry.copyWithSlice(replaceSliceIcons(entry.getSlice(), replacements));
    }

    @NonNull
    private static List<AuthenticationEntry> replaceAuthenticationEntryIcons(
            @NonNull List<AuthenticationEntry> entries,
            @NonNull Map<Icon, Icon> replacements) {
        final List<AuthenticationEntry> replacedEntries = new ArrayList<>(entries.size());
        for (AuthenticationEntry entry : entries) {
            replacedEntries.add(entry.copyWithSlice(
                    replaceSliceIcons(entry.getSlice(), replacements)));
        }
        return replacedEntries;
    }

    @NonNull
    private static Slice replaceSliceIcons(
            @NonNull Slice slice, @NonNull Map<Icon, Icon> replacements) {
        final Slice.Builder builder = new Slice.Builder(slice.getUri(), slice.getSpec())
                .addHints(slice.getHints());
        for (SliceItem item : slice.getItems()) {
            SliceItem replacementItem = item;
            switch (item.getFormat()) {
                case SliceItem.FORMAT_IMAGE:
                    final Icon replacementIcon = replacements.get(item.getIcon());
                    if (replacementIcon != null) {
                        replacementItem = new SliceItem(replacementIcon, item.getFormat(),
                                item.getSubType(), item.getHints());
                    }
                    break;
                case SliceItem.FORMAT_SLICE:
                    replacementItem = new SliceItem(
                            replaceSliceIcons(item.getSlice(), replacements), item.getFormat(),
                            item.getSubType(), item.getHints());
                    break;
                case SliceItem.FORMAT_ACTION:
                    replacementItem = new SliceItem(item.getAction(),
                            replaceSliceIcons(item.getSlice(), replacements), item.getFormat(),
                            item.getSubType(), item.getHints().toArray(new String[0]));
                    break;
                default:
                    break;
            }
            builder.addItem(replacementItem);
        }
        return builder.build();
    }

    private void cleanupOffloadedIcons() {
        mHandler.removeCallbacksAndMessages(mOffloadedIconCleanupToken);
        final Set<Uri> uris;
        synchronized (mOffloadedIconUris) {
            uris = new HashSet<>(mOffloadedIconUris);
        }
        cleanupOffloadedIcons(uris);
    }

    private void cleanupOffloadedIcons(@NonNull Collection<Uri> uris) {
        final Set<Uri> ownedUris = new HashSet<>();
        synchronized (mOffloadedIconUris) {
            for (Uri uri : uris) {
                if (mOffloadedIconUris.remove(uri)) {
                    ownedUris.add(uri);
                }
            }
        }
        deleteOffloadedIcons(ownedUris);
    }

    private void deleteOffloadedIcons(@NonNull Collection<Uri> uris) {
        for (Uri uri : uris) {
            sAllowedIconUids.remove(uri);
            if (mBitmapOffloader != null) {
                mBitmapOffloader.deleteBitmap(uri);
            }
        }
    }

    private static void scaleDownProviderIconsIfNecessary(
            @NonNull List<ProviderData> providerDataList) {
        final Set<Icon> bitmapIcons =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final long totalBitmapBytes = collectProviderIcons(providerDataList, bitmapIcons);

        if (totalBitmapBytes <= MAX_SELECTOR_BITMAP_BYTES || bitmapIcons.isEmpty()) {
            return;
        }

        final double scale = Math.sqrt(
                (double) MAX_SELECTOR_BITMAP_BYTES / totalBitmapBytes);
        int scaledIconCount = 0;
        for (Icon icon : bitmapIcons) {
            final Bitmap bitmap = icon.getBitmap();
            final int maxWidth = Math.max(1, (int) Math.floor(bitmap.getWidth() * scale));
            final int maxHeight = Math.max(1, (int) Math.floor(bitmap.getHeight() * scale));
            if (maxWidth < bitmap.getWidth() || maxHeight < bitmap.getHeight()) {
                icon.scaleDownIfNecessary(maxWidth, maxHeight);
                scaledIconCount++;
            }
        }

        Slog.w(TAG, "Scaled credential selector bitmap icons count=" + scaledIconCount
                + " originalBytes=" + totalBitmapBytes
                + " budgetBytes=" + MAX_SELECTOR_BITMAP_BYTES);
    }

    private static long collectProviderIcons(
            @NonNull List<ProviderData> providerDataList, @NonNull Set<Icon> bitmapIcons) {
        long totalBitmapBytes = 0;
        for (ProviderData providerData : providerDataList) {
            if (providerData instanceof GetCredentialProviderData getData) {
                totalBitmapBytes += collectEntryIcons(getData.getCredentialEntries(), bitmapIcons);
                totalBitmapBytes += collectEntryIcons(getData.getActionChips(), bitmapIcons);
                for (AuthenticationEntry entry : getData.getAuthenticationEntries()) {
                    totalBitmapBytes += collectSliceIcons(entry.getSlice(), bitmapIcons);
                }
                if (getData.getRemoteEntry() != null) {
                    totalBitmapBytes += collectSliceIcons(
                            getData.getRemoteEntry().getSlice(), bitmapIcons);
                }
            } else if (providerData instanceof CreateCredentialProviderData createData) {
                totalBitmapBytes += collectEntryIcons(createData.getSaveEntries(), bitmapIcons);
                if (createData.getRemoteEntry() != null) {
                    totalBitmapBytes += collectSliceIcons(
                            createData.getRemoteEntry().getSlice(), bitmapIcons);
                }
            }
        }
        return totalBitmapBytes;
    }

    private static long collectEntryIcons(
            @NonNull List<Entry> entries, @NonNull Set<Icon> bitmapIcons) {
        long totalBitmapBytes = 0;
        for (Entry entry : entries) {
            totalBitmapBytes += collectSliceIcons(entry.getSlice(), bitmapIcons);
        }
        return totalBitmapBytes;
    }

    private static long collectSliceIcons(
            @NonNull Slice slice, @NonNull Set<Icon> bitmapIcons) {
        long totalBitmapBytes = 0;
        for (SliceItem item : slice.getItems()) {
            switch (item.getFormat()) {
                case SliceItem.FORMAT_IMAGE:
                    final Icon icon = item.getIcon();
                    if ((icon.getType() == Icon.TYPE_BITMAP
                            || icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP)) {
                        final Bitmap bitmap = icon.getBitmap();
                        bitmapIcons.add(icon);
                        // Count every Slice occurrence because each one is written to the parcel.
                        totalBitmapBytes += bitmap.getAllocationByteCount();
                    }
                    break;
                case SliceItem.FORMAT_ACTION:
                case SliceItem.FORMAT_SLICE:
                    totalBitmapBytes += collectSliceIcons(item.getSlice(), bitmapIcons);
                    break;
                default:
                    break;
            }
        }
        return totalBitmapBytes;
    }

    /**
     * Creates an {@link Intent} to be used to invoke the credential manager selector UI,
     * by the calling app process. This intent is invoked from the Autofill flow, when the user
     * requests to bring up the 'All Options' page of the credential bottom-sheet. When the user
     * clicks on the pinned entry, the intent will bring up the 'All Options' page of the
     * bottom-sheet. The provider data list is processed by the credential autofill service for
     * each autofill id and passed in as extras in the pending intent set as authentication
     * of the pinned entry.
     *
     * @param requestInfo          the information about the request
     * @param requestSessionMetric the metric object for logging
     */
    public Intent createIntentForAutofill(RequestInfo requestInfo,
            RequestSessionMetric requestSessionMetric) {
        IntentCreationResult intentCreationResult = IntentFactory
                .createCredentialSelectorIntentForAutofill(mContext, requestInfo, new ArrayList<>(),
                        mResultReceiver, mUserId);
        requestSessionMetric.collectUiConfigurationResults(
                mContext, intentCreationResult, mUserId);
        return intentCreationResult.getIntent();
    }
}
