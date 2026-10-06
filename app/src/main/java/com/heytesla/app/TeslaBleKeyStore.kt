package com.heytesla.app

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

internal interface TeslaBleCredential {
    fun publicKeyBytes(): ByteArray
    fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray
}

/** 원인·alias·키 소재를 노출하지 않는 고정 로컬 오류. */
internal class TeslaBleKeyException : IllegalStateException("앱 전용 보안 키를 사용할 수 없습니다")

/** 기존 alias가 손상되거나 사용할 수 없으면 삭제/교체하지 않고 실패한다. */
internal class TeslaBleKeyStore(context: Context, private val alias: String = KEY_ALIAS) {
    private val applicationContext = context.applicationContext

    fun hasKey(): Boolean = synchronized(keyLock) {
        try {
            loadStore().containsAlias(alias)
        } catch (_: Exception) {
            throw TeslaBleKeyException()
        }
    }

    /** 명시적 시작 후 IO dispatcher에서만 호출. 개인키는 AndroidKeyStore 밖으로 나가지 않는다. */
    fun getOrCreate(): TeslaBleCredential = synchronized(keyLock) {
        try {
            val keyguard = applicationContext.getSystemService(KeyguardManager::class.java)
                ?: throw TeslaBleKeyException()
            if (keyguard.isDeviceLocked) throw TeslaBleKeyException()
            val store = loadStore()
            if (!store.containsAlias(alias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                    initialize(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setUserAuthenticationRequired(false)
                            .setUnlockedDeviceRequired(false)
                            .build(),
                    )
                    generateKeyPair()
                }
            }
            val entry = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: throw TeslaBleKeyException()
            val publicKey = entry.certificate.publicKey as? ECPublicKey
                ?: throw TeslaBleKeyException()
            requireP256(publicKey)
            val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                .getKeySpec(entry.privateKey, KeyInfo::class.java)
            // imported/software-only/다른 용도의 alias는 재사용하지 않는다. fallback도 없다.
            if (info.origin != KeyProperties.ORIGIN_GENERATED ||
                info.purposes != KeyProperties.PURPOSE_AGREE_KEY ||
                info.isUserAuthenticationRequired ||
                (info.securityLevel != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT &&
                    info.securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX)
            ) throw TeslaBleKeyException()
            AndroidTeslaBleCredential(entry.privateKey, publicKey)
        } catch (_: Exception) {
            throw TeslaBleKeyException()
        }
    }

    private fun loadStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "hey_tesla_ble_driver_p256_v1"
        val keyLock = Any()
    }
}

private class AndroidTeslaBleCredential(
    private val privateKey: PrivateKey,
    private val publicKey: ECPublicKey,
) : TeslaBleCredential {
    override fun publicKeyBytes(): ByteArray = try {
        p256PublicKeyBytes(publicKey)
    } catch (_: Exception) {
        throw TeslaBleKeyException()
    }

    override fun deriveSessionKey(vehiclePublicKey: ByteArray): ByteArray {
        var secret: ByteArray? = null
        var digest: ByteArray? = null
        return try {
            val peer = p256PublicKeyFromBytes(vehiclePublicKey)
            val agreement = KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
            agreement.init(privateKey)
            // 점 유효성/곡선 검증은 JCA/KeyStore ECDH 제공자에 맡긴다. 수제 EC 연산 없음.
            agreement.doPhase(peer, true)
            secret = agreement.generateSecret()
            if (secret.size != 32) throw TeslaBleKeyException()
            digest = MessageDigest.getInstance("SHA-1").digest(secret)
            digest.copyOf(16)
        } catch (_: Exception) {
            throw TeslaBleKeyException()
        } finally {
            secret?.fill(0)
            digest?.fill(0)
        }
    }
}

private val p256Parameters: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)
}

private fun requireP256(key: ECPublicKey) {
    val parameters = key.params
    require(parameters.curve == p256Parameters.curve &&
        parameters.generator == p256Parameters.generator &&
        parameters.order == p256Parameters.order &&
        parameters.cofactor == p256Parameters.cofactor)
}

internal fun p256PublicKeyBytes(key: ECPublicKey): ByteArray {
    requireP256(key)
    val encoded = ByteArray(65)
    encoded[0] = 4
    fun putCoordinate(value: BigInteger, offset: Int) {
        require(value.signum() >= 0 && value.bitLength() <= 256)
        val coordinate = value.toByteArray()
        val first = if (coordinate.size == 33 && coordinate[0] == 0.toByte()) 1 else 0
        val length = coordinate.size - first
        require(length <= 32)
        coordinate.copyInto(encoded, offset + 32 - length, first)
    }
    putCoordinate(key.w.affineX, 1)
    putCoordinate(key.w.affineY, 33)
    return encoded
}

internal fun p256PublicKeyFromBytes(encoded: ByteArray): ECPublicKey {
    require(encoded.size == 65 && encoded[0] == 4.toByte())
    // 고정 SubjectPublicKeyInfo: id-ecPublicKey + prime256v1 + uncompressed point.
    val prefix = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(),
        0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
        0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )
    val key = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(prefix + encoded)) as ECPublicKey
    requireP256(key)
    return key
}
