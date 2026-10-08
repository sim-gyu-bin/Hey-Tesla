package com.heytesla.app

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * 앱 전용 noBackup 저장소. AndroidKeyStore 밖에 키를 내보내지 않으며 평문 fallback은 없다.
 * 암호문을 pending 파일에 sync하고 원자 rename 뒤 디렉터리도 sync해야 저장 완료다.
 * 이전 유효암호문은 .previous에 보존해 commit 실패만 복구한다. 복원 fallback/삭제/키 교체 API는 없다.
 */
internal class VehicleVinStore private constructor(private val file: File, private val alias: String) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, RELATIVE_PATH), KEY_ALIAS)

    /** 실제 저장 API를 사용하되 계측은 이 별도 디렉터리와 alias namespace에만 기록한다. */
    internal constructor(context: Context, fixtureFile: File, fixtureAlias: String) : this(fixtureFile, fixtureAlias) {
        require(fixtureFile.parentFile?.canonicalFile == File(context.noBackupFilesDir, TEST_DIRECTORY).canonicalFile)
        require(fixtureAlias.startsWith(TEST_ALIAS_PREFIX) && fixtureAlias.length > TEST_ALIAS_PREFIX.length)
    }

    fun restore(): VehicleVin? = synchronized(storageLock) {
        backgroundThread()
        var envelope: ByteArray? = null
        try {
            if (!filePresent()) return@synchronized null
            val bytes = readEnvelope()
            envelope = bytes
            decrypt(bytes)
        } catch (_: Exception) {
            throw VehicleVinStorageException("VIN_RESTORE_FAILED")
        } finally {
            envelope?.fill(0)
        }
    }

    fun save(input: String): VehicleVin {
        backgroundThread()
        // 잘못된 입력은 키 조회/생성, 디렉터리 생성, 파일 쓰기 이전에 거절한다.
        return save(VehicleVin.parse(input) ?: throw VehicleVinStorageException("VIN_FORMAT_INVALID"))
    }

    internal fun save(vin: VehicleVin): VehicleVin = synchronized(storageLock) {
        backgroundThread()
        var plaintext: ByteArray? = null
        var encrypted: ByteArray? = null
        var envelope: ByteArray? = null
        var previousEnvelope: ByteArray? = null
        var committed = false
        val parent = file.parentFile ?: throw VehicleVinStorageException("VIN_SAVE_FAILED")
        val previous = File(parent, file.name + ".previous")
        try {
            if (filePresent()) {
                val old = readEnvelope()
                previousEnvelope = old
                // 손상/다른 키의 기존 자료는 저장에서도 교체하지 않는다. .previous로 복원하지 않는다.
                decrypt(old)
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // 기존 암호문이 있는데 alias가 없으면 새 키로 덮어쓰지 않는다.
            cipher.init(Cipher.ENCRYPT_MODE, key(create = previousEnvelope == null))
            cipher.updateAAD(AAD)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw VehicleVinStorageException("VIN_SAVE_FAILED")
            val material = vin.snapshot().toByteArray(Charsets.US_ASCII)
            plaintext = material
            val ciphertext = cipher.doFinal(material)
            encrypted = ciphertext
            if (ciphertext.size != VIN_BYTES + TAG_BYTES) throw VehicleVinStorageException("VIN_SAVE_FAILED")
            val bytes = ByteArray(ENVELOPE_BYTES)
            envelope = bytes
            bytes[0] = VERSION
            iv.copyInto(bytes, 1)
            ciphertext.copyInto(bytes, 1 + IV_BYTES)
            if (!parent.isDirectory) {
                if (!parent.mkdirs()) throw VehicleVinStorageException("VIN_SAVE_FAILED")
                parent.parentFile?.let(::syncDirectory)
            }
            previousEnvelope?.let { old ->
                FileOutputStream(previous).use { output -> output.write(old); output.fd.sync() }
            }
            // 신규 디렉터리/복구용 암호문도 rename 전에 durable하게 만든다.
            syncDirectory(parent)
            val pending = File(parent, file.name + ".pending")
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // AtomicFile.finishWrite의 내부 sync/rename 실패 로그 대신 실패를 호출자에게 명시한다.
            Os.rename(pending.absolutePath, file.absolutePath)
            committed = true
            syncDirectory(parent)
            vin
        } catch (_: Exception) {
            if (committed && previousEnvelope != null) {
                try {
                    Os.rename(previous.absolutePath, file.absolutePath)
                    syncDirectory(parent)
                } catch (_: Exception) {
                    // 복구 자체 실패에도 .previous 암호문은 남고 상태는 FAILED다. 성공/fallback으로 숨기지 않는다.
                }
            }
            throw VehicleVinStorageException("VIN_SAVE_FAILED")
        } finally {
            plaintext?.fill(0)
            encrypted?.fill(0)
            envelope?.fill(0)
            previousEnvelope?.fill(0)
        }
    }

    private fun readEnvelope(): ByteArray {
        val bytes = ByteArray(ENVELOPE_BYTES)
        try {
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) throw VehicleVinStorageException("VIN_RESTORE_FAILED")
                    offset += count
                }
                if (input.read() != -1) throw VehicleVinStorageException("VIN_RESTORE_FAILED")
            }
            return bytes
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun decrypt(bytes: ByteArray): VehicleVin {
        if (bytes[0] != VERSION) throw VehicleVinStorageException("VIN_RESTORE_FAILED")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, bytes, 1, IV_BYTES))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(bytes, 1 + IV_BYTES, VIN_BYTES + TAG_BYTES)
        return try {
            if (plaintext.size != VIN_BYTES) throw VehicleVinStorageException("VIN_RESTORE_FAILED")
            VehicleVin.parse(String(plaintext, Charsets.US_ASCII)) ?: throw VehicleVinStorageException("VIN_RESTORE_FAILED")
        } finally {
            plaintext.fill(0)
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw VehicleVinStorageException("VIN_SAVE_FAILED")
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    private fun filePresent(): Boolean = try {
        Os.stat(file.absolutePath)
        true
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) false else throw failure
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (!create) throw VehicleVinStorageException("VIN_RESTORE_FAILED")
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .setUnlockedDeviceRequired(false)
                    .build())
                generateKey()
            }
        }
        val secret = (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw VehicleVinStorageException("VIN_RESTORE_FAILED")
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .getKeySpec(secret, KeyInfo::class.java) as KeyInfo
        if (info.origin != KeyProperties.ORIGIN_GENERATED || info.keySize != 256 ||
            info.purposes != (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ||
            !info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)) ||
            !info.encryptionPaddings.contentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE)) ||
            info.isUserAuthenticationRequired
        ) throw VehicleVinStorageException("VIN_RESTORE_FAILED")
        return secret
    }

    private fun backgroundThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "VIN_IO_REQUIRES_BACKGROUND" }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "hey_tesla_vehicle_vin_aes_gcm_v1"
        const val RELATIVE_PATH = "vehicle-vin/registered.enc"
        const val TEST_DIRECTORY = "vehicle-vin-tests"
        const val TEST_ALIAS_PREFIX = "heytesla.vin.test."
        const val VIN_BYTES = 17
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = TAG_BYTES * 8
        const val ENVELOPE_BYTES = 1 + IV_BYTES + VIN_BYTES + TAG_BYTES
        const val VERSION: Byte = 1
        val AAD = "heytesla.vehicle-vin.v1".toByteArray(Charsets.US_ASCII)
        val storageLock = Any()
    }
}
