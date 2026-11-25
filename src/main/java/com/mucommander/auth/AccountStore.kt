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
package com.mucommander.auth

import com.mucommander.bookmark.XORCipher
import com.mucommander.commons.file.Credentials
import com.mucommander.commons.file.FileURL
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A saved login: name shown to the user, login and password. One account can be bound to any number of servers.
 */
class Account internal constructor(
    val id: String,
    name: String,
    login: String,
    /** Where the password lives: [AccountStore.STORAGE_KEYRING] or [AccountStore.STORAGE_FILE] */
    storage: String
) {
    var name: String = name
        internal set
    var login: String = login
        internal set
    var storage: String = storage
        internal set

    /** Password loaded from the file or fetched from the keyring, null if not known (yet) */
    @Volatile
    internal var password: String? = null

    override fun toString() = if (name == login) name else "$name ($login)"
}

/**
 * Binds a location (scheme, host, port and a path prefix) to an [Account]: every location under [url] is opened with
 * that account without asking. The URL may carry connection properties which are applied when authenticating.
 */
class Binding internal constructor(url: FileURL, accountId: String) {
    var url: FileURL = url
        internal set
    var accountId: String = accountId
        internal set

    /** "smb://host:port/path/" without credentials, the form shown to the user */
    val displayUrl: String get() = url.toString(false)
}

/**
 * Central store of saved logins: [Account]s (login + password) and [Binding]s (location -> account).
 *
 * Accounts and bindings are saved in `accounts.xml` in the preferences folder. Passwords go to the desktop keyring
 * when it's available ([KeyringSecretStore]) and to the same file otherwise (weakly encrypted, file readable by the
 * owner only). A legacy `credentials.xml` is migrated on first load.
 */
object AccountStore {
    private val LOGGER = LoggerFactory.getLogger(AccountStore::class.java)

    const val STORAGE_KEYRING = "keyring"
    const val STORAGE_FILE = "file"

    private const val FILE_VERSION = "1"

    private val accounts = CopyOnWriteArrayList<Account>()
    private val bindings = CopyOnWriteArrayList<Binding>()

    private var file: File? = null

    @JvmStatic
    fun getAccounts(): List<Account> = accounts.toList()

    @JvmStatic
    fun getBindings(): List<Binding> = bindings.toList()

    @JvmStatic
    fun getAccount(id: String?): Account? = accounts.firstOrNull { it.id == id }

    @JvmStatic
    fun getBindings(account: Account): List<Binding> = bindings.filter { it.accountId == account.id }

    /** Where new/changed passwords are stored right now */
    @JvmStatic
    fun currentStorage(): String = if (KeyringSecretStore.isAvailable()) STORAGE_KEYRING else STORAGE_FILE

    ////////////////////
    // Passwords      //
    ////////////////////

    /**
     * Returns the password of an account, fetching it from the keyring if needed (may block while the keyring asks
     * to be unlocked, so don't call it on the event thread unless the user asked for it). Null if unavailable.
     */
    @JvmStatic
    fun getPassword(account: Account): String? {
        account.password?.let { return it }
        if (account.storage == STORAGE_KEYRING && KeyringSecretStore.isAvailable()) {
            val pw = KeyringSecretStore.get(account.id)
            account.password = pw
            return pw
        }
        return null
    }

    private fun storePassword(account: Account, password: String) {
        account.password = password
        if (KeyringSecretStore.isAvailable() && KeyringSecretStore.put(account.id, account.name, password)) {
            account.storage = STORAGE_KEYRING
        } else {
            account.storage = STORAGE_FILE
        }
    }

    ////////////////////
    // Editing        //
    ////////////////////

    @JvmStatic
    @Synchronized
    fun addAccount(name: String, login: String, password: String): Account {
        val account = Account(UUID.randomUUID().toString(), name.ifBlank { login }, login, STORAGE_FILE)
        storePassword(account, password)
        accounts.add(account)
        save()
        return account
    }

    /** Updates an account; a null password keeps the current one. */
    @JvmStatic
    @Synchronized
    fun updateAccount(account: Account, name: String, login: String, password: String?) {
        val renamed = account.name != name
        account.name = name.ifBlank { login }
        account.login = login
        val pw = password ?: if (renamed && account.storage == STORAGE_KEYRING) getPassword(account) else null
        if (pw != null) {
            storePassword(account, pw)  // also refreshes the keyring label after a rename
        }
        save()
    }

    /** Deletes an account, its password and all its bindings. */
    @JvmStatic
    @Synchronized
    fun removeAccount(account: Account) {
        accounts.remove(account)
        bindings.removeAll { it.accountId == account.id }
        if (account.storage == STORAGE_KEYRING && KeyringSecretStore.isAvailable()) {
            KeyringSecretStore.delete(account.id)
        }
        save()
    }

