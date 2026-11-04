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

package com.android.internal.widget;

import static android.graphics.drawable.AdaptiveIconDrawable.MASK_SIZE;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.util.Log;
import android.util.PathParser;

import com.android.internal.R;

/**
 * Provides the adaptive icon mask, so notification icon backgrounds and badges can follow the
 * system icon shape instead of always being circles.
 */
public final class NotificationIconShape {
    private static final String TAG = "NotificationIconShape";

    private static final Object sLock = new Object();
    private static String sMaskData;
    private static Path sMask;

    private NotificationIconShape() {}

    /**
     * Returns a copy of the icon mask in a {@link android.graphics.drawable.AdaptiveIconDrawable
     * #MASK_SIZE} square, or null if the mask cannot be parsed.
     */
    @Nullable
    public static Path getIconMask(Resources res) {
        final String maskData = res.getString(R.string.config_icon_mask);
        synchronized (sLock) {
            if (!maskData.equals(sMaskData)) {
                sMaskData = maskData;
                try {
                    sMask = PathParser.createPathFromPathData(maskData);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Invalid icon mask: " + maskData, e);
                    sMask = null;
                }
            }
            return sMask == null ? null : new Path(sMask);
        }
    }

    /** Returns the icon mask scaled into the given bounds, or null if it cannot be parsed. */
    @Nullable
    public static Path getIconMask(Resources res, float left, float top, float right,
            float bottom) {
        final Path mask = getIconMask(res);
        if (mask == null) {
            return null;
        }
        final Matrix matrix = new Matrix();
        matrix.setScale((right - left) / MASK_SIZE, (bottom - top) / MASK_SIZE);
        matrix.postTranslate(left, top);
        mask.transform(matrix);
        return mask;
    }
}
