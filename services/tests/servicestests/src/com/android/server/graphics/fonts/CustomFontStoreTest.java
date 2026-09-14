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

import android.os.ParcelFileDescriptor;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CustomFontStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private CustomFontStore store(File root, boolean safeMode) {
        new File(root, "files").mkdirs();
        new File(root, "config").mkdirs();
        CustomFontStore store = new CustomFontStore(root, safeMode, false);
        store.load();
        return store;
    }

    @Test
    public void selectionIsPendingUntilNextBootAndDefaultDoesNotClearFiles() throws Exception {
        File root = temporary.newFolder();
        CustomFontStore first = store(root, false);
        first.select("builtin:serif");
        assertThat(first.snapshot(List.of()).getSelectedId()).isEqualTo("builtin:serif");
        assertThat(first.activeId()).isEmpty();

        CustomFontStore second = store(root, false);
        assertThat(second.activeId()).isEqualTo("builtin:serif");
        second.bootCompleted();
        second.select("");
        assertThat(second.activeId()).isEqualTo("builtin:serif");
        assertThat(second.snapshot(List.of()).isRebootRequired()).isTrue();
        assertThat(store(root, false).activeId()).isEmpty();
    }

    @Test
    public void incompleteBootFallsBackWithoutRetryingSelection() throws Exception {
        File root = temporary.newFolder();
        store(root, false).select("builtin:serif");
        assertThat(store(root, false).activeId()).isEqualTo("builtin:serif");
        CustomFontStore recovered = store(root, false);
        assertThat(recovered.activeId()).isEmpty();
        assertThat(recovered.snapshot(List.of()).getSelectedId()).isEmpty();
        assertThat(recovered.snapshot(List.of()).wasRecovered()).isTrue();
    }

    @Test
    public void safeModeDoesNotActivateOrForgetAHealthySelection() throws Exception {
        File root = temporary.newFolder();
        store(root, false).select("builtin:serif");
        CustomFontStore safe = store(root, true);
        assertThat(safe.activeId()).isEmpty();
        assertThat(safe.snapshot(List.of()).getSelectedId()).isEqualTo("builtin:serif");
        assertThrows(IOException.class, () -> safe.select("builtin:casual"));
        safe.select("");
    }

    @Test
    public void failedSelectionWritePreservesPreviousSelection() throws Exception {
        File root = temporary.newFolder();
        CustomFontStore fonts = store(root, false);
        fonts.select("builtin:serif");
        File config = new File(root, "config");
        File saved = new File(root, "saved-config");
        assertThat(config.renameTo(saved)).isTrue();
        assertThat(config.createNewFile()).isTrue();
        assertThrows(IOException.class, () -> fonts.select("builtin:casual"));
        assertThat(fonts.snapshot(List.of()).getSelectedId()).isEqualTo("builtin:serif");
    }

    @Test
    public void refusesProviderPipesWithoutReadingThem() throws Exception {
        CustomFontStore fonts = store(temporary.newFolder(), false);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor read = pipe[0]; ParcelFileDescriptor write = pipe[1]) {
            assertThrows(IOException.class, () -> fonts.importFont(read));
        }
    }

    @Test
    public void rejectsMalformedFontWithoutChangingSelectionOrLeavingTemporaryFiles()
            throws Exception {
        File root = temporary.newFolder();
        CustomFontStore fonts = store(root, false);
        fonts.select("builtin:serif");
        File malformed = temporary.newFile();
        try (FileOutputStream out = new FileOutputStream(malformed)) {
            out.write(new byte[] {1, 2, 3, 4});
        }
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(
                malformed, ParcelFileDescriptor.MODE_READ_ONLY)) {
            assertThrows(IOException.class, () -> fonts.importFont(fd));
        }
        assertThat(fonts.snapshot(List.of()).getSelectedId()).isEqualTo("builtin:serif");
        assertThat(fonts.snapshot(List.of()).getFonts()).isEmpty();
        assertThat(new File(root, "files").list()).isEmpty();
    }

    @Test
    public void pathTraversalIsNotALibraryId() throws Exception {
        CustomFontStore fonts = store(temporary.newFolder(), false);
        assertThrows(IOException.class, () -> fonts.open("../../config/state.xml"));
        assertThrows(IOException.class, () -> fonts.delete("../../config/state.xml"));
    }

    @Test
    public void removalUnlinksOnlyOnNextBoot() throws Exception {
        File root = temporary.newFolder();
        new File(root, "files").mkdirs();
        new File(root, "config").mkdirs();
        String id = "a".repeat(64);
        File font = new File(root, "files/" + id + ".font");
        assertThat(font.createNewFile()).isTrue();
        try (FileOutputStream out = new FileOutputStream(new File(root, "config/state.xml"))) {
            out.write(("<custom-fonts version=\"1\" selected=\"\"><font id=\"" + id
                    + "\" name=\"Example\"/></custom-fonts>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        CustomFontStore fonts = store(root, false);
        fonts.delete(id);
        assertThat(fonts.snapshot(List.of()).getFonts()).isEmpty();
        assertThat(font.exists()).isTrue();
        store(root, false);
        assertThat(font.exists()).isFalse();
    }
}
