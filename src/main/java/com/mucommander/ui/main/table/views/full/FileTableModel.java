/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2012 Maxence Bernard
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mucommander.ui.main.table.views.full;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.ui.main.table.FileTable;
import com.mucommander.utils.text.CustomDateFormat;
import com.mucommander.utils.text.SizeFormat;
import com.mucommander.ui.main.table.Column;
import com.mucommander.ui.main.table.views.BaseFileTableModel;
import org.jetbrains.annotations.NotNull;


/**
 * This class maps table cells onto file attributes.
 *
 * @author Maxence Bernard
 */
public class FileTableModel extends BaseFileTableModel {

    /** Cell values cache */
    private Object[][] cellValuesCache;

    private int columnsVisibilityMask;

    /** Number of cached values per row: every column but the icon */
    private static final int CACHED_COLUMNS = Column.values().length - 1;


    /**
     * Creates a new FileTableModel, without any initial current folder.
     */
    public FileTableModel() {
        super();
        cellValuesCache = new Object[0][];
    }


    @Override
    public synchronized void setupFromModel(BaseFileTableModel model) {
        super.setupFromModel(model);
        initCellValuesCache();
        fillCellCache(null);
    }

    /**
     * Init and fill cell cache to speed up table even more
     */
    @Override
    protected void initCellValuesCache() {
        // Rows are allocated when first displayed (see #fillOneCellCache): a large folder is mostly never scrolled
        // through, one array per file would be memory spent for nothing
        this.cellValuesCache = new Object[getRowCount()][];
    }


    /**
     * Retrieves all cell values and stores them in an array for fast access.
     */
    @Override
    public synchronized void fillCellCache(FileTable fileTable) {
        int len = cellValuesCache.length;
        if (len == 0) {
            return;
        }

        columnsVisibilityMask = calcColumnVisibilityMask(fileTable);
        // Forget the cell values of every file, they are computed again when displayed
        java.util.Arrays.fill(cellValuesCache, null);
        // Special '..' file
        if (parent != null) {
            Object[] cell = cellValuesCache[0] = new Object[CACHED_COLUMNS];
            cell[Column.NAME.ordinal()-1] = "..";
            cell[Column.SIZE.ordinal()-1] = DIRECTORY_SIZE_STRING;
            currentFolderDateSnapshot = currentFolder.getLastModifiedDate();
            cell[Column.DATE.ordinal()-1] =	CustomDateFormat.format(currentFolderDateSnapshot);
            // Don't display parent's permissions as they can have a different format from the folder contents
            // (e.g. for archives) and this looks weird
            cell[Column.PERMISSIONS.ordinal()-1] = "";
            cell[Column.OWNER.ordinal()-1] = "";
            cell[Column.GROUP.ordinal()-1] = "";
        }
    }

    private static int calcColumnVisibilityMask(FileTable fileTable) {
        if (fileTable == null) {
            return 0xffff;
        }
        int mask = 0;
        for (Column column : Column.values()) {
            if (fileTable.isColumnVisible(column)) {
                mask |= 1 << column.ordinal();
            }
        }
        return mask;
    }

    private Object[] fillOneCellCache(int cellIndex, int fileIndex) {
        AbstractFile file = getCachedFileAt(fileIndex);
        Object[] cell = cellValuesCache[cellIndex];
        if (cell == null) {
            cell = cellValuesCache[cellIndex] = new Object[CACHED_COLUMNS];
        }
        cell[Column.NAME.ordinal()-1] = file.getName();
        if (isColumnVisible(Column.SIZE)) {
            cell[Column.SIZE.ordinal() - 1] = getSizeValue(file);
        }
        if (isColumnVisible(Column.DATE)) {
            cell[Column.DATE.ordinal() - 1] = CustomDateFormat.format(file.getLastModifiedDate());
        }
        if (isColumnVisible(Column.PERMISSIONS)) {
            cell[Column.PERMISSIONS.ordinal() - 1] = file.getPermissionsString();
        }
        if (isColumnVisible(Column.OWNER) && file.canGetOwner()) {
            cell[Column.OWNER.ordinal() - 1] = file.getOwner();
        }
        if (isColumnVisible(Column.GROUP) && file.canGetGroup()) {
            cell[Column.GROUP.ordinal() - 1] = file.getGroup();
        }
        return cell;
    }

