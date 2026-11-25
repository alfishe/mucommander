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
package com.mucommander.ui.dialog.auth

import com.mucommander.auth.Account
import com.mucommander.auth.AccountStore
import com.mucommander.auth.Binding
import com.mucommander.commons.file.FileProtocols
import com.mucommander.commons.file.FileURL
import com.mucommander.ui.action.ActionProperties
import com.mucommander.ui.action.impl.EditCredentialsAction
import com.mucommander.ui.dialog.DialogToolkit
import com.mucommander.ui.dialog.FocusDialog
import com.mucommander.ui.layout.XAlignedComponentPanel
import com.mucommander.ui.main.MainFrame
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.net.MalformedURLException
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Credentials manager: saved accounts (name, login, password) and the servers bound to them.
 *
 * Opening a location covered by a server binding logs in with its account without asking; one account can be bound
 * to any number of servers. Edits are applied when another entry is selected, the tab is switched or the dialog is
 * closed.
 */
class CredentialsManagerDialog(private val mainFrame: MainFrame) : FocusDialog(
    mainFrame.jFrame,
    ActionProperties.getActionLabel(EditCredentialsAction.Descriptor.ACTION_ID),
    mainFrame.jFrame
), ActionListener {

    // Accounts tab
    private val accountModel = DefaultListModel<Account>()
    private val accountList = JList(accountModel)
    private val edtAccountName = JTextField()
    private val edtLogin = JTextField()
    private val edtPassword = JPasswordField()
    private val cbShowPassword = JCheckBox(i18n("credentials_manager.show_password"))
    private val lblUsedBy = JLabel(" ")
    private val btnNewAccount = JButton(i18n("credentials_manager.new"))
    private val btnDeleteAccount = JButton(i18n("credentials_manager.delete"))
    private var editedAccount: Account? = null
    private var accountDirty = false
    private var passwordDirty = false

    // Servers tab
    private val bindingModel = DefaultListModel<Binding>()
    private val bindingList = JList(bindingModel)
    private val edtLocation = JTextField()
    private val cbAccount = JComboBox<Account>()
    private val btnNewBinding = JButton(i18n("credentials_manager.new"))
    private val btnDeleteBinding = JButton(i18n("credentials_manager.delete"))
    private val btnGoTo = JButton(i18n("go_to"))
    private var editedBinding: Binding? = null
    private var bindingDirty = false

    private val tabs = JTabbedPane()
    private val btnClose = JButton(i18n("close"))

    /** Suppresses dirty tracking while fields are filled programmatically */
    private var loading = false

    private val echoChar = edtPassword.echoChar

    init {
        contentPane.layout = BorderLayout()

        val storage = JLabel(
            i18n(
                if (AccountStore.currentStorage() == AccountStore.STORAGE_KEYRING) "credentials_manager.storage_keyring"
                else "credentials_manager.storage_file"
            )
        ).apply { border = BorderFactory.createEmptyBorder(0, 2, 8, 2) }
        contentPane.add(storage, BorderLayout.NORTH)

        tabs.addTab(i18n("credentials_manager.accounts"), buildAccountsTab())
        tabs.addTab(i18n("credentials_manager.servers"), buildServersTab())
        tabs.addChangeListener {
            applyPendingEdits()
            reloadBindings(editedBinding)
            refreshAccountCombo()
        }
        contentPane.add(tabs, BorderLayout.CENTER)

        contentPane.add(DialogToolkit.createOKPanel(btnClose, getRootPane(), this), BorderLayout.SOUTH)

        reloadAccounts(null)
        reloadBindings(null)
        refreshAccountCombo()
        // Start on the servers tab when there are accounts already: that's where most edits happen
        if (AccountStore.getBindings().isNotEmpty()) {
            tabs.selectedIndex = 1
        }

        setInitialFocusComponent(if (tabs.selectedIndex == 0) accountList else bindingList)
        minimumSize = Dimension(700, 380)
        showDialog()
    }

    ////////////////////
    // Accounts       //
    ////////////////////

    private fun buildAccountsTab(): JPanel {
        accountList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        accountList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                applyAccount()
                showAccount(accountList.selectedValue)
            }
        }
        val form = XAlignedComponentPanel(10).apply {
            addRow(i18n("name") + ":", edtAccountName, 5)
            addRow(i18n("login") + ":", edtLogin, 5)
            addRow(i18n("password") + ":", edtPassword, 2)
            addRow("", cbShowPassword, 10)
            addRow("", lblUsedBy, 5)
        }
        trackDirty(edtAccountName) { accountDirty = true }
        trackDirty(edtLogin) { accountDirty = true }
        trackDirty(edtPassword) { accountDirty = true; passwordDirty = true }
        cbShowPassword.addActionListener {
            edtPassword.echoChar = if (cbShowPassword.isSelected) 0.toChar() else echoChar
        }
        btnNewAccount.addActionListener(this)
        btnDeleteAccount.addActionListener(this)
        return masterDetail(accountList, form, btnNewAccount, btnDeleteAccount)
    }

    private fun reloadAccounts(select: Account?) {
        loading = true
        accountModel.clear()
        AccountStore.getAccounts().forEach { accountModel.addElement(it) }
        loading = false
        if (select != null) {
            accountList.setSelectedValue(select, true)
        } else if (!accountModel.isEmpty) {
            accountList.selectedIndex = 0
        } else {
            showAccount(null)
        }
    }

    private fun showAccount(account: Account?) {
        loading = true
        editedAccount = account
        accountDirty = false
        passwordDirty = false
        val enabled = account != null
        listOf(edtAccountName, edtLogin, edtPassword, cbShowPassword).forEach { it.isEnabled = enabled }
        btnDeleteAccount.isEnabled = enabled
        edtAccountName.text = account?.name ?: ""
        edtLogin.text = account?.login ?: ""
        val password = account?.let { AccountStore.getPassword(it) }
        edtPassword.text = password ?: ""
        edtPassword.toolTipText = if (account != null && password == null) i18n("credentials_manager.password_unavailable") else null
        lblUsedBy.text = if (account == null) " " else {
            val bound = AccountStore.getBindings(account)
            if (bound.isEmpty()) i18n("credentials_manager.not_used")
            else i18n("credentials_manager.used_by", bound.joinToString(", ") { it.displayUrl })
        }
        loading = false
    }

    /** Saves the edited account if it was changed */
    private fun applyAccount() {
        val account = editedAccount ?: return
        if (!accountDirty) {
            return
        }
        val login = edtLogin.text.trim()
        if (login.isEmpty()) {
            return  // an account without a login is useless, keep the previous values
        }
        AccountStore.updateAccount(
            account, edtAccountName.text.trim(), login,
            if (passwordDirty) String(edtPassword.password) else null
        )
        accountDirty = false
        passwordDirty = false
        accountList.repaint()
    }

    private fun newAccount() {
        applyAccount()
        val account = AccountStore.addAccount(i18n("credentials_manager.new_account"), System.getProperty("user.name"), "")
        reloadAccounts(account)
        edtAccountName.selectAll()
        edtAccountName.requestFocusInWindow()
    }

    private fun deleteAccount() {
        val account = editedAccount ?: return
        val bound = AccountStore.getBindings(account).size
        val answer = JOptionPane.showConfirmDialog(
            this, i18n("credentials_manager.delete_account_confirm", account.name, bound.toString()),
            title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (answer != JOptionPane.OK_OPTION) {
            return
        }
        editedAccount = null
        AccountStore.removeAccount(account)
        reloadAccounts(null)
    }

    ////////////////////
    // Servers        //
    ////////////////////

    private fun buildServersTab(): JPanel {
        bindingList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        bindingList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val b = value as Binding
                val text = b.displayUrl + "   →   " + (AccountStore.getAccount(b.accountId)?.name ?: "?")
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        bindingList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                applyBinding()
                showBinding(bindingList.selectedValue)
            }
        }
        val form = XAlignedComponentPanel(10).apply {
            addRow(i18n("location") + ":", edtLocation, 5)
            addRow(i18n("credentials_manager.account") + ":", cbAccount, 10)
            addRow("", JLabel("<html><div style='width:260px'>" + i18n("credentials_manager.binding_hint") + "</div></html>"), 5)
        }
        trackDirty(edtLocation) { bindingDirty = true }
        cbAccount.addActionListener { if (!loading) bindingDirty = true }
        btnNewBinding.addActionListener(this)
        btnDeleteBinding.addActionListener(this)
        btnGoTo.addActionListener(this)
        return masterDetail(bindingList, form, btnNewBinding, btnDeleteBinding, btnGoTo)
    }

    private fun refreshAccountCombo() {
        loading = true
        val selected = cbAccount.selectedItem
        cbAccount.removeAllItems()
        AccountStore.getAccounts().forEach { cbAccount.addItem(it) }
        editedBinding?.let { cbAccount.selectedItem = AccountStore.getAccount(it.accountId) } ?: run {
            cbAccount.selectedItem = selected
        }
        btnNewBinding.isEnabled = cbAccount.itemCount > 0
        btnNewBinding.toolTipText = if (cbAccount.itemCount > 0) null else i18n("credentials_manager.no_account_first")
        loading = false
    }

    private fun reloadBindings(select: Binding?) {
        loading = true
        bindingModel.clear()
        AccountStore.getBindings().forEach { bindingModel.addElement(it) }
        loading = false
        if (select != null && bindingModel.contains(select)) {
            bindingList.setSelectedValue(select, true)
        } else if (!bindingModel.isEmpty) {
            bindingList.selectedIndex = 0
        } else {
            showBinding(null)
        }
    }

    private fun showBinding(binding: Binding?) {
        loading = true
        editedBinding = binding
        bindingDirty = false
        val enabled = binding != null
        listOf(edtLocation, cbAccount, btnDeleteBinding, btnGoTo).forEach { it.isEnabled = enabled }
        edtLocation.text = binding?.displayUrl ?: ""
        cbAccount.selectedItem = binding?.let { AccountStore.getAccount(it.accountId) }
        loading = false
    }

    /** Saves the edited binding if it was changed; returns false if the location is invalid */
    private fun applyBinding(): Boolean {
        val binding = editedBinding ?: return true
        if (!bindingDirty) {
            return true
        }
        val account = cbAccount.selectedItem as? Account ?: return true
        val url = parseLocation(edtLocation.text) ?: return false
        AccountStore.updateBinding(binding, url, account)
        bindingDirty = false
        bindingList.repaint()
        return true
    }

    private fun newBinding() {
        if (!applyBinding()) {
            return
        }
        val account = (cbAccount.selectedItem as? Account) ?: AccountStore.getAccounts().firstOrNull() ?: return
        // Default to the active panel's location when it's a remote one
        val current = mainFrame.activePanel.currentFolder?.url
        val url = if (current != null && current.scheme != FileProtocols.FILE) current.realm
        else FileURL.getFileURL("smb://server/")
        val binding = AccountStore.bind(url, account)
        reloadBindings(binding)
        edtLocation.selectAll()
        edtLocation.requestFocusInWindow()
    }

    private fun deleteBinding() {
        val binding = editedBinding ?: return
        editedBinding = null
        AccountStore.removeBinding(binding)
        reloadBindings(null)
    }

    private fun goTo() {
        if (!applyBinding()) {
            return
        }
        val binding = editedBinding ?: return
        dispose()
        mainFrame.activePanel.tryChangeCurrentFolder(binding.url)
    }

    private fun parseLocation(text: String): FileURL? {
        val trimmed = text.trim()
        return try {
            val url = FileURL.getFileURL(trimmed)
            if (url.scheme == FileProtocols.FILE || url.host.isNullOrEmpty()) throw MalformedURLException(trimmed)
            url
        } catch (_: MalformedURLException) {
            JOptionPane.showMessageDialog(
                this, i18n("credentials_manager.invalid_location", trimmed), title, JOptionPane.ERROR_MESSAGE
            )
            edtLocation.requestFocusInWindow()
            null
        }
    }

    ////////////////////
    // Common         //
    ////////////////////

    private fun applyPendingEdits() {
        applyAccount()
        applyBinding()
    }

    override fun actionPerformed(e: ActionEvent) {
        when (e.source) {
            btnClose -> {
                if (applyBinding()) {
                    applyAccount()
                    dispose()
                }
            }
            btnNewAccount -> newAccount()
            btnDeleteAccount -> deleteAccount()
            btnNewBinding -> newBinding()
            btnDeleteBinding -> deleteBinding()
            btnGoTo -> goTo()
        }
    }

    override fun cancel() {
        // Escape / window close: keep the edits like the Close button does
        applyPendingEdits()
        super.cancel()
    }

    private fun trackDirty(field: JTextField, onChange: () -> Unit) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                if (!loading) onChange()
            }
        })
    }

    private fun masterDetail(list: JList<*>, form: JPanel, vararg buttons: JButton): JPanel {
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply { buttons.forEach { add(it) } }
        val left = JPanel(BorderLayout(0, 5)).apply {
            add(JScrollPane(list).apply { preferredSize = Dimension(340, 220) }, BorderLayout.CENTER)
            add(buttonsPanel, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(10, 0)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(left, BorderLayout.WEST)
            add(JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }, BorderLayout.CENTER)
        }
    }
}
