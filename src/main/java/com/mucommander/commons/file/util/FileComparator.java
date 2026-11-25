package com.mucommander.commons.file.util;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.ui.quicksearch.QuickSearch;

import java.util.Comparator;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;


/**
 * FileComparator compares {@link AbstractFile} instances using a comparison criterion order (ascending or descending).
 * Directories can either be mixed with regular files (compared just as regular files), or always precede regular files.

 * <p>FileComparator extends Comparator and thus can be used wherever a Comparator is accepted. In particular, it
 * can be used with <code>java.util.Arrays</code> sort methods to easily sort an array of files.
 *
 * <p>The following criteria are available:
 * <ul>
 * <li>{@link #NAME_CRITERION}: compares filenames returned by {@link AbstractFile#getName()}
 * <li>{@link #SIZE_CRITERION}: compares file sizes returned by {@link AbstractFile#getSize()}. Note: size for
 * directories is always considered as 0, even if {@link AbstractFile#getSize()} returns something else. 
 * <li>{@link #DATE_CRITERION}: compares file dates returned by {@link AbstractFile#getLastModifiedDate()}
 * <li>{@link #EXTENSION_CRITERION}: compares file extensions returned by {@link AbstractFile#getExtension()}
 * <li>{@link #PERMISSIONS_CRITERION}: compares file permissions returned by {@link AbstractFile#getPermissions()}
 * </ul>
 *
 * @author Maxence Bernard
 */
public class FileComparator implements Comparator<AbstractFile> {

    /** Comparison criterion */
    private final int criterion;
    /** Ascending or descending order ? */
    private final boolean ascending;
    /** Specifies whether directories should precede files or be handled as regular files */
    private final boolean directoriesFirst;
    /** Specifies whether directories always alphabetical sorted */
    private final boolean foldersAlwaysAlphabetical;

    /** Criterion for filename comparison. */
    public final static int NAME_CRITERION = 0;
    /** Criterion for file size comparison. */
    public final static int SIZE_CRITERION = 1;
    /** Criterion for file date comparison. */
    public final static int DATE_CRITERION = 2;
    /** Criterion for file extension comparison. */
    public final static int EXTENSION_CRITERION = 3;
    /** Criterion for file permissions comparison. */
    public final static int PERMISSIONS_CRITERION = 4;
    /** Criterion for owner comparison. */
    public final static int OWNER_CRITERION = 5;
    /** Criterion for group comparison. */
    public final static int GROUP_CRITERION = 6;

    /** Matches filenames that contain a number, like "01 - Do the Joy.mp3" */
    private final static Pattern FILENAME_WITH_NUMBER_PATTERN = Pattern.compile("\\d+");

    private final QuickSearch quickSearch;


    /**
     * Creates a new FileComparator using the specified comparison criterion, order (ascending or descending) and
     * directory handling rule.
     *
     * @param criterion comparison criterion, see constant fields
     * @param ascending if true, ascending order will be used, descending order otherwise
     * @param directoriesFirst specifies whether directories should precede files or be handled as regular files
     * @param foldersAlwaysAlphabetical specifies whether directories are sorted always alphabetical if they stay first
     */
    public FileComparator(int criterion, boolean ascending, boolean directoriesFirst, boolean foldersAlwaysAlphabetical, QuickSearch quickSearch) {
        this.criterion = criterion;
        this.ascending = ascending;
        this.directoriesFirst = directoriesFirst;
        this.foldersAlwaysAlphabetical = foldersAlwaysAlphabetical && directoriesFirst;
        this.quickSearch = quickSearch;
    }

    public FileComparator(int criterion, boolean ascending, boolean directoriesFirst, boolean foldersAlwaysAlphabetical) {
        this(criterion, ascending, directoriesFirst, foldersAlwaysAlphabetical, null);
    }


    /**
     * Returns a <code>value</code> for the given character. Using this function in a comparator will separator
     * symbols for digits and letters and put in the following order:
     * <ul>
     *   <li>symbols first</li>
     *   <li>digits second</li>
     *   <li>letters third</li>
     * </ul>
     *
     * <p>This character order was suggested in ticket #282.
     *
     * @param c character for which to return a value
     * @return a <code>value</code> for the given character
     */
    private static int getCharacterValue(int c) {
        // Note: max char value is 65535
        if (Character.isLetter(c))
            c += 131070;    // yields a value higher than any other symbol or digit
        else if (Character.isDigit(c))
            c += 65535;     // yields a value higher than any other symbol

        // else we have a symbol
        return c;
    }

