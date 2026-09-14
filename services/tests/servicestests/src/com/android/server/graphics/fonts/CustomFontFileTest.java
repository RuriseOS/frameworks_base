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

package com.android.server.graphics.fonts;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CustomFontFileTest {
    private static final int WGHT = 0x77676874;

    private ByteBuffer variableFont(int min, int def, int max) {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0, 0x00010000);
        b.putShort(4, (short) 1);
        b.putInt(12, 0x66766172);
        b.putInt(20, 28);
        b.putInt(24, 36);
        b.putInt(28, 0x00010000);
        b.putShort(32, (short) 16);
        b.putShort(36, (short) 1);
        b.putShort(38, (short) 20);
        b.putInt(44, WGHT);
        b.putInt(48, min * 65536);
        b.putInt(52, def * 65536);
        b.putInt(56, max * 65536);
        return b;
    }

    @Test
    public void readsActualWeightRange() throws Exception {
        Map<Integer, CustomFontFile.Axis> axes =
                CustomFontFile.readAxes(variableFont(300, 450, 700));
        assertThat(axes).hasSize(1);
        assertThat(axes.get(WGHT).min).isEqualTo(300);
        assertThat(axes.get(WGHT).def).isEqualTo(450);
        assertThat(axes.get(WGHT).max).isEqualTo(700);
        assertThat(axes.get(WGHT).contains(900)).isFalse();
    }

    @Test
    public void rejectsInvertedAxisRange() {
        assertThrows(IOException.class,
                () -> CustomFontFile.readAxes(variableFont(500, 400, 700)));
    }

    @Test
    public void rejectsUnsignedTableOffsetOutsideFile() {
        ByteBuffer b = variableFont(100, 400, 900);
        b.putInt(20, -1);
        assertThrows(IOException.class, () -> CustomFontFile.readAxes(b));
    }

    @Test
    public void rejectsAxisArrayOutsideFvarEvenInsideFile() {
        ByteBuffer b = variableFont(100, 400, 900);
        b.putInt(24, 20);
        assertThrows(IOException.class, () -> CustomFontFile.readAxes(b));
    }

    @Test
    public void rejectsCollectionsAndTruncatedHeaders() {
        ByteBuffer b = variableFont(100, 400, 900);
        b.putInt(0, 0x74746366);
        assertThrows(IOException.class, () -> CustomFontFile.readAxes(b));
        assertThrows(IOException.class, () -> CustomFontFile.readAxes(ByteBuffer.allocate(4)));
    }

    @Test
    public void staticFontHasNoVariationAxes() throws Exception {
        ByteBuffer b = variableFont(100, 400, 900);
        b.putInt(12, 0x6e616d65);
        assertThat(CustomFontFile.readAxes(b)).isEmpty();
    }
}
