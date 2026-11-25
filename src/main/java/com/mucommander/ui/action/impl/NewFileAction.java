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

package com.mucommander.ui.action.impl;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.FileOperation;
import com.mucommander.ui.action.AbstractActionDescriptor;
import com.mucommander.ui.action.ActionCategory;
import com.mucommander.ui.action.ActionDescriptor;
import com.mucommander.ui.action.TcAction;
import com.mucommander.ui.dialog.file.MakeDirectoryFileDialog;
import com.mucommander.ui.main.MainFrame;

import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * This action brings up the 'Make file' dialog which allows to create a new file in the currently active folder.
 *
 * @author Maxence Bernard
 */
/**
 * New File (Shift+F4): asks for a name, creates an empty file in the current folder and opens it with the same
 * editor as F4. If a file with that name already exists it is opened as is, never overwritten.
 */
public class NewFileAction extends ParentFolderAction {

    private NewFileAction(MainFrame mainFrame, Map<String, Object> properties) {
        super(mainFrame, properties);
    }

    protected void toggleEnabledState() {
        AbstractFile firstFile = mainFrame.getActiveTable().getFileTableModel().getFileAt(0);
        // Same rule as MkfileAction: enabled unless the folder is known not to support writing files
        setEnabled(firstFile == null || firstFile.isFileOperationSupported(FileOperation.WRITE_FILE));
    }

    @Override
    public void performAction() {
        new MakeDirectoryFileDialog(mainFrame, true, true).showDialog();
    }

    @Override
    public ActionDescriptor getDescriptor() {
        return new Descriptor();
    }


    public static final class Descriptor extends AbstractActionDescriptor {
        public static final String ACTION_ID = "NewFile";

        public String getId() {
            return ACTION_ID;
        }

        public ActionCategory getCategory() {
            return ActionCategory.FILES;
        }

        public KeyStroke getDefaultAltKeyStroke() {
            return null;
        }

        public KeyStroke getDefaultKeyStroke() {
            return KeyStroke.getKeyStroke(KeyEvent.VK_F4, KeyEvent.SHIFT_DOWN_MASK);
        }

        public TcAction createAction(MainFrame mainFrame, Map<String,Object> properties) {
            return new NewFileAction(mainFrame, properties);
        }
    }
}
