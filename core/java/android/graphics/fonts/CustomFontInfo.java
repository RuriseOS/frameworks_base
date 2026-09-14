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

import java.util.Objects;

/** An immutable entry in the device's system font library. @hide */
public final class CustomFontInfo implements Parcelable {
    /** Empty selection means the original ROM font configuration. */
    public static final String DEFAULT_ID = "";
    public static final String BUILTIN_PREFIX = "builtin:";
    /** Maximum size accepted by the service and the import UI. */
    public static final long MAX_FILE_SIZE = 32L * 1024 * 1024;

    private final String mId;
    private final String mName;

    public CustomFontInfo(@NonNull String id, @NonNull String name) {
        mId = Objects.requireNonNull(id);
        mName = Objects.requireNonNull(name);
    }

    @NonNull public String getId() { return mId; }
    @NonNull public String getName() { return mName; }
    public boolean isBuiltIn() { return mId.startsWith(BUILTIN_PREFIX); }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mId);
        dest.writeString8(mName);
    }

    public static final @NonNull Creator<CustomFontInfo> CREATOR =
            new Creator<CustomFontInfo>() {
                @Override public CustomFontInfo createFromParcel(Parcel in) {
                    return new CustomFontInfo(in.readString8(), in.readString8());
                }
                @Override public CustomFontInfo[] newArray(int size) {
                    return new CustomFontInfo[size];
                }
            };
}
