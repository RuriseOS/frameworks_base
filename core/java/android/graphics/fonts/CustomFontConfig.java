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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Snapshot of the library, boot-active selection and next-boot selection. @hide */
public final class CustomFontConfig implements Parcelable {
    private final List<CustomFontInfo> mFonts;
    private final String mSelectedId;
    private final String mActiveId;
    private final boolean mRecovered;
    private final boolean mSafeMode;

    public CustomFontConfig(@NonNull List<CustomFontInfo> fonts, @NonNull String selectedId,
            @NonNull String activeId, boolean recovered, boolean safeMode) {
        mFonts = Collections.unmodifiableList(new ArrayList<>(fonts));
        mSelectedId = Objects.requireNonNull(selectedId);
        mActiveId = Objects.requireNonNull(activeId);
        mRecovered = recovered;
        mSafeMode = safeMode;
    }

    @NonNull public List<CustomFontInfo> getFonts() { return mFonts; }
    @NonNull public String getSelectedId() { return mSelectedId; }
    @NonNull public String getActiveId() { return mActiveId; }
    public boolean wasRecovered() { return mRecovered; }
    public boolean isSafeMode() { return mSafeMode; }
    public boolean isRebootRequired() { return !mSelectedId.equals(mActiveId); }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mFonts);
        dest.writeString8(mSelectedId);
        dest.writeString8(mActiveId);
        dest.writeBoolean(mRecovered);
        dest.writeBoolean(mSafeMode);
    }

    public static final @NonNull Creator<CustomFontConfig> CREATOR =
            new Creator<CustomFontConfig>() {
                @Override public CustomFontConfig createFromParcel(Parcel in) {
                    return new CustomFontConfig(in.createTypedArrayList(CustomFontInfo.CREATOR),
                            in.readString8(), in.readString8(), in.readBoolean(), in.readBoolean());
                }
                @Override public CustomFontConfig[] newArray(int size) {
                    return new CustomFontConfig[size];
                }
            };
}
