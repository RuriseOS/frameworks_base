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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Parcel;
import android.text.FontConfig;
import android.util.ArrayMap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemFontSnapshotTest {
    private SystemFontSnapshot snapshot(long generation, Typeface replacement) {
        return new SystemFontSnapshot(generation, "test", Map.of("sans-serif", replacement),
                new String[] {"sans-serif"});
    }

    @Test
    public void acceptsSystemFontArrayMapAndCopiesBeforeSourceMutation() {
        // Match the map implementation returned by SystemFonts.buildSystemTypefaces().
        ArrayMap<String, Typeface> fonts = new ArrayMap<>();
        fonts.put("sans-serif", Typeface.SERIF);
        fonts.put("monospace", Typeface.MONOSPACE);
        SystemFontSnapshot snapshot = new SystemFontSnapshot(1, "test", fonts,
                new String[] {"sans-serif"});
        fonts.clear();
        fonts.put("sans-serif", Typeface.MONOSPACE);

        Typeface resolved = snapshot.resolve(Typeface.SANS_SERIF);
        assertNotSame(Typeface.SANS_SERIF, resolved);
        assertEquals("serif", resolved.getSystemFontFamilyName());
        assertSame(Typeface.MONOSPACE, snapshot.resolve(Typeface.MONOSPACE));
    }

    @Test
    public void changesOnlySystemFamiliesAndRetainsOriginalRequest() {
        Typeface original = Typeface.create(Typeface.SANS_SERIF, 600, true);
        long pointer = original.getNativeInstance();
        SystemFontSnapshot snapshot = snapshot(1, Typeface.SERIF);
        Typeface resolved = snapshot.resolve(original);
        assertNotSame(original, resolved);
        assertEquals(600, resolved.getWeight());
        assertTrue(resolved.isItalic());
        assertEquals(pointer, original.getNativeInstance());
        assertSame(resolved, snapshot.resolve(original));
        assertSame(Typeface.MONOSPACE, snapshot.resolve(Typeface.MONOSPACE));
    }

    @Test
    public void appOwnedFaceWithSystemFallbackDoesNotFollowSelection() {
        Typeface owned = Typeface.createForFontSnapshot(Typeface.SANS_SERIF, 400, false);
        // A family-name string alone must not opt a privately built face into replacement.
        assertFalse(owned.isSystemFontForRuntime());
        assertSame(owned, snapshot(1, Typeface.SERIF).resolve(owned));
    }

    @Test
    public void previewMapWithSystemFamilyNameIsNotTreatedAsBootMap() {
        Font font = Font.getAvailableFonts().iterator().next();
        FontFamily family = new FontFamily.Builder(font).build();
        Map<String, Typeface> preview = new HashMap<>();
        Typeface.initSystemDefaultTypefaces(Map.of("sans-serif", new FontFamily[] {family}),
                List.of(), preview);
        Typeface face = Typeface.create(preview.get("sans-serif"), 600, false);
        assertEquals("sans-serif", face.getSystemFontFamilyName());
        assertFalse(face.isSystemFontForRuntime());
        assertSame(face, snapshot(1, Typeface.SERIF).resolve(face));
    }

    @Test
    public void styleCacheEvictsOldEntriesWithinOneSnapshot() {
        SystemFontSnapshot snapshot = snapshot(1, Typeface.SERIF);
        Typeface firstRequest = Typeface.create(Typeface.SANS_SERIF, 1, false);
        Typeface first = snapshot.resolve(firstRequest);
        for (int weight = 2; weight <= 130; weight++) {
            snapshot.resolve(Typeface.create(Typeface.SANS_SERIF, weight, false));
        }
        assertNotSame(first, snapshot.resolve(firstRequest));
    }

    @Test
    public void snapshotStylesAndVariationsAreNotPinnedInGlobalCaches() {
        Typeface face = snapshot(1, Typeface.SERIF).resolve(Typeface.SANS_SERIF);
        Typeface first = Typeface.create(face, 600, false);
        Typeface second = Typeface.create(face, 600, false);
        assertNotSame(first, second);
        assertNotSame(Typeface.create(face, Typeface.BOLD), Typeface.create(face, Typeface.BOLD));
        assertNotSame(Typeface.createFromTypefaceWithVariation(face, List.of()),
                Typeface.createFromTypefaceWithVariation(face, List.of()));
    }

    @Test
    public void replacingSnapshotDoesNotMutateOldPaintOrBootTypeface() {
        Typeface original = Typeface.SANS_SERIF;
        long bootPointer = original.getNativeInstance();
        SystemFontSnapshot first = snapshot(1, Typeface.SERIF);
        Paint paint = new Paint();
        paint.setTypeface(first.resolve(original));
        long oldPointer = paint.getTypeface().getNativeInstance();
        float oldWidth = paint.measureText("Font snapshot 123");
        SystemFontSnapshot restored = snapshot(2, Typeface.SANS_SERIF);
        Typeface next = restored.resolve(original);
        assertNotSame(paint.getTypeface(), next);
        assertEquals(oldPointer, paint.getTypeface().getNativeInstance());
        assertEquals(oldWidth, paint.measureText("Font snapshot 123"), 0f);
        assertEquals(bootPointer, original.getNativeInstance());
        paint.setTypeface(next);
        assertTrue(paint.measureText("Font snapshot 123") > 0);
    }

    @Test
    public void runtimeParcelKeepsGenerationSelectionAndDefensiveFamilyCopy() {
        FontConfig fonts = new FontConfig(List.of(), List.of(), List.of(), List.of(), 0, 7);
        String[] families = {"sans-serif"};
        CustomFontRuntimeConfig config = new CustomFontRuntimeConfig(42, "chosen", fonts, families);
        families[0] = "mutated";
        config.getFamilies()[0] = "also-mutated";
        Parcel parcel = Parcel.obtain();
        try {
            config.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            CustomFontRuntimeConfig copy = CustomFontRuntimeConfig.CREATOR.createFromParcel(parcel);
            assertEquals(42, copy.getGeneration());
            assertEquals("chosen", copy.getSelectedId());
            assertEquals(7, copy.getFontConfig().getConfigVersion());
            assertEquals("sans-serif", copy.getFamilies()[0]);
        } finally {
            parcel.recycle();
        }
    }
}