    /** Binds a location to an account, replacing an existing binding of the same location. */
    @JvmStatic
    @Synchronized
    fun bind(url: FileURL, account: Account): Binding {
        val clean = cleanUrl(url)
        val existing = bindings.firstOrNull { key(it.url) == key(clean) }
        val binding = if (existing != null) {
            existing.url = clean
            existing.accountId = account.id
            existing
        } else {
            Binding(clean, account.id).also { bindings.add(it) }
        }
        save()
        return binding
    }

    @JvmStatic
    @Synchronized
    fun updateBinding(binding: Binding, url: FileURL, account: Account) {
        binding.url = cleanUrl(url)
        binding.accountId = account.id
        // Two bindings for the same location would be ambiguous: keep the edited one
        bindings.removeAll { it !== binding && key(it.url) == key(binding.url) }
        save()
    }

    @JvmStatic
    @Synchronized
    fun removeBinding(binding: Binding) {
        bindings.remove(binding)
        save()
    }

    ////////////////////
    // Lookup         //
    ////////////////////

    /**
     * Returns the binding that covers the given location: same scheme, host and port, and a path that is a prefix of
     * the location's path (whole path segments). The most specific binding wins. Null if none.
     */
    @JvmStatic
    fun findBinding(location: FileURL): Binding? {
        val segments = segments(location.path)
        return bindings
            .filter { sameServer(it.url, location) }
            .map { it to segments(it.url.path) }
            .filter { (_, prefix) -> isPrefix(prefix, segments) }
            .maxByOrNull { (_, prefix) -> prefix.size }
            ?.first
    }

    /**
     * Credentials to log into the given location without asking: those of the covering binding's account, provided
     * its password is known. Null if there is no binding or the password can't be retrieved.
     */
    @JvmStatic
    fun autoLogin(location: FileURL): CredentialsMapping? {
        val binding = findBinding(location) ?: return null
        val account = getAccount(binding.accountId) ?: return null
        val password = getPassword(account) ?: return null
        return CredentialsMapping(Credentials(account.login, password), binding.url, true, account.id)
    }

    /**
     * All saved credentials for the server of the given location (any path), to pre-fill the authentication dialog.
     * Passwords that can't be retrieved are empty.
     */
    @JvmStatic
    fun matchingMappings(location: FileURL): List<CredentialsMapping> =
        bindings.filter { sameServer(it.url, location) }.mapNotNull { b ->
            getAccount(b.accountId)?.let { a ->
                CredentialsMapping(Credentials(a.login, getPassword(a) ?: ""), b.url, true, a.id)
            }
        }.distinctBy { it.accountId }

    /**
     * Remembers credentials that were just used successfully, called when the user ticked "save" in the
     * authentication dialog:
     *  1. an account explicitly picked in the dialog is used (its password updated if it was changed),
     *  2. else if a binding already covers the location with the same login, that account's password is updated,
     *  3. else an existing account with the same login and password is reused,
     *  4. else a new account is created.
     * The location is then bound to the account, unless a binding to it already covers the location.
     */
    @JvmStatic
    @Synchronized
    fun remember(mapping: CredentialsMapping) {
        val credentials = mapping.credentials
        if (credentials == null || credentials.isEmpty) {
            return
        }
        val realm = mapping.realm
        val covering = findBinding(realm)
        val coveringAccount = covering?.let { getAccount(it.accountId) }

        var account = getAccount(mapping.accountId)
        if (account == null && coveringAccount != null && coveringAccount.login.equals(credentials.login, true)) {
            account = coveringAccount
        }
        if (account == null) {
            account = accounts.firstOrNull { it.login == credentials.login && getPassword(it) == credentials.password }
        }
        var changed = false
        if (account == null) {
            val host = realm.host
            val name = if (host.isNullOrEmpty()) credentials.login else "${credentials.login}@$host"
            account = Account(UUID.randomUUID().toString(), name, credentials.login, STORAGE_FILE)
            accounts.add(account)
            storePassword(account, credentials.password)
            changed = true
        } else if (account.login != credentials.login || getPassword(account) != credentials.password) {
            account.login = credentials.login
            storePassword(account, credentials.password)
            changed = true
        }

        if (covering == null || covering.accountId != account.id) {
            bind(realm, account)    // saves
        } else if (changed) {
            save()
        }
    }

    ////////////////////
    // Persistence    //
    ////////////////////

