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

import android.graphics.fonts.CustomFontConfig;
import android.graphics.fonts.CustomFontInfo;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.security.VerityUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns custom fonts only. Callers serialize operations on the FontManagerService font lock.
 * Selection changes are durable but do not replace the current boot's map or unlink live files.
 */
final class CustomFontStore {
    private static final String TAG = "CustomFontStore";
    private static final int MAX_FONTS = 16;
    private static final long MAX_STORAGE = 128L * 1024 * 1024;

    private final File mFiles;
    private final AtomicFile mConfig;
    private final AtomicFile mBootMarker;
    private final boolean mSafeMode;
    private final boolean mUseVerity;
    private final Map<String, String> mFonts = new LinkedHashMap<>();
    private final Map<String, CustomFontFile> mParsed = new LinkedHashMap<>();
    private String mSelected = "";
    private String mActive = "";
    private boolean mRecovered;
    private boolean mKeepBootMarker;

    CustomFontStore(File root, boolean safeMode, boolean useVerity) {
        mFiles = new File(root, "files");
        mConfig = new AtomicFile(new File(root, "config/state.xml"));
        mBootMarker = new AtomicFile(new File(root, "config/boot-pending"));
        mSafeMode = safeMode;
        mUseVerity = useVerity;
    }

    void load() {
        try {
            if (mConfig.exists()) readConfig();
            if (mBootMarker.exists()) {
                // A previous custom-font boot did not reach BOOT_COMPLETED. Do not retry it.
                mSelected = "";
                mRecovered = true;
                writeConfig();
                mBootMarker.delete();
            }
            for (String id : new ArrayList<>(mFonts.keySet())) {
                if (!file(id).isFile()) {
                    mFonts.remove(id);
                    if (mSelected.equals(id)) {
                        mSelected = "";
                        mRecovered = true;
                    }
                }
            }
            writeConfig();
            // Only at service startup, before a font map referencing this store exists.
            File[] files = mFiles.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.endsWith(".font")
                            && mFonts.containsKey(name.substring(0, name.length() - 5))) continue;
                    if (file.isFile() && !file.delete()) Slog.w(TAG, "Cannot reclaim " + file);
                }
            }
        } catch (IOException | RuntimeException e) {
            Slog.e(TAG, "Cannot load custom fonts; using ROM defaults", e);
            mFonts.clear();
            mSelected = "";
            mRecovered = true;
            mKeepBootMarker = mBootMarker.exists();
        }
        if (!mSafeMode && !mSelected.isEmpty()) {
            try {
                FileOutputStream out = mBootMarker.startWrite();
                try {
                    out.write(mSelected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    mBootMarker.finishWrite(out);
                } catch (IOException e) {
                    mBootMarker.failWrite(out);
                    throw e;
                }
                mActive = mSelected;
            } catch (IOException e) {
                Slog.e(TAG, "Cannot arm custom font boot recovery", e);
                mRecovered = true;
            }
        }
    }

    void bootCompleted() {
        if (!mKeepBootMarker) mBootMarker.delete();
    }

    String activeId() { return mActive; }

    boolean wasRecovered() { return mRecovered; }

    void dump(PrintWriter writer) {
        writer.println("Custom system fonts (device-wide, next-boot selection):");
        writer.println("  active=" + mActive + " selected=" + mSelected);
        writer.println("  safeMode=" + mSafeMode + " recovered=" + mRecovered
                + " fsVerity=" + mUseVerity + " bootPending=" + mBootMarker.exists());
        mFonts.forEach((id, name) -> writer.println("  " + id + " " + name));
    }

    void recover() {
        mActive = "";
        mSelected = "";
        mRecovered = true;
        try {
            writeConfig();
            mBootMarker.delete();
        } catch (IOException e) {
            // Keep the marker if the reset could not be persisted.
            mKeepBootMarker = true;
            Slog.e(TAG, "Cannot persist font recovery", e);
        }
    }

    CustomFontConfig snapshot(List<CustomFontInfo> builtIns) {
        List<CustomFontInfo> entries = new ArrayList<>(builtIns);
        mFonts.forEach((id, name) -> entries.add(new CustomFontInfo(id, name)));
        return new CustomFontConfig(entries, mSelected, mActive, mRecovered, mSafeMode);
    }

    CustomFontInfo importFont(ParcelFileDescriptor descriptor) throws IOException {
        if (mSafeMode) throw new IOException("Font import is disabled in safe mode");
        File temporary = null;
        try {
            StructStat stat = Os.fstat(descriptor.getFileDescriptor());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0
                    || stat.st_size > CustomFontInfo.MAX_FILE_SIZE) {
                throw new IOException("Expected a regular font file of at most 32 MiB");
            }
            long used = 0;
            File[] storedFiles = mFiles.listFiles();
            if (storedFiles == null) throw new IOException("Font storage unavailable");
            for (File stored : storedFiles) used += stored.length();
            temporary = File.createTempFile("import-", ".tmp", mFiles);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                while (true) {
                    // pread neither takes ownership nor changes the caller's shared FD offset.
                    int size = Os.pread(descriptor.getFileDescriptor(), buffer, 0, buffer.length,
                            total);
                    if (size == 0) break;
                    total += size;
                    if (total > CustomFontInfo.MAX_FILE_SIZE || total > stat.st_size) {
                        throw new IOException("Font changed size during import");
                    }
                    digest.update(buffer, 0, size);
                    out.write(buffer, 0, size);
                }
                if (total != stat.st_size) throw new IOException("Incomplete font");
                out.getFD().sync();
            }
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) hash.append(String.format("%02x", value & 0xff));
            String id = hash.toString();
            if (mFonts.containsKey(id)) return new CustomFontInfo(id, mFonts.get(id));
            if (used + stat.st_size > MAX_STORAGE) {
                throw new IOException("Font storage full; remove fonts and reboot");
            }
            if (mFonts.size() >= MAX_FONTS) throw new IOException("Font library full");
            Os.chmod(temporary.getAbsolutePath(), 0644);
            if (mUseVerity) VerityUtils.setUpFsverity(temporary.getAbsolutePath());
            // Validate before committing metadata. Never use caller filenames or PostScript names
            // as paths, and never overwrite a file that an existing process may have mapped.
            CustomFontFile parsed = CustomFontFile.validateForImport(temporary);
            File destination = file(id);
            if (destination.exists()) {
                // A deleted entry's immutable content may still be referenced by this boot.
                if (!id.equals(hashFile(destination))) throw new IOException("Font hash mismatch");
            } else if (!temporary.renameTo(destination)) {
                throw new IOException("Cannot commit font file");
            }
            java.io.FileDescriptor directory = Os.open(mFiles.getAbsolutePath(),
                    OsConstants.O_RDONLY, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) {
                    throw new IOException("Font storage is not a directory");
                }
                Os.fsync(directory);
            } finally {
                Os.close(directory);
            }
            // Bind the already validated styles to their final immutable path.
            mFonts.put(id, parsed.name);
            try {
                writeConfig();
            } catch (IOException e) {
                mFonts.remove(id);
                throw e;
            }
            mParsed.put(id, parsed.withFile(destination));
            return new CustomFontInfo(id, parsed.name);
        } catch (ErrnoException e) {
            throw new IOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    private static String hashFile(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int size;
            while ((size = in.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest.digest()) hash.append(String.format("%02x", value & 0xff));
        return hash.toString();
    }

    CustomFontFile parsed(String id) throws IOException {
        requireImported(id);
        CustomFontFile parsed = mParsed.get(id);
        if (parsed == null) {
            File font = file(id);
            if (font.length() <= 0 || font.length() > CustomFontInfo.MAX_FILE_SIZE) {
                throw new IOException("Invalid stored font size");
            }
            try {
                if (!id.equals(hashFile(font))) throw new IOException("Stored font changed");
                if (mUseVerity && !VerityUtils.hasFsverity(font.getAbsolutePath())) {
                    throw new IOException("Stored font lost fs-verity");
                }
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
            parsed = CustomFontFile.read(font);
            mParsed.put(id, parsed);
        }
        return parsed;
    }

    void select(String id) throws IOException {
        if (mSafeMode && !id.isEmpty()) throw new IOException("Safe mode");
        String old = mSelected;
        mSelected = id;
        try {
            writeConfig();
        } catch (IOException e) {
            mSelected = old;
            throw e;
        }
    }

    void delete(String id) throws IOException {
        requireImported(id);
        if (id.equals(mSelected) || id.equals(mActive)) {
            throw new IOException("Select another font and reboot before deleting this font");
        }
        String name = mFonts.remove(id);
        try {
            writeConfig();
        } catch (IOException e) {
            mFonts.put(id, name);
            throw e;
        }
        mParsed.remove(id);
        // Physical deletion is deferred until startup.
    }

    ParcelFileDescriptor open(String id) throws IOException {
        requireImported(id);
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void requireImported(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}") || !mFonts.containsKey(id)) {
            throw new IOException("Unknown custom font");
        }
    }

    private File file(String id) { return new File(mFiles, id + ".font"); }

    private void readConfig() throws IOException {
        try (FileInputStream in = mConfig.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);
            int event;
            boolean root = false;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                if (parser.getDepth() == 1 && "custom-fonts".equals(parser.getName())) {
                    if (parser.getAttributeInt(null, "version") != 1) {
                        throw new IOException("Unsupported font state");
                    }
                    mSelected = parser.getAttributeValue(null, "selected");
                    if (mSelected == null || mSelected.length() > 256) {
                        throw new IOException("Invalid selection");
                    }
                    root = true;
                } else if (root && parser.getDepth() == 2 && "font".equals(parser.getName())) {
                    String id = parser.getAttributeValue(null, "id");
                    String name = parser.getAttributeValue(null, "name");
                    if (id == null || !id.matches("[0-9a-f]{64}") || name == null
                            || name.length() > 128 || mFonts.size() >= MAX_FONTS
                            || mFonts.put(id, name) != null) {
                        throw new IOException("Invalid library entry");
                    }
                } else {
                    throw new IOException("Invalid font state");
                }
            }
            if (!root) throw new IOException("Missing font state");
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }

    private void writeConfig() throws IOException {
        FileOutputStream out = mConfig.startWrite();
        try {
            TypedXmlSerializer serializer = Xml.resolveSerializer(out);
            serializer.startDocument(null, true);
            serializer.startTag(null, "custom-fonts");
            serializer.attributeInt(null, "version", 1);
            serializer.attribute(null, "selected", mSelected);
            for (Map.Entry<String, String> font : mFonts.entrySet()) {
                serializer.startTag(null, "font");
                serializer.attribute(null, "id", font.getKey());
                serializer.attribute(null, "name", font.getValue());
                serializer.endTag(null, "font");
            }
            serializer.endTag(null, "custom-fonts");
            serializer.endDocument();
            mConfig.finishWrite(out);
        } catch (IOException | RuntimeException e) {
            mConfig.failWrite(out);
            throw e;
        }
    }
}
