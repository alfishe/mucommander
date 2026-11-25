package com.mucommander.ui.main.table;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.FileFactory;
import com.mucommander.commons.file.FileURL;
import com.mucommander.commons.file.impl.zip.ZipFormatProvider;
import com.mucommander.commons.file.impl.CachedFile;
import com.mucommander.commons.file.impl.local.LocalFile;
import com.mucommander.ui.main.table.views.BaseFileTableModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attributes read by the single-lstat prefetch must be the ones LocalFile returns, and children created by the
 * LocalFile#ls fast path must be equivalent to the ones FileFactory creates.
 */
@DisabledOnOs({OS.WINDOWS, OS.MAC})
public class LargeFolderPrefetchTest {

    @TempDir
    Path dir;

    private void populate() throws Exception {
        if (!FileFactory.isArchiveFilename("archive.zip")) {
            FileFactory.registerArchiveFormat(new ZipFormatProvider());
        }
        Path file = Files.writeString(dir.resolve("plain.txt"), "hello world");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_500_000_000_123L));
        Files.writeString(dir.resolve(".hidden"), "x");
        Path sub = Files.createDirectory(dir.resolve("folder"));
        Files.createDirectory(dir.resolve(".hiddenFolder"));
        Files.createSymbolicLink(dir.resolve("link-to-file"), file);
        Files.createSymbolicLink(dir.resolve("link-to-folder"), sub);
        Files.createSymbolicLink(dir.resolve("broken-link"), dir.resolve("does-not-exist"));
        Files.writeString(dir.resolve("archive.zip"), "not really a zip");
    }

    @Test
    public void prefetchedAttributesMatchLocalFile() throws Exception {
        populate();
        AbstractFile folder = FileFactory.getFile(dir.toString());
        AbstractFile[] children = folder.ls();
        AbstractFile[] prepared = children.clone();
        BaseFileTableModel.prepareFiles(prepared);

        for (int i = 0; i < children.length; i++) {
            AbstractFile raw = FileFactory.getFile(children[i].getAbsolutePath());
            AbstractFile cached = prepared[i];
            assertInstanceOf(CachedFile.class, cached);
            String n = raw.getName();
            assertEquals(raw.isDirectory(), cached.isDirectory(), n + " isDirectory");
            assertEquals(raw.isSymlink(), cached.isSymlink(), n + " isSymlink");
            assertEquals(raw.isHidden(), cached.isHidden(), n + " isHidden");
            assertEquals(raw.exists(), cached.exists(), n + " exists");
            assertEquals(raw.isBrowsable(), cached.isBrowsable(), n + " isBrowsable");
            assertEquals(raw.isArchive(), cached.isArchive(), n + " isArchive");
            assertEquals(raw.getLastModifiedDate(), cached.getLastModifiedDate(), n + " date");
            if (!raw.isDirectory()) {
                assertEquals(raw.getSize(), cached.getSize(), n + " size");
            }
        }
        Map<String, AbstractFile> byName = Arrays.stream(prepared).collect(Collectors.toMap(AbstractFile::getName, Function.identity()));
        assertEquals(11, byName.get("link-to-file").getSize());
        assertTrue(byName.get("link-to-folder").isDirectory());
        assertFalse(byName.get("broken-link").exists());
        assertEquals(1_500_000_000_123L, byName.get("plain.txt").getLastModifiedDate());
    }

    @Test
    public void fastPathChildrenAreEquivalentToFactoryFiles() throws Exception {
        populate();
        AbstractFile folder = FileFactory.getFile(dir.toString());
        for (AbstractFile child : folder.ls()) {
            // Exactly what LocalFile#ls used to do for every child
            FileURL url = (FileURL) folder.getURL().clone();
            url.setPath(dir + "/" + child.getName());
            AbstractFile viaFactory = FileFactory.getFile(url, folder, new java.io.File(dir.toFile(), child.getName()));
            assertEquals(viaFactory.getClass(), child.getClass(), child.getName());
            assertEquals(viaFactory.getURL(), child.getURL(), child.getName());
            assertEquals(viaFactory, child);
            assertEquals(viaFactory.getAbsolutePath(), child.getAbsolutePath());
            assertEquals(folder, child.getParent());
            if (child instanceof LocalFile) {
                // One path string shared by java.io.File, FileURL and LocalFile
                assertSame(((java.io.File) child.getUnderlyingFileObject()).getPath(), child.getURL().getPath());
            }
        }
        assertTrue(Arrays.stream(folder.ls()).anyMatch(AbstractFile::isArchive), "archive.zip is still wrapped as an archive");
    }

    @Test
    public void largeFolderIsPrefetchedInParallelWithTheSameValues() throws Exception {
        populate();
        for (int i = 0; i < 2600; i++) {
            Path p = dir.resolve((i % 7 == 0 ? "." : "") + "f" + i + (i % 3 == 0 ? ".txt" : ""));
            if (i % 11 == 0) {
                Files.createDirectory(p);
            } else {
                Files.write(p, new byte[i % 50]);
                Files.setLastModifiedTime(p, FileTime.fromMillis(1_000_000_000_000L + i * 1001L));
            }
        }
        AbstractFile[] children = FileFactory.getFile(dir.toString()).ls();
        assertTrue(children.length > 2000);
        AbstractFile[] prepared = children.clone();
        BaseFileTableModel.prepareFiles(prepared);
        for (int i = 0; i < children.length; i++) {
            AbstractFile raw = children[i];
            AbstractFile cached = prepared[i];
            String n = raw.getName();
            assertEquals(raw.getName(), cached.getName());
            assertEquals(raw.isDirectory(), cached.isDirectory(), n);
            assertEquals(raw.isHidden(), cached.isHidden(), n);
            assertEquals(raw.isSymlink(), cached.isSymlink(), n);
            assertEquals(raw.getLastModifiedDate(), cached.getLastModifiedDate(), n);
            if (!raw.isDirectory()) {
                assertEquals(raw.getSize(), cached.getSize(), n);
            }
        }
    }

    @Test
    public void cachedFileExistsDoesNotDisableRecursion() throws Exception {
        populate();
        // exists() used to share its bit with the 'recursive instances' flag
        CachedFile broken = new CachedFile(FileFactory.getFile(dir.resolve("broken-link").toString()), true);
        assertFalse(broken.exists());
        assertInstanceOf(CachedFile.class, broken.getParent());
    }
}
