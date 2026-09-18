package com.nomily.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nomily.app.data.KeyVault
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [KeyVault] real-device test — **must run on a device**: AndroidKeyStore has no JVM implementation,
 * Even in unit tests you can't obtain `KeyStore.getInstance("AndroidKeyStore")`.
 *
 * The focus is PLAN §3.5's **R2 (accounting by SN: a key from one device cannot decrypt another device's file)**.
 * R2 is testable here because it is exactly the "each SN stores its own" scenario.
 *
 * R1 (unreadable when screen locked) and R3 (excluded from backup) **cannot be tested here**:
 * - R1 requires an actual screen lock + a lock screen, which is a personal judgment; this test only asserts whether the device supports this property
 * - R3 is a build‑time manifest / XML rule, relying on build artifacts rather than runtime behavior
 *
 * Clearly stating what cannot be tested is more important than making all three appear "green".
 */
class KeyVaultDeviceTest {

    private lateinit var vault: KeyVault
    private val prefsName = "dnote-keys-test"

    private val snA = "DNOTE0000000000001"
    private val snB = "DNOTE0000000000002"
    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (0xff - it).toByte() }

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        vault = KeyVault(ctx(), ctx().getSharedPreferences(prefsName, Context.MODE_PRIVATE))
        vault.clear(snA); vault.clear(snB)
    }

    @After
    fun tearDown() {
        vault.clear(snA); vault.clear(snB)
    }

    @Test
    fun storesAndReadsBackByteForByte() {
        assertNull("Nothing should be readable from a clean state", vault.load(snA))
        vault.save(snA, keyA)
        assertArrayEquals(keyA, vault.load(snA))
    }

    /** **R2's positive assertion**: The two SNs are stored separately, not affecting each other. */
    @Test
    fun r2_twoDevicesStayIndependent() {
        vault.save(snA, keyA)
        vault.save(snB, keyB)
    assertArrayEquals("A's key was contaminated by B", keyA, vault.load(snA))
    assertArrayEquals("B's key was contaminated by A", keyB, vault.load(snB))
        assertEquals(setOf(snA, snB), vault.knownSerials())
    }

    /**
     * **R2's negative assertion**: after deleting A, A cannot read, while B **is completely unaffected**.
     *
     * Testing only the positive case (each can read back) is insufficient — an implementation that shares a wrapping key can also pass the positive case.
     * The real distinction between “one SN one alias” and “shared key” is this: after deleting A's key alias, B must still be able to decrypt.
     */
    @Test
    fun r2_deletingOneLeavesTheOtherIntact() {
        vault.save(snA, keyA)
        vault.save(snB, keyB)
    assertTrue("clear should report that it existed", vault.clear(snA))
    assertNull("A deleted, should not be readable", vault.load(snA))
    assertArrayEquals("Deleting A should not affect B", keyB, vault.load(snB))
        assertEquals(setOf(snB), vault.knownSerials())
    }

    @Test
    fun overwritingTheSameSnKeepsTheLastValue() {
        vault.save(snA, keyA)
        vault.save(snA, keyB)
        assertArrayEquals(keyB, vault.load(snA))
    }

    @Test
    fun rejectsAnyKeyThatIsNot32Bytes() {
        for (bad in listOf(0, 1, 16, 31, 33, 64)) {
            val e = runCatching { vault.save(snA, ByteArray(bad)) }.exceptionOrNull()
    assertNotNull("$bad-byte key should be rejected", e)
            assertTrue(e is IllegalArgumentException)
        }
    }

    /**
     * R1 only reports, it does not pretend to verify.
     *
     * Its purpose is to print whether R1 holds on this device. Two preconditions:
     * API >= 28 **and** a secure lock screen is set — the second condition was discovered on real devices (see the KeyVault class comment).
     *
     * ⚠️ This line **does not assert `r1Satisfied == true`**: if the device has no lock screen, the assertion would stay red permanently,
     * whereas R1 not being satisfied is an **environmental fact**, not a code defect. It only asserts that the **logical relationship** between them holds.
     */
    @Test
    fun r1_reportsLockScreenProtectionOnThisDevice() {
        val km = ctx().getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val secure = km.isDeviceSecure
        println(
            "R1 (unreadable while locked) on this device: ${if (vault.r1Satisfied) "enabled" else "**NOT satisfied**"}" +
                " (API ${android.os.Build.VERSION.SDK_INT}, needs >= 28; secure lock screen = $secure)",
        )
        assertEquals(
    "r1Satisfied must exactly equal \"API>=28 and secure lock screen set\"",
            android.os.Build.VERSION.SDK_INT >= 28 && secure,
            vault.r1Satisfied,
        )
    }

    /**
     * **Keys must still be storable and readable on devices without a lock screen** — this is the regression test for that real-device bug.
     *
     * Previously `setUnlockedDeviceRequired(true)` was added unconditionally, so on this device (no lock screen)
     * even `Cipher.init(ENCRYPT_MODE)` fails: `UserNotAuthenticatedException`.
     * The consequence is not "slightly weaker protection", but **the entire persistence becomes invalid, requiring the user to re-enter the password on every launch**.
     */
    @Test
    fun persistenceStillWorksWithoutALockScreen() {
        vault.save(snA, keyA)
        assertArrayEquals(
    "Regardless of R1 satisfaction, key must be stored and retrieved (also works on devices without lock screen password)",
            keyA,
            vault.load(snA),
        )
    }
}
