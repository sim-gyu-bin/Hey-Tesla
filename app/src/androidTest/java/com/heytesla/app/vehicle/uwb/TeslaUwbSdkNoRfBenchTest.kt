package com.heytesla.app.vehicle.uwb

import com.heytesla.app.diagnostics.NoRuntimeAndroidJUnitRunner

import android.os.Parcel
import android.ranging.RangingPreference
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.uwb.UwbRangingParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual installed SDK builders + Parcelable, with a private, source-pinned downstream oracle.
 * No manager/capability/permission/device query, session, Activity, service, app data or key store.
 * A pass is NOT execution of the OEM service, its UCI encoder, GMS config 7, or RF acceptance.
 * All identifiers/addresses/keys below are synthetic; failures intentionally never print values.
 */
@RunWith(AndroidJUnit4::class)
class TeslaUwbSdkNoRfBenchTest {
    @Test fun sdkParcelPreservesConsumerContractWithoutRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Ordinary suites keep their existing runner/Application behavior and skip this bench.
        assumeTrue("Select the dedicated no-runtime runner", instrumentation is NoRuntimeAndroidJUnitRunner)
        val runner = instrumentation as NoRuntimeAndroidJUnitRunner
        val context = instrumentation.targetContext
        runner.assertIsolated(context)
        val native = AndroidTeslaUwbPlatform(context).createNativeRanging()
        try {
            // Framework configs, NOT GMS's 1/2/4/5/7 namespace. Both unsigned high-bit boundaries.
            for ((config, peers, sts, multiNode) in listOf(
                listOf(1, 1, 0, 0), listOf(2, 5, 0, 1), listOf(3, 1, 3, 0),
                listOf(4, 5, 3, 1), listOf(5, 5, 4, 1),
            )) {
                for (unsignedId in listOf(0x80a1_b2c3L, 0xffff_ffffL)) {
                    val ids = syntheticIds(peers)
                    val params = parameters(config, peers, unsignedId.toInt())
                    val consumed = PinnedConsumer.consume(parcelledPreference(native, params, ids))
                    expect(consumed.unsignedSessionId == unsignedId, "Unsigned session boundary changed")
                    expect(consumed.sts == sts && consumed.multiNode == multiNode, "Profile namespace changed")
                    expect(consumed.local == bytes(0x24, 0x79), "Short local address direction changed")
                    val expectedPeers = ids.zip(expectedAddresses().take(peers)).toMap()
                    expect(consumed.peers == expectedPeers, "Serialized peer identity/address association changed")
                    val expectedSecurity = if (sts == 0) {
                        mapOf(0x27 to bytes(0x12, 0x34), 0x28 to bytes(0x56, 0x78, 0x21, 0x43, 0x65, 0x7f))
                    } else {
                        mapOf(0x45 to bytes(
                            0x07, 0x12, 0x1d, 0x28, 0x33, 0x3e, 0x49, 0x54,
                            0x5f, 0x6a, 0x75, 0x80, 0x8b, 0x96, 0xa1, 0xac,
                        ))
                    }
                    expect(consumed.securityFields == expectedSecurity, "STS consumer interpretation changed")
                }
            }

            // Public SDK builders are not FiRa validators. Exercise their REAL parcel boundary,
            // then reject invalid widths in the pinned profile consumer, or allow early SDK rejection.
            // This does not assert that an OEM service/HAL accepts any of these malformed inputs.
            for (size in listOf(0, 7, 9)) {
                expectRejected(native, parameters(2, 5).copy(sessionKeyInfo = ByteArray(size)))
            }
            for (size in listOf(0, 15, 17, 31, 33)) {
                expectRejected(native, parameters(4, 5).copy(sessionKeyInfo = ByteArray(size)))
            }
            // AOSP provisioned FiRa's upper key width, not an advertised public-SDK/OEM key limit.
            // Production TeslaUwbRanging admission intentionally accepts only Tesla's 16-byte key.
            val upperKey = parameters(4, 5).copy(sessionKeyInfo = ByteArray(32) { (it * 7 + 3).toByte() })
            val upper = PinnedConsumer.consume(parcelledPreference(native, upperKey, syntheticIds(5)))
            expect(upper.securityFields[0x45]?.size == 32, "Provisioned consumer upper boundary changed")

            // The public address API allows extended addresses, but this source-pinned profile is
            // SHORT only. Check its upper address width and just beyond without a device query.
            expectRejected(native, parameters(4, 5).copy(localAddress = ByteArray(8) { (it + 1).toByte() }))
            expectRejected(native, parameters(4, 5).copy(localAddress = ByteArray(9) { (it + 1).toByte() }))
        } catch (_: Exception) {
            // Framework exception messages/toString may contain addresses or key material.
            // Preserve failure, never attach the original exception or synthetic values to output.
            throw AssertionError("SDK parcel bench could not consume the synthetic contract")
        } finally {
            runner.assertIsolated(context)
        }
    }

    private fun parcelledPreference(
        native: TeslaUwbNativeRanging,
        parameters: TeslaUwbRangingParameters,
        ids: List<UUID>,
    ): RangingPreference {
        val prepared = native.prepare(parameters, ids)
        // Only our app-owned private wrapper is reflected; never access hidden framework APIs.
        val field = prepared.javaClass.getDeclaredField("value")
        field.isAccessible = true
        val preference = field.get(prepared) as RangingPreference
        val parcel = Parcel.obtain()
        try {
            preference.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = RangingPreference.CREATOR.createFromParcel(parcel)
            expect(parcel.dataAvail() == 0, "Preference parcel had an unread tail")
            return restored
        } finally {
            parcel.recycle()
        }
    }

    private fun expectRejected(native: TeslaUwbNativeRanging, parameters: TeslaUwbRangingParameters) {
        val preference = try {
            parcelledPreference(native, parameters, syntheticIds(parameters.peerAddresses.size))
        } catch (_: IllegalArgumentException) {
            // An installed OEM SDK may enforce this boundary before the source-pinned consumer.
            return
        }
        val rejected = try {
            PinnedConsumer.consume(preference)
            false
        } catch (_: ContractRejected) {
            true
        }
        expect(rejected, "Malformed input reached the pinned profile consumer")
    }

    private fun parameters(config: Int, peers: Int, sessionId: Int = Int.MIN_VALUE) = TeslaUwbRangingParameters(
        firaSessionId = sessionId,
        frameworkConfigId = config,
        localAddress = byteArrayOf(0x24, 0x79),
        peerAddresses = List(peers) { index -> byteArrayOf((0x31 + index * 0x11).toByte(), (0x8a + index * 0x11).toByte()) },
        channel = 5,
        preambleIndex = 11,
        sessionKeyInfo = if (config <= 2) byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x21, 0x43, 0x65, 0x7f)
            else ByteArray(16) { (it * 11 + 7).toByte() },
        updateRate = 3,
        angleOfArrivalNeeded = true,
    )

    private fun syntheticIds(count: Int) = List(count) { UUID(0x1234_5678_9abc_def0L, 0x1122_3344_5566_7700L + it) }

    private fun expectedAddresses() = listOf(
        bytes(0x31, 0x8a), bytes(0x42, 0x9b), bytes(0x53, 0xac), bytes(0x64, 0xbd), bytes(0x75, 0xce),
    )

    private class ContractRejected : RuntimeException()

    /**
     * Independent test-only consumer, NOT a production codec or OEM service implementation.
     * Pinned Android android-16.0.0_r1 contracts (all under packages/modules/Uwb):
     * - ranging/service/.../session/RangingSessionConfig.java: PeerIgnoringParamsHasher
     * - ranging/uwb_backend/.../internal/ConfigurationManager.java: config profiles, vendor2/IV6
     *   https://android.googlesource.com/platform/packages/modules/Uwb/+/refs/tags/android-16.0.0_r1/ranging/uwb_backend/src/com/android/ranging/uwb/backend/internal/ConfigurationManager.java
     * - service/support_lib/.../fira/FiraOpenSessionParams.java: checkAddress/checkStsConfig
     *   https://android.googlesource.com/platform/packages/modules/Uwb/+/refs/tags/android-16.0.0_r1/service/support_lib/src/com/google/uwb/support/fira/FiraOpenSessionParams.java
     * - service/.../params/FiraEncoder.java: STATIC vendor/IV vs provisioned SESSION_KEY tags.
     * Address bytes are unchanged for Android 16, assuming the source's default reverse flag=false.
     * Tesla REA APK 63adb6bb0556a44ea83996c719e2dfdcdbda4b33b8250bf46f6e5ee853c3d083:
     * pg0.f.o ev_e9d80e816961c5b275b5fec511a3eeb1717070dd6370838ccb3d2b538d86eac2,
     * o6.b.C1984b.invokeSuspend ev_1df038843f1d6b6ee3497d85031f2915fb8701ae2e0b6365fb3808dbd76878bc.
     * These independently establish Tesla key8/key16 and individual controller 0/null preparation;
     * GMS config7's contradictory nonzero/subkey validation is NOT imported into framework config5.
     * No default timing/notification values are re-pinned here.
     */
    private object PinnedConsumer {
        fun consume(preference: RangingPreference): Consumed {
            requireContract(preference.deviceRole == 1)
            val raw = preference.rangingParams as? RawInitiatorRangingConfig ?: throw ContractRejected()
            requireContract(raw.rawRangingDevices.isNotEmpty())
            val params = raw.rawRangingDevices.map { it.uwbRangingParams ?: throw ContractRejected() }
            // The real service groups on ALL common parameters, excluding peer UUID/address.
            // Its map/set order is unspecified; never infer a UCI destination order from this list.
            requireContract(params.map(::groupKey).distinct().size == 1)
            val first = params.first()
            val profile = when (first.configId) {
                1 -> 0 to 0
                2 -> 0 to 1
                3 -> 3 to 0
                4 -> 3 to 1
                5 -> 4 to 1
                else -> throw ContractRejected()
            }
            val local = first.deviceAddress.addressBytes.toList()
            requireContract(local.size == 2)
            val peers = raw.rawRangingDevices.zip(params).associate { (device, param) ->
                val address = param.peerAddress.addressBytes.toList()
                requireContract(address.size == 2)
                device.rangingDevice.uuid to address
            }
            requireContract(peers.size == params.size && peers.values.toSet().size == params.size)
            requireContract(profile.second == 1 || peers.size == 1)
            val key = first.sessionKeyInfo ?: throw ContractRejected()
            // Explicit Tesla STATIC profile width; this is not a claim that public build() checks it.
            val security = if (profile.first == 0) {
                requireContract(key.size == 8)
                mapOf(0x27 to key.take(2), 0x28 to key.drop(2))
            } else {
                // FiRa provisioned key16/key32; common production admission is narrower (key16).
                requireContract(key.size == 16 || key.size == 32)
                mapOf(0x45 to key.toList())
            }
            if (profile.first == 4) {
                requireContract(first.subSessionId == 0 && first.subSessionKeyInfo == null)
            }
            return Consumed(Integer.toUnsignedLong(first.sessionId), profile.first, profile.second, local, peers, security)
        }

        private fun groupKey(params: UwbRangingParams): List<Any?> = listOf(
            params.sessionId, params.subSessionId, params.configId,
            params.deviceAddress.addressBytes.toList(), params.sessionKeyInfo?.toList(),
            params.subSessionKeyInfo?.toList(), params.complexChannel.channel, params.complexChannel.preambleIndex,
            params.rangingUpdateRate, params.slotDuration,
        )

        private fun requireContract(condition: Boolean) {
            if (!condition) throw ContractRejected()
        }
    }

    private class Consumed(
        val unsignedSessionId: Long,
        val sts: Int,
        val multiNode: Int,
        val local: List<Byte>,
        val peers: Map<UUID, List<Byte>>,
        val securityFields: Map<Int, List<Byte>>,
    )

    companion object {
        private fun bytes(vararg values: Int) = values.map { it.toByte() }
        private fun expect(condition: Boolean, message: String) {
            if (!condition) throw AssertionError(message)
        }
    }
}