    /**
     * Loads accounts from [file]. If it doesn't exist and [legacyFile] (old `credentials.xml`) does, the legacy
     * credentials are imported and the legacy file is renamed to `*.migrated`.
     */
    @JvmStatic
    @Synchronized
    fun load(file: File, legacyFile: File?) {
        this.file = file
        accounts.clear()
        bindings.clear()
        KeyringSecretStore.probeAsync()
        if (file.exists()) {
            read(file)
            LOGGER.debug("loaded {} accounts, {} bindings", accounts.size, bindings.size)
        } else if (legacyFile != null && legacyFile.exists()) {
            val legacy = mutableListOf<CredentialsMapping>()
            CredentialsParser { legacy.add(it) }.parse(com.mucommander.commons.file.FileFactory.getFile(legacyFile.absolutePath))
            for (m in legacy) {
                remember(m)
            }
            save()
            legacyFile.renameTo(File(legacyFile.path + ".migrated"))
            LOGGER.info("migrated {} credentials from {}", legacy.size, legacyFile)
        }
    }

    private fun read(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val accountNodes = root.getElementsByTagName("account")
        for (i in 0 until accountNodes.length) {
            val e = accountNodes.item(i) as Element
            val account = Account(
                e.getAttribute("id"), e.getAttribute("name"), e.getAttribute("login"),
                e.getAttribute("storage").ifEmpty { STORAGE_FILE }
            )
            if (e.hasAttribute("password")) {
                account.password = try {
                    XORCipher.decryptXORBase64(e.getAttribute("password"))
                } catch (_: Exception) {
                    null
                }
            }
            accounts.add(account)
        }
        val bindingNodes = root.getElementsByTagName("binding")
        for (i in 0 until bindingNodes.length) {
            val e = bindingNodes.item(i) as Element
            try {
                val url = FileURL.getFileURL(e.getAttribute("url"))
                val props = e.getElementsByTagName("property")
                for (j in 0 until props.length) {
                    val p = props.item(j) as Element
                    url.setProperty(p.getAttribute("name"), p.getAttribute("value"))
                }
                bindings.add(Binding(url, e.getAttribute("account")))
            } catch (ex: Exception) {
                LOGGER.warn("skipping invalid binding {}", e.getAttribute("url"))
            }
        }
    }

    /** Writes the accounts file (owner read/write only). Errors are logged, the data stays in memory. */
    @JvmStatic
    @Synchronized
    fun save() {
        val target = file ?: return
        try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            val root = doc.createElement("accounts")
            root.setAttribute("version", FILE_VERSION)
            doc.appendChild(root)
            for (a in accounts) {
                val e = doc.createElement("account")
                e.setAttribute("id", a.id)
                e.setAttribute("name", a.name)
                e.setAttribute("login", a.login)
                e.setAttribute("storage", a.storage)
                if (a.storage == STORAGE_FILE) {
                    a.password?.let { e.setAttribute("password", XORCipher.encryptXORBase64(it)) }
                }
                root.appendChild(e)
            }
            for (b in bindings) {
                val e = doc.createElement("binding")
                e.setAttribute("url", b.url.toString(false))
                e.setAttribute("account", b.accountId)
                for (name in b.url.propertyNames) {
                    val p = doc.createElement("property")
                    p.setAttribute("name", name)
                    p.setAttribute("value", b.url.getProperty(name))
                    e.appendChild(p)
                }
                root.appendChild(e)
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.delete()
            tmp.createNewFile()
            restrictToOwner(tmp)
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }.transform(DOMSource(doc), StreamResult(tmp))
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            LOGGER.error("could not save {}", target, e)
        }
    }

    private fun restrictToOwner(f: File) {
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
        }
    }

    ////////////////////
    // Helpers        //
    ////////////////////

    /** Location without credentials, query, and with a path ending with a separator */
    private fun cleanUrl(url: FileURL): FileURL {
        val clean = url.clone() as FileURL
        clean.credentials = null
        clean.query = null
        val sep = clean.pathSeparator
        if (!clean.path.endsWith(sep)) {
            clean.path = clean.path + sep
        }
        return clean
    }

    private fun sameServer(a: FileURL, b: FileURL) = a.schemeEquals(b) && a.hostEquals(b) && a.portEquals(b)

    private fun key(url: FileURL) =
        "${url.scheme}://${url.host?.lowercase()}:${url.port}/${segments(url.path).joinToString("/") { it.lowercase() }}"

    private fun segments(path: String?) = (path ?: "").split('/', '\\').filter { it.isNotEmpty() }

    private fun isPrefix(prefix: List<String>, path: List<String>) =
        prefix.size <= path.size && prefix.indices.all { prefix[it].equals(path[it], ignoreCase = true) }
}