    /**
     * Removes leading zeros ('0') from the given string (if it contains any), and returns the trimmed string.
     *
     * @param s the string from which to remove leading zeros
     * @return a string without leading zeros
     */
    private static String removeLeadingZeros(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && s.charAt(i) == '0') {
            i++;
        }

        if (i > 0) {
            return s.substring(i, len);
        }
        return s;
    }

    /**
     * Compare the specified strings, following the contract of {@link Comparator#compare(Object, Object)}.
     *
     * @param s1 first string to compare
     * @param s2 second string to compare.
     * @param ignoreCase <code>true</code> to perform a case-insensitive string comparison, <code>false</code> to take
     * the case into account.
     * @param nullProtection <code>true</code> if any of s1 or s2 can be <code>null</code>, <code>false</code>
     * if strings cannot be <code>null</code>.
     * @return a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater
     * than the second.
     */
    private static int compareStrings(String s1, String s2, boolean ignoreCase, boolean nullProtection) {
        // Protect against null values, only if requested
        if (nullProtection) {
            if (s1 == null && s2 != null) {	    // s1 is null, s2 isn't
                return -1;
            } else if (s1 != null && s2 == null) {    // s2 is null, s1 isn't
                return 1;
                // At this point, either both strings are null, or none of them are
            } else {
                if (s1 == null)		        // Both strings are null
                    return 0;
                // else: Both strings are not null, go on with the comparison
            }
        }
        return compareStrings(s1, s2, ignoreCase);
    }

    /**
     * Compare the specified strings, following the contract of {@link Comparator#compare(Object, Object)}.
     *
     * @param s1 first string to compare
     * @param s2 second string to compare.
     * @param ignoreCase <code>true</code> to perform a case-insensitive string comparison, <code>false</code> to take
     * the case into account.
     * @return a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater
     * than the second.
     */
    private static int compareStrings(String s1, String s2, boolean ignoreCase) {
        return compareStrings(s1, firstDigitPos(s1), s2, -2, ignoreCase);
    }

    /**
     * Same as {@link #compareStrings(String, String, boolean)} with the position of the first digit of the strings
     * already known ({@link #firstDigitPos(String)}), or -2 if it hasn't been computed yet.
     */
    private static int compareStrings(String s1, int digitIndex1, String s2, int digitIndex2, boolean ignoreCase) {
        // Special treatment for strings that contain a number, so they are ordered by the number's value, e.g.:
        // 1 < 1a < 2 < 10, like Mac OS X Finder and Windows Explorer do.
        //
        // This special order applies only if both strings contain a number and have the same prefix. Otherwise, the general order applies.
        if (digitIndex1 >= 0) {
            if (digitIndex2 == -2) {
                digitIndex2 = firstDigitPos(s2);
            }
            if (digitIndex2 >= 0) {
                // So we got two filenames that both contain a number, check if they have the same prefix

                // Note: compare prefixes only if start indexes match, faster that way
                if (digitIndex1 == digitIndex2 && (digitIndex1==0 || s1.regionMatches(0, s2, 0, digitIndex1))) {
                    int g1Len = 0;
                    int l1 = s1.length();
                    for (int i = digitIndex1; i < l1; i++) {
                        char c = s1.charAt(i);
                        if (c >= '0' && c <= '9') {
                            g1Len++;
                        } else {
                            break;
                        }
                    }
                    int g2Len = 0;
                    int l2 = s2.length();
                    for (int i = digitIndex2; i < l2; i++) {
                        char c = s2.charAt(i);
                        if (c >= '0' && c <= '9') {
                            g2Len++;
                        } else {
                            break;
                        }
                    }

                    if (g1Len != g2Len) {
                        return g1Len - g2Len;
                    }

                    for (int i = 0; i < g1Len && i < g2Len; i++) {
                        int c1 = s1.charAt(i + digitIndex1);
                        int c2 = s2.charAt(i + digitIndex2);
                        if (c1 != c2) {
                            return c1 - c2;
                        }
                    }
                }
            }
        }

        int n1 = s1.length();
        int n2 = s2.length();

        for (int i=0; i<n1 && i<n2; i++) {
            int c1 = s1.charAt(i);
            int c2 = s2.charAt(i);

            if (ignoreCase) {
                if (c1 != c2) {
                    c1 = Character.toUpperCase(c1);
                    c2 = Character.toUpperCase(c2);
                    if (c1 != c2) {
                        // Quote from String#regionsMatches:
                        // "Unfortunately, conversion to uppercase does not work properly for the Georgian alphabet, which
                        // has strange rules about case conversion. So we need to make one last check before exiting."
                        c1 = Character.toLowerCase(c1);
                        c2 = Character.toLowerCase(c2);

                        if (c1 != c2)
                            return getCharacterValue(c1) -  getCharacterValue(c2);
                    }
                }
            } else if (c1 != c2) {
                return getCharacterValue(c1) -  getCharacterValue(c2);
            }
        }

        return n1 - n2;
    }


    private static int firstDigitPos(String s) {
        if (s == null) {
            return -1;
        }
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                return i;
            }
        }
        return -1;
    }


    ///////////////////////////////
    // Comparator implementation //
    ///////////////////////////////
    
    public int compare(AbstractFile f1, AbstractFile f2) {
        if (quickSearch != null) {
            boolean m1 = quickSearch.matches(f1);
            boolean m2 = quickSearch.matches(f2);
            if (m1 & !m2) {
                return -1;
            } else if (m2 && !m1) {
                return 1;
            }
        }

        boolean is1Directory = f1.isDirectory();
        boolean is2Directory = f2.isDirectory();
        long diff;

        if (directoriesFirst) {
            if (is1Directory && !is2Directory) {
                return -1;    // ascending has no effect on the result (a directory is always first) so let's return
            } else if (is2Directory && !is1Directory) {
                return 1;    // ascending has no effect on the result (a directory is always first) so let's return
            }
            if (foldersAlwaysAlphabetical && is1Directory) {
                diff = compareStrings(f1.getName(), f2.getName(), true);
                if (diff == 0) {
                    // Case-sensitive name comparison
                    diff = compareStrings(f1.getName(), f2.getName(), false);
                }
                return (int) diff;
            }
            // At this point, either both files are directories or none of them are
        }

        if (criterion == SIZE_CRITERION)  {
            // Consider that directories have a size of 0
            long fileSize1 = is1Directory ? 0 : f1.getSize();
            long fileSize2 = is2Directory ? 0 : f2.getSize();

            // Returns file1 size - file2 size, file size of -1 (unavailable) is considered as enormous (max long value)
            diff = (fileSize1 == -1 ? Long.MAX_VALUE : fileSize1) - (fileSize2 == -1 ? Long.MAX_VALUE : fileSize2);
        } else if (criterion == DATE_CRITERION) {
            diff = f1.getLastModifiedDate() - f2.getLastModifiedDate();
        } else if (criterion == PERMISSIONS_CRITERION) {
            diff = f1.getPermissions().getIntValue() - f2.getPermissions().getIntValue();
        } else if (criterion == EXTENSION_CRITERION) {
            diff = compareStrings(f1.getExtension(), f2.getExtension(), true, true);
        } else if (criterion == OWNER_CRITERION) {
            diff = compareStrings(f1.getOwner(), f2.getOwner(), true, true);
        } else if (criterion == GROUP_CRITERION) {
            diff = compareStrings(f1.getGroup(), f2.getGroup(), true, true);
        } else {      // criterion == NAME_CRITERION
            diff = compareStrings(f1.getName(), f2.getName(), true);
            if (diff == 0) {
                // This should never happen unless the current filesystem allows a directory to have
                // several files with different case variations of the same name.
                // AFAIK, no OS/filesystem allows this, but just to be safe.

                // Case-sensitive name comparison
                diff = compareStrings(f1.getName(), f2.getName(), false);
            }
        }

        if (criterion != NAME_CRITERION && diff==0)	// If both files have the same criterion's value, compare names
            diff = compareStrings(f1.getName(), f2.getName(), true, false);

        // Cast long value to int, without overflowing the int if the long value exceeds the min or max int value
        int intValue;
        
        if (diff > Integer.MAX_VALUE) {
            intValue = Integer.MAX_VALUE;   // 2147483647
        } else if (diff < Integer.MIN_VALUE+1) {  // Need that +1 so that the int is not overflowed if ascending order is enabled (i.e. int is negated)
            intValue = Integer.MIN_VALUE + 1; // 2147483647
        } else {
            intValue = (int) diff;
        }

        return ascending ? intValue : -intValue; // Note: ascending is used more often, more efficient to negate for descending
    }



    /////////////////////////////////////
    // Sort with pre-computed sort keys //
    /////////////////////////////////////

    /**
     * Sorts the given files in place, in the same order as {@link java.util.Arrays#sort(Object[], Comparator)} with
     * this comparator would. The sort keys (name, size, date...) are read once per file instead of once per
     * comparison, which makes sorting large folders several times faster. The sort is stable.
     */
    public void sort(AbstractFile[] files) {
        int n = files.length;
        if (n < 2) {
            return;
        }
        int[] indexes = new int[n];
        for (int i = 0; i < n; i++) {
            indexes[i] = i;
        }
        sortIndexes(files, indexes);
        AbstractFile[] copy = files.clone();
        for (int i = 0; i < n; i++) {
            files[i] = copy[indexes[i]];
        }
    }

    /**
     * Sorts an array of indexes into <code>files</code> in place by the files they refer to, with the same order as
     * {@link #compare(AbstractFile, AbstractFile)}. The sort is stable and close to linear for already sorted input.
     */
    public void sortIndexes(AbstractFile[] files, int[] indexes) {
        sortIndexes(files, indexes, true);
    }

    /** @param allowParallel false to always sort on the calling thread only (tests) */
    void sortIndexes(AbstractFile[] files, int[] indexes, boolean allowParallel) {
        if (indexes.length < 2) {
            return;
        }
        Keys keys = new Keys(files);
        int n = indexes.length;
        int[] tmp = new int[n];
        if (allowParallel && n >= PARALLEL_SORT_THRESHOLD && ForkJoinPool.getCommonPoolParallelism() > 1) {
            // Large folders: sort the halves on several cores (the keys are only read while sorting)
            ForkJoinPool.commonPool().invoke(keys.new SortTask(indexes, tmp, 0, n));
        } else {
            keys.mergeSort(indexes, tmp, 0, n);
        }
    }

    /** Ranges larger than this are sorted in parallel */
    private static final int PARALLEL_SORT_THRESHOLD = 20_000;

    /** Sort keys of a file array, fetched once per file; compare() mirrors {@link FileComparator#compare}. */
    private final class Keys {
        private final boolean[] dir;
        private final boolean[] quickSearchMatch;
        private final String[] name;
        private final int[] nameDigit;
        private final long[] number;
        private final String[] text;
        private final int[] textDigit;

        Keys(AbstractFile[] files) {
            int n = files.length;
            boolean needDir = directoriesFirst || criterion == SIZE_CRITERION;
            dir = needDir ? new boolean[n] : null;
            quickSearchMatch = quickSearch != null ? new boolean[n] : null;
            name = new String[n];
            nameDigit = new int[n];
            boolean numeric = criterion == SIZE_CRITERION || criterion == DATE_CRITERION || criterion == PERMISSIONS_CRITERION;
            boolean textual = criterion == EXTENSION_CRITERION || criterion == OWNER_CRITERION || criterion == GROUP_CRITERION;
            number = numeric ? new long[n] : null;
            text = textual ? new String[n] : null;
            textDigit = textual ? new int[n] : null;
            for (int i = 0; i < n; i++) {
                AbstractFile f = files[i];
                if (f == null) {
                    continue;
                }
                boolean isDir = needDir && f.isDirectory();
                if (needDir) {
                    dir[i] = isDir;
                }
                if (quickSearchMatch != null) {
                    quickSearchMatch[i] = quickSearch.matches(f);
                }
                String fileName = f.getName();
                name[i] = fileName;
                nameDigit[i] = firstDigitPos(fileName);
                if (numeric) {
                    if (criterion == SIZE_CRITERION) {
                        // Directories have a size of 0, unavailable size (-1) is considered as enormous
                        long size = isDir ? 0 : f.getSize();
                        number[i] = size == -1 ? Long.MAX_VALUE : size;
                    } else if (criterion == DATE_CRITERION) {
                        number[i] = f.getLastModifiedDate();
                    } else {
                        number[i] = f.getPermissions().getIntValue();
                    }
                } else if (textual) {
                    // The extension is derived from the name here (what getExtension() does) rather than asked to the
                    // file: CachedFile would keep one more String per file of the folder for the rest of its life
                    String value = criterion == EXTENSION_CRITERION ? AbstractFile.getExtension(fileName)
                            : criterion == OWNER_CRITERION ? f.getOwner() : f.getGroup();
                    text[i] = value;
                    textDigit[i] = firstDigitPos(value);
                }
            }
        }

        private int compareNames(int i1, int i2, boolean ignoreCase) {
            return compareStrings(name[i1], nameDigit[i1], name[i2], nameDigit[i2], ignoreCase);
        }

        int compare(int i1, int i2) {
            if (i1 == i2) {
                return 0;
            }
            if (quickSearchMatch != null) {
                boolean m1 = quickSearchMatch[i1];
                boolean m2 = quickSearchMatch[i2];
                if (m1 && !m2) {
                    return -1;
                } else if (m2 && !m1) {
                    return 1;
                }
            }
            long diff;
            if (directoriesFirst) {
                boolean is1Directory = dir[i1];
                boolean is2Directory = dir[i2];
                if (is1Directory && !is2Directory) {
                    return -1;
                } else if (is2Directory && !is1Directory) {
                    return 1;
                }
                if (foldersAlwaysAlphabetical && is1Directory) {
                    diff = compareNames(i1, i2, true);
                    if (diff == 0) {
                        diff = compareNames(i1, i2, false);
                    }
                    return (int) diff;
                }
            }
            if (number != null) {
                diff = number[i1] - number[i2];
            } else if (text != null) {
                String s1 = text[i1];
                String s2 = text[i2];
                if (s1 == null || s2 == null) {
                    diff = s1 == null ? (s2 == null ? 0 : -1) : 1;
                } else {
                    diff = compareStrings(s1, textDigit[i1], s2, textDigit[i2], true);
                }
            } else {
                diff = compareNames(i1, i2, true);
                if (diff == 0) {
                    diff = compareNames(i1, i2, false);
                }
            }
            if (criterion != NAME_CRITERION && diff == 0) {
                diff = compareNames(i1, i2, true);
            }
            int intValue;
            if (diff > Integer.MAX_VALUE) {
                intValue = Integer.MAX_VALUE;
            } else if (diff < Integer.MIN_VALUE + 1) {
                intValue = Integer.MIN_VALUE + 1;
            } else {
                intValue = (int) diff;
            }
            return ascending ? intValue : -intValue;
        }

        /** Stable merge sort of a[lo, hi), using tmp[lo, hi) as scratch space. */
        void mergeSort(int[] a, int[] tmp, int lo, int hi) {
            if (hi - lo <= 12) {
                for (int i = lo + 1; i < hi; i++) {
                    int v = a[i];
                    int j = i - 1;
                    while (j >= lo && compare(a[j], v) > 0) {
                        a[j + 1] = a[j];
                        j--;
                    }
                    a[j + 1] = v;
                }
                return;
            }
            int mid = (lo + hi) >>> 1;
            mergeSort(a, tmp, lo, mid);
            mergeSort(a, tmp, mid, hi);
            merge(a, tmp, lo, mid, hi);
        }

        /** Merges the sorted ranges a[lo, mid) and a[mid, hi) */
        void merge(int[] a, int[] tmp, int lo, int mid, int hi) {
            if (compare(a[mid - 1], a[mid]) <= 0) {
                return;     // halves are already in order: nothing to merge (keeps sorted input linear)
            }
            System.arraycopy(a, lo, tmp, lo, mid - lo);
            int i = lo, j = mid, k = lo;
            while (i < mid && j < hi) {
                a[k++] = compare(a[j], tmp[i]) < 0 ? a[j++] : tmp[i++];
            }
            while (i < mid) {
                a[k++] = tmp[i++];
            }
        }

        /** Parallel version of {@link #mergeSort}, the two halves of large ranges are sorted concurrently. */
        final class SortTask extends RecursiveAction {
            private final int[] a, tmp;
            private final int lo, hi;

            SortTask(int[] a, int[] tmp, int lo, int hi) {
                this.a = a;
                this.tmp = tmp;
                this.lo = lo;
                this.hi = hi;
            }

            @Override
            protected void compute() {
                if (hi - lo <= PARALLEL_SORT_THRESHOLD / 2) {
                    mergeSort(a, tmp, lo, hi);
                    return;
                }
                int mid = (lo + hi) >>> 1;
                invokeAll(new SortTask(a, tmp, lo, mid), new SortTask(a, tmp, mid, hi));
                merge(a, tmp, lo, mid, hi);
            }
        }
    }

    /**
     * Returns true if the given comparator orders files exactly like this one: same criterion, order, folder
     * options and quick search. (Unlike {@link #equals(Object)} which only looks at the criterion and order.)
     */
    public boolean sortsLike(FileComparator other) {
        return other != null && criterion == other.criterion && ascending == other.ascending
                && directoriesFirst == other.directoriesFirst && foldersAlwaysAlphabetical == other.foldersAlwaysAlphabetical
                && quickSearch == other.quickSearch;
    }

    /**
     * Returns true only if the given object is a FileComparator using the same criterion and ascending/descending order.
     */
    public boolean equals(Object o) {
        if (! (o instanceof FileComparator fc)) {
            return false;
        }

        return criterion == fc.criterion && ascending == fc.ascending;
    }
}
