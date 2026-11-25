/*
 * This file is part of trolCommander, http://www.trolsoft.ru/en/soft/trolcommander
 *
 * trolCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * trolCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.ui.main

import com.mucommander.bookmark.Bookmark
import com.mucommander.bookmark.BookmarkManager
import com.mucommander.utils.text.Translator
import com.mucommander.ui.button.PopupButton
import com.mucommander.ui.dialog.bookmark.AddBookmarkDialog
import com.mucommander.ui.dialog.bookmark.EditBookmarksDialog
import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Total Commander style "Directory hotlist" button shown at the right edge of a panel's location line (`*`).
 *
 * The popup lets the user add the panel's current folder (local or network) to the hotlist, remove it again, jump to any hotlist entry and open the editor to add/edit/delete entries.
 * The hotlist is backed by the regular bookmarks, so groups (bookmarks without a location) become sub-menus and
 * separators are honoured.
 */
class HotlistButton(private val folderPanel: FolderPanel) : PopupButton() {

    init {
        text = "*"
        toolTipText = Translator.get("hotlist.tooltip")
        margin = Insets(0, 4, 0, 4)
        // Right-align the menu under the button so it never runs off the right edge of the window
        setPopupMenuLocation(BOTTOM_LEFT_ORIENTED)
    }

    override fun getPopupMenu(): JPopupMenu {
        val popupMenu = JPopupMenu()
        val mainFrame = folderPanel.mainFrame
        val currentFolder = folderPanel.currentFolder
        val currentPath = currentFolder?.absolutePath
        val bookmarks = BookmarkManager.getBookmarks()
        val currentEntry = bookmarks.firstOrNull { it.location.isNotEmpty() && sameLocation(it.location, currentPath) }

        // Add / remove the folder shown in *this* panel (not necessarily the active one)
        if (currentEntry == null) {
            popupMenu.add(menuItem(Translator.get("hotlist.add_current")) {
                if (currentFolder != null) {
                    AddBookmarkDialog(mainFrame, currentFolder)
                }
            }).isEnabled = currentFolder != null
        } else {
            popupMenu.add(menuItem(Translator.get("hotlist.remove_current", currentEntry.name)) {
                BookmarkManager.removeBookmark(currentEntry)
                saveBookmarks()
            })
        }
        popupMenu.addSeparator()

        if (bookmarks.none { it.location.isNotEmpty() }) {
            popupMenu.add(Translator.get("bookmarks_menu.no_bookmark")).isEnabled = false
        } else {
            addGroup(popupMenu, bookmarks, null, currentPath)
        }
        popupMenu.addSeparator()

        popupMenu.add(menuItem(Translator.get("hotlist.configure")) { EditBookmarksDialog(mainFrame) })
        return popupMenu
    }

    private fun addGroup(parentMenu: JComponent, bookmarks: List<Bookmark>, parent: String?, currentPath: String?) {
        for (b in bookmarks) {
            if (b.parent != parent) {
                continue
            }
            when {
                b.location.isEmpty() && b.name == BookmarkManager.BOOKMARKS_SEPARATOR -> addSeparator(parentMenu)
                b.location.isEmpty() -> {
                    val groupMenu = JMenu(b.name)
                    parentMenu.add(groupMenu)
                    addGroup(groupMenu, bookmarks, b.name, currentPath)
                    if (groupMenu.itemCount == 0) {
                        groupMenu.add(Translator.get("bookmarks_menu.no_bookmark")).isEnabled = false
                    }
                }
                else -> {
                    val item = menuItem(b.name) { open(b) }
                    item.toolTipText = b.location
                    if (sameLocation(b.location, currentPath)) {
                        item.font = item.font.deriveFont(Font.BOLD)
                    }
                    parentMenu.add(item)
                }
            }
        }
    }

    /** Opens a hotlist entry in this panel; authentication is handled by the regular location change flow. */
    private fun open(b: Bookmark) {
        folderPanel.tryChangeCurrentFolder(b.location)
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(HotlistButton::class.java)

        private fun menuItem(text: String, onClick: () -> Unit) = JMenuItem(text).apply {
            addActionListener { onClick() }
        }

        private fun addSeparator(menu: JComponent) {
            when (menu) {
                is JPopupMenu -> menu.addSeparator()
                is JMenu -> menu.addSeparator()
            }
        }

        private fun sameLocation(a: String, b: String?): Boolean =
            b != null && a.trimEnd('/', '\\') == b.trimEnd('/', '\\')

        private fun saveBookmarks() {
            try {
                BookmarkManager.writeBookmarks(false)
            } catch (e: Exception) {
                LOGGER.warn("Failed to save bookmarks", e)
            }
        }
    }
}
