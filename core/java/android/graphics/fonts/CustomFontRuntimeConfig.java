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
import android.text.FontConfig;

import java.util.Objects;

/** A coherent selection snapshot for explicitly opted-in UI processes. @hide */
public final class CustomFontRuntimeConfig implements Parcelable {
    private final long mGeneration;
    private final String mSelectedId;
    private final FontConfig mFontConfig;
    private final String[] mFamilies;

    public CustomFontRuntimeConfig(long generation, @NonNull String selectedId,
            @NonNull FontConfig fontConfig, @NonNull String[] families) {
        mGeneration = generation;
        mSelectedId = Objects.requireNonNull(selectedId);
        mFontConfig = Objects.requireNonNull(fontConfig);
        mFamilies = families.clone();
    }

    public long getGeneration() { return mGeneration; }
    public @NonNull String getSelectedId() { return mSelectedId; }
    public @NonNull FontConfig getFontConfig() { return mFontConfig; }
    public @NonNull String[] getFamilies() { return mFamilies.clone(); }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mGeneration);
        dest.writeString8(mSelectedId);
        dest.writeTypedObject(mFontConfig, flags);
        dest.writeStringArray(mFamilies);
    }

    public static final @NonNull Creator<CustomFontRuntimeConfig> CREATOR = new Creator<>() {
        @Override public CustomFontRuntimeConfig createFromParcel(Parcel in) {
            return new CustomFontRuntimeConfig(in.readLong(), in.readString8(),
                    in.readTypedObject(FontConfig.CREATOR), in.createStringArray());
        }
        @Override public CustomFontRuntimeConfig[] newArray(int size) {
            return new CustomFontRuntimeConfig[size];
        }
    };
}
