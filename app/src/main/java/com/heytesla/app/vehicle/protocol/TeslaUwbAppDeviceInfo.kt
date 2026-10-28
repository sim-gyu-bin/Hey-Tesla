package com.heytesla.app.vehicle.protocol

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.Closeable

/** Private VCSEC field 44, not a FiRa request and not authentication approval. */
internal enum class TeslaUwbAppDeviceInfoRequest(val wireValue: Int) {
    NONE(0), GET_MODEL_NUMBER(1), ONCHANGE_BATTERY_STATE(2),
}

internal enum class TeslaUwbAvailability(val wireValue: Int) {
    UNKNOWN(0), AVAILABLE(1), UNSUPPORTED(2), PERMISSION_DENIED(3), UNAVAILABLE(4), DISABLED_BY_USER(5),
}

internal enum class TeslaUwbAppBatteryState(val wireValue: Int) {
    UNKNOWN(0), UNPLUGGED(1), CHARGING(2), FULL(3),
}

internal enum class TeslaUwbAppThermalState(val wireValue: Int) {
    UNKNOWN(0), NOMINAL(1), FAIR(2), LIGHT(3), MODERATE(4), SERIOUS(5), SEVERE(6), CRITICAL(7), EMERGENCY(8), SHUTDOWN(9),
}

internal data class TeslaUwbAppDeviceInfoVersion(
    val osVersion: Long,
    val brandHash: Long,
    val incrementalHash: Long,
    val appMajor: Long,
    val appMinor: Long,
    val appPatch: Long,
) {
    init {
        require(osVersion in 0..0xffff_ffffL && brandHash in 0..0xffff_ffffL && incrementalHash in 0..0xffff_ffffL)
        require(appMajor in 0..0xffff_ffffL && appMinor in 0..0xffff_ffffL && appPatch in 0..0xffff_ffffL)
    }
    override fun toString(): String = "AppDeviceInfoVersion"
}

internal data class TeslaUwbAppDeviceBatteryState(
    val percent: Int?,
    val state: TeslaUwbAppBatteryState,
    val thermal: TeslaUwbAppThermalState,
    val lowPower: Boolean,
) {
    init { require(percent == null || percent in 0..100) }
    override fun toString(): String = "AppDeviceBatteryState"
}

/** The model digest identifies a device model, never an installation or a unique device. */
internal class TeslaUwbAppDeviceInfo(
    val modelSha256: ByteArray,
    val availability: TeslaUwbAvailability,
    val version: TeslaUwbAppDeviceInfoVersion,
    val batteryState: TeslaUwbAppDeviceBatteryState? = null,
) : Closeable {
    init { require(modelSha256.size == 32) }
    override fun close() = modelSha256.fill(0)
    override fun toString(): String = "AppDeviceInfo"
}

/** One source per BLE owner. subscribe is called once; close seals callbacks and releases all listeners. */
internal interface TeslaUwbAppDeviceInfoSource : Closeable {
    fun snapshot(includeBatteryState: Boolean): TeslaUwbAppDeviceInfo
    fun subscribe(onChanged: () -> Unit)
}

internal fun teslaUwbAppBatteryPercent(level: Int, scale: Int): Int? =
    if (scale > 0 && level in 0..scale) (level.toLong() * 100L / scale).toInt() else null

internal fun teslaUwbAppBatteryState(androidStatus: Int): TeslaUwbAppBatteryState = when (androidStatus) {
    2 -> TeslaUwbAppBatteryState.CHARGING
    3, 4 -> TeslaUwbAppBatteryState.UNPLUGGED
    5 -> TeslaUwbAppBatteryState.FULL
    else -> TeslaUwbAppBatteryState.UNKNOWN
}

internal fun teslaUwbAppThermalState(androidStatus: Int): TeslaUwbAppThermalState = when (androidStatus) {
    0 -> TeslaUwbAppThermalState.NOMINAL
    1 -> TeslaUwbAppThermalState.LIGHT
    2 -> TeslaUwbAppThermalState.MODERATE
    3 -> TeslaUwbAppThermalState.SEVERE
    4 -> TeslaUwbAppThermalState.CRITICAL
    5 -> TeslaUwbAppThermalState.EMERGENCY
    6 -> TeslaUwbAppThermalState.SHUTDOWN
    else -> TeslaUwbAppThermalState.UNKNOWN
}

/** Identifies a failure after a field 44 tag was actually observed, not an unrelated FiRa error. */
internal class TeslaUwbAppDeviceInfoException(cause: Exception) :
    IllegalArgumentException("INVALID_DEVICE_INFO_REQUEST", cause)

