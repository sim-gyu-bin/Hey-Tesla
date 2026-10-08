package com.heytesla.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 합성 식별자와 독립 file/alias만 사용한다. 생산 VIN·등록 키·로그를 수정/삭제하지 않는다. */
@RunWith(AndroidJUnit4::class)
class VehicleVinStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedSaveAndNewStoreRestoreNeverContainThePlaintext() {
        val fixture = fixture("round-trip")
        val saved = fixture.store.save(" \t5yj3e1ea7kf000000\n")
        val restored = requireNotNull(fixture.reopen().restore())
        assertTrue(saved.masked == "*************0000")
        assertTrue(restored.masked == saved.masked)
        assertTrue(TeslaBleAdvertisement.localName(restored.snapshot()) == TeslaBleAdvertisement.localName(FIRST))
        assertFalse(saved.toString().contains(FIRST))
        assertFalse(restored.toString().contains(FIRST))
        assertFalse(fixture.file.readBytes().toString(Charsets.ISO_8859_1).contains(FIRST))
        val initial = fixture.file.readBytes()
        fixture.store.save(FIRST)
        // 매 저장마다 provider가 만든 새 nonce를 사용한다.
        assertFalse(initial.contentEquals(fixture.file.readBytes()))
    }

    @Test fun invalidInputDoesNotChangeTheExistingCiphertextOrCreateAFile() {
        val fixture = fixture("invalid-old")
        fixture.store.save(FIRST)
        val before = fixture.file.readBytes()
        for (input in listOf("", "0000000000000000", "000000000000000000", "5YJ3E1EA7KF00000I",
            "5YJ3E1EA7KF00000O", "5YJ3E1EA7KF00000Q", "5YJ3E1EA7KF0000 0", "5YJ3E1EA7KF00000가")) {
            expectCode("VIN_FORMAT_INVALID") { fixture.store.save(input) }
            assertTrue(before.contentEquals(fixture.file.readBytes()))
        }
        val absent = fixture("invalid-absent")
        expectCode("VIN_FORMAT_INVALID") { absent.store.save("invalid") }
        assertFalse(absent.file.exists())
        assertNull(absent.store.restore())
        assertNotNull(fixture.reopen().restore())
    }

    @Test fun corruptEnvelopeBlocksRestoreAndReplacementWithoutPlaintextFallback() {
        val fixture = fixture("corrupt")
        fixture.store.save(FIRST)
        fixture.store.save(SECOND)
        assertTrue(File(fixture.file.parentFile, fixture.file.name + ".previous").isFile)
        val corrupt = fixture.file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        fixture.file.writeBytes(corrupt)
        expectCode("VIN_RESTORE_FAILED") { fixture.reopen().restore() }
        expectCode("VIN_SAVE_FAILED") { fixture.store.save(SECOND) }
        assertTrue(corrupt.contentEquals(fixture.file.readBytes()))
        val oversized = fixture("oversized")
        oversized.store.save(FIRST)
        oversized.file.appendBytes(byteArrayOf(0))
        expectCode("VIN_RESTORE_FAILED") { oversized.reopen().restore() }
    }

    @Test fun wrongOrMissingKeyCannotDecryptOrReplaceTheOldCiphertext() {
        val fixture = fixture("wrong-key-source")
        fixture.store.save(FIRST)
        val other = fixture("wrong-key-other")
        other.store.save(SECOND)
        val wrong = VehicleVinStore(context, fixture.file, other.alias)
        val before = fixture.file.readBytes()
        expectCode("VIN_RESTORE_FAILED") { wrong.restore() }
        expectCode("VIN_SAVE_FAILED") { wrong.save(SECOND) }
        val missing = VehicleVinStore(context, fixture.file, "heytesla.vin.test.missing-${UUID.randomUUID()}")
        expectCode("VIN_RESTORE_FAILED") { missing.restore() }
        expectCode("VIN_SAVE_FAILED") { missing.save(SECOND) }
        assertTrue(before.contentEquals(fixture.file.readBytes()))
        assertNotNull(fixture.reopen().restore())
    }

    @Test fun writeFailurePreservesTheOldValidEncryptedFile() {
        val fixture = fixture("write-failure")
        fixture.store.save(FIRST)
        val before = fixture.file.readBytes()
        // 독립 fixture의 pending 경로만 디렉터리로 만들어 실제 FileOutputStream 실패를 일으킨다.
        assertTrue(File(fixture.file.parentFile, fixture.file.name + ".pending").mkdir())
        expectCode("VIN_SAVE_FAILED") { fixture.store.save(SECOND) }
        assertTrue(before.contentEquals(fixture.file.readBytes()))
        assertTrue(TeslaBleAdvertisement.localName(requireNotNull(fixture.reopen().restore()).snapshot()) ==
            TeslaBleAdvertisement.localName(FIRST))
    }

    private fun fixture(name: String): Fixture {
        val id = "$name-${UUID.randomUUID()}"
        return Fixture(File(context.noBackupFilesDir, "vehicle-vin-tests/$id.enc"), "heytesla.vin.test.$id")
    }

    private inner class Fixture(val file: File, val alias: String) {
        val store = VehicleVinStore(context, file, alias)
        fun reopen() = VehicleVinStore(context, file, alias)
    }

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("VIN_EXPECTED_STATIC_FAILURE")
        } catch (failure: VehicleVinStorageException) {
            assertEquals(code, failure.code)
            assertEquals(code, failure.message)
            assertNull(failure.cause)
        }
    }

    private companion object {
        const val FIRST = "5YJ3E1EA7KF000000"
        const val SECOND = "5YJ3E1EA7KF000001"
    }
}
