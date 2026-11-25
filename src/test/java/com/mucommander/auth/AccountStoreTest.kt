package com.mucommander.auth

import com.mucommander.commons.file.Credentials
import com.mucommander.commons.file.FileURL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AccountStoreTest {

    @TempDir
    lateinit var dir: File

    private lateinit var file: File

    companion object {
        @JvmStatic
        @BeforeAll
        fun noKeyring() {
            // Never touch the real desktop keyring from tests: passwords go to the file
            System.setProperty("trolcommander.keyring", "off")
        }
    }

    @BeforeEach
    fun setUp() {
        file = File(dir, "accounts.xml")
        AccountStore.load(file, null)
    }

    private fun url(s: String) = FileURL.getFileURL(s)

    private fun remember(location: String, login: String, password: String, accountId: String? = null) =
        AccountStore.remember(CredentialsMapping(Credentials(login, password), url(location), true, accountId))

    @Test
    fun `saved login is used for the share and below, not for other shares`() {
        remember("smb://nas/Data/", "alice", "pw1")
        val inside = AccountStore.autoLogin(url("smb://nas/Data/photos/2024/"))
        assertNotNull(inside)
        assertEquals("alice", inside!!.credentials.login)
        assertEquals("pw1", inside.credentials.password)
        assertNull(AccountStore.autoLogin(url("smb://nas/Other/")))
        assertNull(AccountStore.autoLogin(url("smb://other-host/Data/")))
    }

    @Test
    fun `host wide binding covers all shares and the most specific binding wins`() {
        val admin = AccountStore.addAccount("admin", "root", "rootpw")
        val guest = AccountStore.addAccount("guest", "bob", "bobpw")
        AccountStore.bind(url("smb://nas/"), admin)
        AccountStore.bind(url("smb://nas/Public/"), guest)
        assertEquals("root", AccountStore.autoLogin(url("smb://nas/Data/x/"))!!.credentials.login)
        assertEquals("bob", AccountStore.autoLogin(url("smb://nas/Public/x/"))!!.credentials.login)
        assertEquals("bob", AccountStore.autoLogin(url("smb://NAS/public/"))!!.credentials.login)
    }

    @Test
    fun `same login and password reuse one account for several servers`() {
        remember("smb://a/Data/", "alice", "pw")
        remember("smb://b/Data/", "alice", "pw")
        assertEquals(1, AccountStore.getAccounts().size)
        assertEquals(2, AccountStore.getBindings().size)
    }

    @Test
    fun `changed password updates the bound account instead of creating a new one`() {
        remember("smb://a/Data/", "alice", "old")
        remember("smb://a/Data/", "alice", "new")
        assertEquals(1, AccountStore.getAccounts().size)
        assertEquals("new", AccountStore.autoLogin(url("smb://a/Data/"))!!.credentials.password)
    }

    @Test
    fun `explicitly picked account is bound to the new server`() {
        val shared = AccountStore.addAccount("office", "corp\\alice", "pw")
        remember("smb://fileserver/Team/", "corp\\alice", "pw", shared.id)
        assertEquals(1, AccountStore.getAccounts().size)
        assertEquals(shared.id, AccountStore.findBinding(url("smb://fileserver/Team/"))!!.accountId)
    }

    @Test
    fun `accounts and bindings survive a reload, credentials never end up in urls`() {
        remember("smb://user:secret@nas/Data/", "alice", "pw")
        AccountStore.load(file, null)
        assertEquals(1, AccountStore.getAccounts().size)
        val binding = AccountStore.getBindings().single()
        assertEquals("smb://nas/Data/", binding.displayUrl)
        assertEquals("pw", AccountStore.autoLogin(url("smb://nas/Data/"))!!.credentials.password)
        val xml = file.readText()
        assertFalse(xml.contains("secret"))
        assertFalse(xml.contains("\"pw\""))
        assertEquals(
            "rw-------",
            java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
        )
    }

    @Test
    fun `deleting an account removes its bindings`() {
        remember("smb://a/Data/", "alice", "pw")
        AccountStore.removeAccount(AccountStore.getAccounts().single())
        assertTrue(AccountStore.getBindings().isEmpty())
        assertNull(AccountStore.autoLogin(url("smb://a/Data/")))
    }

    @Test
    fun `legacy credentials xml is migrated`() {
        val legacy = File(dir, "credentials.xml")
        legacy.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <credentials_list encryption="weak" version="1.0">
            <credentials>
            <url><![CDATA[smb://old-nas/Media/]]></url>
            <login><![CDATA[carol]]></login>
            <password><![CDATA[${com.mucommander.bookmark.XORCipher.encryptXORBase64("carolpw")}]]></password>
            </credentials>
            </credentials_list>
            """.trimIndent()
        )
        file.delete()
        AccountStore.load(file, legacy)
        assertEquals("carol", AccountStore.autoLogin(url("smb://old-nas/Media/"))!!.credentials.login)
        assertTrue(file.exists())
        assertFalse(legacy.exists())
        assertTrue(File(dir, "credentials.xml.migrated").exists())
    }
}