    private boolean isColumnVisible(Column column) {
        return (columnsVisibilityMask & (1 << column.ordinal())) != 0;
    }

    @NotNull
    private String getSizeValue(AbstractFile file) {
        if (file.isDirectory()) {
            if (hasCalculatedDirectories) {
                Long dirSize;
                synchronized (directorySizes) {
                    dirSize = directorySizes.get(file);
                }
                if (dirSize != null) {
                    return SizeFormat.format(dirSize, sizeFormat);
                } else {
                    synchronized (calculateSizeQueue) {
                        return calculateSizeQueue.contains(file) ? QUEUED_DIRECTORY_SIZE_STRING : DIRECTORY_SIZE_STRING;
                    }
                }
            } else {
                return DIRECTORY_SIZE_STRING;
            }
        } else {
            return SizeFormat.format(file.getSize(), sizeFormat);
        }
    }


    //////////////////////////////////////////
    // Overridden AbstractTableModel methods //
    //////////////////////////////////////////

    @Override
    public int getColumnCount() {
        return Column.values().length; // icon, name, size, date, permissions, owner, group
    }

    @Override
    public String getColumnName(int columnIndex) {
        return Column.valueOf(columnIndex).getLabel();
    }

    /**
     * Returns the total number of rows, including the special parent folder file '..', if there is one.
     */
    @Override
    public synchronized int getRowCount() {
        return fileArrayIndex.length + (parent == null ? 0 : 1);
    }


    @Override
    public synchronized Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= cellValuesCache.length || columnIndex >= CACHED_COLUMNS) {
            return null;
        }
        // Need to check that row index is not larger than actual number of rows
        // because if table has just been changed (rows have been removed),
        // JTable may have an old row count value and may try to repaint rows that are out of bounds.
        if (rowIndex >= getRowCount()) {
            // Returning null will have JTable ignore this row
            return null;
        }

        // Icon/extension column, return a null value
        Column column = Column.valueOf(columnIndex);
        if (column == Column.EXTENSION) {
            return null;
        }
		
        // Decrement column index for cellValuesCache array
        columnIndex--;
        // Handle special '..' file
        if (rowIndex == 0 && parent != null) {
            return cellValuesCache[0][columnIndex];
//            Object result = cellValuesCache[0][columnIndex];
//            if (result == null) {
//                result = fillOneCellCache(0, 0)[columnIndex];
//            }
//            return result;
        }
        int fileIndex = parent == null ? rowIndex : rowIndex-1;
        int index = fileArrayIndex[fileIndex];
        if (parent != null) {
            index++;
        }
        Object[] cell = cellValuesCache[index];
        Object result = cell == null ? null : cell[columnIndex];
        if (result == null) {
            result = fillOneCellCache(index, parent != null ? fileIndex + 1 : fileIndex)[columnIndex];
        }
        return result;
    }

	
    /**
     * Returns <code>true</code> if name column has temporarily be made editable by FileTable
     * and given row doesn't correspond to parent file '..', <code>false</code> otherwise.
     */
    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        // Name column can temporarily be made editable by FileTable
        // but parent file '..' name should never be editable
        return Column.valueOf(columnIndex) == Column.NAME && (parent == null || rowIndex != 0) && nameColumnEditable;
    }

    public String getFileNameAt(int index) {
        return (index == 0 && hasParentFolder()) ? ".." : getFileAt(index).getName();
    }


    public int getFileIndexAt(int row, int column) {
        return row;
    }

    @Override
    public int getFileRow(int index) {
        return index;
    }


}
