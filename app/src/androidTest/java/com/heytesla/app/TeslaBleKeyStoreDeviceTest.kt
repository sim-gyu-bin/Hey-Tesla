package com.heytesla.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Android provider에서 등록 identity의 재사용과 차량 측 ECDH 호환성을 검증한다. */
@RunWith(AndroidJUnit4::class)
class TeslaBleKeyStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // 반복 실행해도 한 개의 별도 회귀용 키만 재사용한다. 실제 등록 키를 삭제·변경하지 않는다.
    private fun store() = TeslaBleKeyStore(context, "heytesla.ble.device-regression.v1")

    @Test
    fun recreatingStorePreservesRegisteredPublicKey() {
        val first = store().getOrCreate().publicKeyBytes()
        val second = store().getOrCreate().publicKeyBytes()
        assertTrue(MessageDigest.isEqual(first, second))
        assertTrue(store().hasKey())
    }

    @Test
    fun androidAgreementMatchesVehicleSideAgreement() {
        val client = store().getOrCreate()
        val vehicle = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val curve = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val raw = client.publicKeyBytes()
        val clientPublic = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))),
                curve,
            ),
        )
        val vehicleSecret = KeyAgreement.getInstance("ECDH").run {
            init(vehicle.private)
            doPhase(clientPublic, true)
            generateSecret()
        }
        val expected = MessageDigest.getInstance("SHA-1").digest(vehicleSecret).copyOf(16)
        vehicleSecret.fill(0)
        val vehiclePublic = vehicle.public as ECPublicKey
        val encoded = ByteArray(65).apply {
            this[0] = 4
            writeCoordinate(vehiclePublic.w.affineX, 1)
            writeCoordinate(vehiclePublic.w.affineY, 33)
        }
        val actual = client.deriveSessionKey(encoded)
        assertTrue(MessageDigest.isEqual(expected, actual))
        expected.fill(0)
        actual.fill(0)
    }

    private fun ByteArray.writeCoordinate(value: BigInteger, offset: Int) {
        val bytes = value.toByteArray()
        val start = if (bytes.size == 33 && bytes[0] == 0.toByte()) 1 else 0
        val size = bytes.size - start
        require(size <= 32)
        bytes.copyInto(this, offset + 32 - size, start)
    }
}
