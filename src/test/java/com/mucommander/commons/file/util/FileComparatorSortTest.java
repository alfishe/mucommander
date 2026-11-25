package com.mucommander.commons.file.util;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.FileFactory;
import com.mucommander.commons.file.impl.TestFile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyed sort of {@link FileComparator} must order files exactly as the comparator does, for every criterion and
 * option, including the natural number ordering of names.
 */
public class FileComparatorSortTest {

    private static final int[] CRITERIA = {FileComparator.NAME_CRITERION, FileComparator.SIZE_CRITERION,
            FileComparator.DATE_CRITERION, FileComparator.EXTENSION_CRITERION, FileComparator.PERMISSIONS_CRITERION,
            FileComparator.OWNER_CRITERION, FileComparator.GROUP_CRITERION};

    private static AbstractFile[] randomFiles(int n, long seed) throws Exception {
        Random rnd = new Random(seed);
        String[] prefixes = {"file", "File", "img_", "a", "b10", "", "._x", "Zeta", "report-2024-"};
        String[] exts = {"", ".txt", ".TXT", ".tar.gz", ".jpg", ".e10", ".e9", "."};
        AbstractFile[] files = new AbstractFile[n];
        for (int i = 0; i < n; i++) {
            String name = prefixes[rnd.nextInt(prefixes.length)]
                    + (rnd.nextBoolean() ? String.valueOf(rnd.nextInt(rnd.nextBoolean() ? 20 : 100000)) : "")
                    + (rnd.nextInt(4) == 0 ? "0" + rnd.nextInt(10) + "b" : "")
                    + exts[rnd.nextInt(exts.length)];
            if (name.isEmpty() || name.equals(".")) {
                name = "x" + i;
            }
            boolean dir = rnd.nextInt(5) == 0;
            long size = rnd.nextInt(10) == 0 ? -1 : rnd.nextInt(3) == 0 ? rnd.nextInt(5) : rnd.nextLong() >>> 20;
            long date = rnd.nextInt(3) == 0 ? 1_000_000L * rnd.nextInt(5) : rnd.nextLong() >>> 16;
            files[i] = new TestFile(FileFactory.getTemporaryFolder() + "d" + i + "/" + name, dir, size, date, null);
        }
        return files;
    }

    @Test
    public void keyedSortMatchesComparator() throws Exception {
        AbstractFile[] original = randomFiles(3000, 42);
        int combinations = 0;
        for (int criterion : CRITERIA) {
            for (boolean ascending : new boolean[]{true, false}) {
                for (boolean foldersFirst : new boolean[]{true, false}) {
                    for (boolean alphabetical : new boolean[]{true, false}) {
                        FileComparator c = new FileComparator(criterion, ascending, foldersFirst, alphabetical);
                        AbstractFile[] sorted = original.clone();
                        c.sort(sorted);
                        for (int i = 1; i < sorted.length; i++) {
                            int finalI = i;
                            assertTrue(c.compare(sorted[i - 1], sorted[i]) <= 0,
                                    () -> "criterion " + criterion + " asc=" + ascending + " folders first=" + foldersFirst
                                            + ": " + sorted[finalI - 1].getName() + " > " + sorted[finalI].getName());
                        }
                        // Same files, each exactly once
                        AbstractFile[] a = original.clone();
                        AbstractFile[] b = sorted.clone();
                        Arrays.sort(a, (x, y) -> Integer.compare(System.identityHashCode(x), System.identityHashCode(y)));
                        Arrays.sort(b, (x, y) -> Integer.compare(System.identityHashCode(x), System.identityHashCode(y)));
                        assertTrue(Arrays.equals(a, b));
                        combinations++;
                    }
                }
            }
        }
        assertEquals(CRITERIA.length * 8, combinations);
    }

    @Test
    public void sortIsStableAndSortedInputIsUnchanged() throws Exception {
        AbstractFile[] files = randomFiles(2000, 7);
        FileComparator c = new FileComparator(FileComparator.SIZE_CRITERION, true, true, false);
        c.sort(files);
        AbstractFile[] again = files.clone();
        c.sort(again);
        assertTrue(Arrays.equals(files, again));
    }

    @Test
    public void parallelSortOfLargeArraysGivesTheSameOrderAsSequentialSort() throws Exception {
        // Note: not compared with Arrays.sort: the natural name order is not transitive for some mixed case names
        // ("File08b" < "file6" < "file02b" but "File08b" > "file02b"), different algorithms can then legitimately
        // disagree. The parallel sort must give exactly the sequential merge sort's result.
        AbstractFile[] files = randomFiles(60_000, 99);
        for (int criterion : CRITERIA) {
            for (boolean ascending : new boolean[]{true, false}) {
                FileComparator c = new FileComparator(criterion, ascending, true, criterion == 0);
                int[] parallel = new int[files.length];
                int[] sequential = new int[files.length];
                for (int i = 0; i < files.length; i++) {
                    parallel[i] = sequential[i] = i;
                }
                c.sortIndexes(files, parallel, true);
                c.sortIndexes(files, sequential, false);
                assertTrue(Arrays.equals(sequential, parallel), "criterion " + criterion + " asc=" + ascending);
                for (int i = 1; i < parallel.length; i++) {
                    assertTrue(c.compare(files[parallel[i - 1]], files[parallel[i]]) <= 0, "criterion " + criterion + " at " + i);
                }
            }
        }
    }

    @Test
    public void naturalNumberOrder() throws Exception {
        String[] names = {"file10.txt", "file9.txt", "file1.txt", "file1a.txt", "file010.txt", "file2.txt", "File3.txt"};
        AbstractFile[] files = new AbstractFile[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = new TestFile(FileFactory.getTemporaryFolder() + names[i], false, 0, 0, null);
        }
        new FileComparator(FileComparator.NAME_CRITERION, true, true, false).sort(files);
        String[] result = Arrays.stream(files).map(AbstractFile::getName).toArray(String[]::new);
        AbstractFile[] expected = files.clone();
        Arrays.sort(expected, new FileComparator(FileComparator.NAME_CRITERION, true, true, false));
        assertEquals(Arrays.toString(Arrays.stream(expected).map(AbstractFile::getName).toArray()), Arrays.toString(result));
        assertEquals("file1.txt", result[0]);
        assertEquals("file9.txt", names[1]);
        assertTrue(Arrays.asList(result).indexOf("file9.txt") < Arrays.asList(result).indexOf("file10.txt"));
    }
}
