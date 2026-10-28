package com.heytesla.app.peer

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Independent test oracle; deliberately imports no production codec, key or fixture helper.
 * Exact public contract and known-answer vectors:
 * https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/protocol.md
 * APK static corroboration (not vehicle/RF evidence):
 * ECDH/KDF ev_a2c1055c1ae7360748af71639d87b756fde02dabc57538847afba57fe957a9ee;
 * TLV ev_bcaa7c774308d7971d71876e7ae8988830f8300ad216ecd72d4816b7fea27533;
 * BE uint32 ev_69788132c8c483352b86b3144026427f4a4defd9cc7a26790d75f4db527d0073;
 * session HMAC ev_779760e91b5ad124465555500a563d541863eec86398a80f77e7db04a7479103;
 * AES-GCM ev_d15348eb5547663c75bd49995dc7422f70596b1b8783281637d38643bcaa207b.
 * Fresh private keys remain JCA objects in process memory, never PEM/serialized or logged.
 */
internal object OfficialPeerCrypto {
    const val UINT32_MAX = 0xffff_ffffL

    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    fun freshKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun encodePublic(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        val result = ByteArray(65)
        result[0] = 4
        putCoordinate(point.affineX, result, 1)
        putCoordinate(point.affineY, result, 33)
        return result
    }

    private fun putCoordinate(value: BigInteger, output: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        try {
            val start = if (bytes.size == 33 && bytes[0] == 0.toByte()) 1 else 0
            val length = bytes.size - start
            require(value.signum() >= 0 && length <= 32) { "P256_COORDINATE" }
            System.arraycopy(bytes, start, output, offset + 32 - length, length)
        } finally { bytes.fill(0) }
    }

    fun decodePublic(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "P256_ENCODING" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val p = (curve.curve.field as ECFieldFp).p
        require(x < p && y < p) { "P256_COORDINATE" }
        val rhs = x.multiply(x).multiply(x).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
        require(y.multiply(y).mod(p) == rhs) { "P256_POINT" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve))
    }

    fun sharedKey(privateKey: PrivateKey, publicKey: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePublic(publicKey), true)
        val secret = agreement.generateSecret()
        try {
            require(secret.size == 32) { "ECDH_COORDINATE_LENGTH" }
            val digest = MessageDigest.getInstance("SHA-1").digest(secret)
            return try { digest.copyOf(16) } finally { digest.fill(0) }
        } finally { secret.fill(0) }
    }

    fun uint32(value: Long): ByteArray {
        require(value in 0..UINT32_MAX) { "UINT32_RANGE" }
        return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte())
    }

    fun tlv(vararg fields: Pair<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        var previous = -1
        for ((tag, bytes) in fields) {
            require(tag in 0..254 && tag > previous && bytes.size <= 255) { "METADATA_ORDER_OR_LENGTH" }
            output.write(tag)
            output.write(bytes.size)
            output.write(bytes)
            previous = tag
        }
        output.write(255)
        return output.toByteArray()
    }

    fun sessionInfoKey(key: ByteArray): ByteArray = hmac(key, "session info".toByteArray(Charsets.US_ASCII))

    fun sessionInfoTag(key: ByteArray, vin: ByteArray, challenge: ByteArray, rawInfo: ByteArray): ByteArray {
        val derived = sessionInfoKey(key)
        val metadata = tlv(0 to byteArrayOf(6), 2 to vin, 6 to challenge)
        return try { hmac(derived, metadata, rawInfo) }
        finally { derived.fill(0); metadata.fill(0) }
    }

    fun hmac(key: ByteArray, vararg inputs: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        inputs.forEach { update(it) }
        doFinal()
    }

    fun requestMetadata(domain: Int, vin: ByteArray, epoch: ByteArray, expiresAt: Long,
                        counter: Long, flags: Long): ByteArray {
        require(domain in 0..255)
        val fields = arrayListOf(0 to byteArrayOf(5), 1 to byteArrayOf(domain.toByte()), 2 to vin,
            3 to epoch, 4 to uint32(expiresAt), 5 to uint32(counter))
        if (flags != 0L) fields.add(7 to uint32(flags))
        return tlv(*fields.toTypedArray())
    }

    fun requestHash(tag: ByteArray): ByteArray {
        require(tag.size == 16) { "REQUEST_TAG_LENGTH" }
        return byteArrayOf(5) + tag
    }

    fun responseMetadata(domain: Int, vin: ByteArray, counter: Long, flags: Long,
                         requestHash: ByteArray, fault: Long): ByteArray {
        require(domain in 0..255)
        return tlv(0 to byteArrayOf(9), 1 to byteArrayOf(domain.toByte()), 2 to vin,
            5 to uint32(counter), 7 to uint32(flags), 8 to requestHash, 9 to uint32(fault))
    }

    /** Input/output of encryption is ciphertext || tag; decryption takes the same representation. */
    fun aesGcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, metadata: ByteArray,
               input: ByteArray): ByteArray {
        require(key.size == 16 && nonce.size == 12) { "AEAD_KEY_OR_NONCE_LENGTH" }
        val aad = MessageDigest.getInstance("SHA-256").digest(metadata)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(input)
            }
        } finally { aad.fill(0) }
    }
}