internal object TeslaUwbAppDeviceInfoProtocol {
    /** Reject duplicate/conflicting dispatch fields like the existing all-or-nothing FiRa decoder. */
    fun decodeRequest(body: ByteArray): TeslaUwbAppDeviceInfoRequest? {
        require(body.size <= TESLA_BLE_MAX_FRAME_BYTES)
        val input = CodedInputStream.newInstance(body)
        var request: TeslaUwbAppDeviceInfoRequest? = null
        var requestFieldSeen = false
        var otherDispatch = false
        try {
            while (!input.isAtEnd) {
                val tag = input.readTag()
                when (tag ushr 3) {
                    44 -> {
                        requestFieldSeen = true
                        require(tag and 7 == 0) { "INVALID_DEVICE_INFO_REQUEST" }
                        require(request == null) { "AMBIGUOUS_DEVICE_INFO_REQUEST" }
                        request = when (input.readEnum()) {
                            0 -> TeslaUwbAppDeviceInfoRequest.NONE
                            1 -> TeslaUwbAppDeviceInfoRequest.GET_MODEL_NUMBER
                            2 -> TeslaUwbAppDeviceInfoRequest.ONCHANGE_BATTERY_STATE
                            else -> throw IllegalArgumentException("UNKNOWN_DEVICE_INFO_REQUEST")
                        }
                    }
                    3, 48, 54 -> {
                        otherDispatch = true
                        require(input.skipField(tag)) { "INVALID_DEVICE_INFO_REQUEST" }
                    }
                    else -> require(input.skipField(tag)) { "INVALID_DEVICE_INFO_REQUEST" }
                }
            }
            require(request == null || !otherDispatch) { "AMBIGUOUS_DEVICE_INFO_REQUEST" }
            return request
        } catch (failure: Exception) {
            if (requestFieldSeen) throw TeslaUwbAppDeviceInfoException(failure)
            throw failure
        }
    }

    /** ToVCSEC field 40 AppDeviceInfo, proto3 defaults omitted; no challenge/token/time echo. */
    fun response(info: TeslaUwbAppDeviceInfo): ByteArray {
        require(info.modelSha256.size == 32)
        val version = info.version
        val versionSize = uintSize(1, version.osVersion) + uintSize(2, version.brandHash) +
            uintSize(3, version.incrementalHash) + uintSize(4, version.appMajor) +
            uintSize(5, version.appMinor) + uintSize(6, version.appPatch)
        val battery = info.batteryState
        val bodySize = CodedOutputStream.computeByteArraySize(1, info.modelSha256) + uintSize(2, 1) +
            uintSize(3, info.availability.wireValue.toLong()) + nestedSize(4, versionSize) +
            (battery?.let { uintSize(6, (it.percent ?: 0).toLong()) + uintSize(7, it.state.wireValue.toLong()) +
                uintSize(8, it.thermal.wireValue.toLong()) + uintSize(9, if (it.lowPower) 1 else 0) } ?: 0)
        val bytes = ByteArray(nestedSize(40, bodySize))
        try {
            val output = CodedOutputStream.newInstance(bytes)
            output.writeTag(40, 2)
            output.writeUInt32NoTag(bodySize)
            output.writeByteArray(1, info.modelSha256)
            output.uint(2, 1) // ANDROID; never claim to be the Tesla application or another OS.
            output.uint(3, info.availability.wireValue.toLong())
            output.writeTag(4, 2)
            output.writeUInt32NoTag(versionSize)
            output.uint(1, version.osVersion)
            output.uint(2, version.brandHash)
            output.uint(3, version.incrementalHash)
            output.uint(4, version.appMajor)
            output.uint(5, version.appMinor)
            output.uint(6, version.appPatch)
            // batchedNI (field 5) is false, hence absent. GET_MODEL omits all of fields 6..9.
            if (battery != null) {
                output.uint(6, (battery.percent ?: 0).toLong())
                output.uint(7, battery.state.wireValue.toLong())
                output.uint(8, battery.thermal.wireValue.toLong())
                output.uint(9, if (battery.lowPower) 1 else 0)
            }
            output.checkNoSpaceLeft()
            return bytes
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun uintSize(field: Int, value: Long): Int =
        if (value == 0L) 0 else CodedOutputStream.computeUInt32Size(field, value.toInt())

    private fun nestedSize(field: Int, size: Int): Int =
        CodedOutputStream.computeTagSize(field) + CodedOutputStream.computeUInt32SizeNoTag(size) + size

    private fun CodedOutputStream.uint(field: Int, value: Long) {
        if (value != 0L) writeUInt32(field, value.toInt())
    }
}
