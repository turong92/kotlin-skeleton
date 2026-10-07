package dev.sumin.skeleton.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StorageKeySchemeTest {
    private val secret = "k".repeat(40)
    private val account = "acc_7f3a9c"
    private val opaque = StorageKeyScheme(StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE, keySecret = secret))
    private val plain = StorageKeyScheme(StorageWebProperties())

    @Test
    fun `account id mode issues the prefix account uuid file key it always did`() {
        assertTrue(Regex("uploads/$account/[0-9a-f-]{36}/cat\\.png").matches(plain.issue(account, "cat.png")))
        assertTrue(plain.owns(account, "uploads/$account/x/cat.png"))
        assertFalse(plain.owns("acc_b", "uploads/$account/x/cat.png"))
        assertFalse(plain.owns("acc_a", "uploads/acc_ab/x/cat.png"))
    }

    @Test
    fun `opaque mode issues prefix o uuid dot mac file without the account id`() {
        val key = opaque.issue(account, "cat.png")
        assertTrue(Regex("uploads/o/[0-9a-f-]{36}\\.[0-9a-f]{32}/file\\.png").matches(key), key)
        assertFalse(key.contains(account))
        assertTrue(opaque.owns(account, key))
    }

    @Test
    fun `opaque mode drops the client file name and keeps only a safe short extension`() {
        assertTrue(opaque.issue(account, "my holiday photo.JPG").endsWith("/file.jpg"))
        assertTrue(opaque.issue(account, "archive.tar.gz").endsWith("/file.gz"))
        assertTrue(opaque.issue(account, "README").endsWith("/file"))
        assertTrue(opaque.issue(account, "weird.ex_t!").endsWith("/file"))
        assertTrue(opaque.issue(account, "long.abcdefghijklmnop").endsWith("/file"))
        assertTrue(opaque.issue(account, "trailing.").endsWith("/file"))
        assertFalse(opaque.issue(account, "private-name.png").contains("private-name"))
        assertTrue(plain.issue(account, "private-name.png").endsWith("/private-name.png"))
    }

    @Test
    fun `two uploads of one account share nothing between the prefix and the file name`() {
        val a = opaque.issue(account, "cat.png").split('/')
        val b = opaque.issue(account, "cat.png").split('/')
        val (uuidA, macA) = a[2].split('.')
        val (uuidB, macB) = b[2].split('.')
        assertNotEquals(uuidA, uuidB)
        assertNotEquals(macA, macB)
        assertNotEquals(a[2], b[2])
    }

    @Test
    fun `someone else's opaque key is not owned`() {
        val key = opaque.issue(account, "cat.png")
        assertFalse(opaque.owns("acc_other", key))
    }

    @Test
    fun `a tampered mac or uuid is refused`() {
        val key = opaque.issue(account, "cat.png")
        val seg = key.split('/')[2]
        val flippedMac = seg.dropLast(1) + (if (seg.last() == '0') '1' else '0')
        assertFalse(opaque.owns(account, key.replace(seg, flippedMac)))
        val flippedUuid = (if (seg.first() == '0') '1' else '0') + seg.drop(1)
        assertFalse(opaque.owns(account, key.replace(seg, flippedUuid)))
        assertFalse(opaque.owns(account, key.replace(seg, seg.substringBefore('.') + ".")))
        assertFalse(opaque.owns(account, key.replace(seg, seg.substringBefore('.'))))
        assertFalse(opaque.owns(account, key.replace(seg, seg.uppercase())))
    }

    @Test
    fun `a key from another secret is refused`() {
        val key = StorageKeyScheme(StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE, keySecret = "z".repeat(40))).issue(account, "cat.png")
        assertFalse(opaque.owns(account, key))
    }

    @Test
    fun `an old account id key is still owned in opaque mode`() {
        assertTrue(opaque.owns(account, "uploads/$account/x/cat.png"))
        assertFalse(opaque.owns("acc_b", "uploads/$account/x/cat.png"))
    }

    @Test
    fun `an account named o cannot claim opaque keys through the old prefix`() {
        val key = opaque.issue(account, "cat.png")
        assertFalse(opaque.owns("o", key))
        assertFalse(opaque.owns("o", "uploads/o/x/f"), "everything under prefix/o/ is judged by the mac only")
    }

    @Test
    fun `the o account exclusion applies to opaque mode only`() {
        assertTrue(plain.owns("o", "uploads/o/x/f.png"))
        assertFalse(plain.owns("acc_b", "uploads/o/x/f.png"))
    }

    @Test
    fun `a key that is not a valid object key or has nothing after the prefix is not owned`() {
        val mine = opaque.issue(account, "cat.png")
        val other = opaque.issue("acc_other", "cat.png")
        val myseg = mine.split('/')[2]
        val otherseg = other.split('/')[2]
        assertFalse(opaque.owns(account, "uploads/$account/../acc_b/x"))
        assertFalse(opaque.owns(account, "uploads/o/$myseg/../$otherseg/f"))
        assertFalse(opaque.owns(account, "$mine/../../x"))
        assertFalse(opaque.owns(account, "uploads/$account/"))
        assertFalse(opaque.owns(account, "uploads/o/$myseg/"))
        assertFalse(opaque.owns(account, "uploads/$account/x/\\y"))
        assertFalse(opaque.owns(account, " uploads/$account/x"))
        assertFalse(plain.owns(account, "uploads/$account/"))
        assertFalse(plain.owns(account, "uploads/$account/./x"))
    }

    @Test
    fun `account id mode does not accept an opaque key`() {
        assertFalse(plain.owns(account, opaque.issue(account, "cat.png")))
    }

    @Test
    fun `keys outside the prefix and malformed keys are not owned`() {
        assertFalse(opaque.owns(account, "other/$account/x/f"))
        assertFalse(opaque.owns(account, ""))
        assertFalse(opaque.owns(account, "uploads/o/"))
        assertFalse(opaque.owns(account, "uploads/o/../$account/x"))
    }

    @Test
    fun `opaque mode needs a secret of at least 32 characters`() {
        assertFailsWith<IllegalArgumentException> { StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE) }
        val e = assertFailsWith<IllegalArgumentException> { StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE, keySecret = "short") }
        assertTrue(e.message!!.contains("skeleton.storage.web.key-secret"))
        assertFalse(e.message!!.contains("short"), "the value is never echoed")
        assertFalse(StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE, keySecret = "s3cr3t-".repeat(6)).toString().contains("s3cr3t"))
        StorageWebProperties(keyOwner = StorageKeyOwner.OPAQUE, keySecret = "x".repeat(32))
        StorageWebProperties()
    }

    @Test
    fun `the default is the account id scheme with no secret`() {
        assertEquals(StorageKeyOwner.ACCOUNT_ID, StorageWebProperties().keyOwner)
    }
}
