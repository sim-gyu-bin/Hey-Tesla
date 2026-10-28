package com.heytesla.app.ui

import com.heytesla.app.vehicle.navigation.VehicleNavigationPolicy
import com.heytesla.app.vehicle.navigation.VehicleNavigationPresentation
import com.heytesla.app.vehicle.navigation.VehicleNavigationStatus
import com.heytesla.app.vehicle.uwb.TeslaUwbDistance
import com.heytesla.app.vehicle.uwb.TeslaUwbProbeState
import com.heytesla.app.vehicle.uwb.TeslaUwbStage

import java.util.Locale

/** Display expiry only; this does not establish a vehicle position or a ranging timeout. */
internal const val VEHICLE_UWB_SAMPLE_TTL_MS = 2_000L

/** Bound collection by the authenticated fix age, not a new sixty-second window at receipt. */
internal fun vehicleNavigationCollectionDeadline(
    fixEpochMs: Long, receivedAtElapsedMs: Long, nowEpochMs: Long, nowElapsedMs: Long,
): Long? {
    if (fixEpochMs <= 0L || receivedAtElapsedMs < 0L || nowEpochMs <= 0L || nowElapsedMs < 0L) return null
    val age = maxOf(0L, nowEpochMs - fixEpochMs, nowElapsedMs - receivedAtElapsedMs)
    val remaining = VehicleNavigationPolicy.VEHICLE_FIX_TTL_MS - age
    if (remaining < 0L) return -1L
    return if (nowElapsedMs <= Long.MAX_VALUE - remaining) nowElapsedMs + remaining else null
}

/** This only decides whether existing phone IO may continue; it never arms a lease or starts IO. */
internal fun vehicleNavigationShouldCollect(
    operationActive: Boolean,
    readRejected: Boolean,
    vehicleFixDeadlineElapsedMs: Long?,
    presentationStatus: VehicleNavigationStatus,
    nowElapsedMs: Long,
): Boolean {
    if (readRejected || presentationStatus == VehicleNavigationStatus.CLOCK_INVALID ||
        presentationStatus == VehicleNavigationStatus.VEHICLE_LOCATION_STALE) return false
    val deadline = vehicleFixDeadlineElapsedMs ?: return operationActive
    return nowElapsedMs >= 0L && deadline >= 0L && nowElapsedMs <= deadline
}

internal fun teslaUwbSampleIsRecent(
    state: TeslaUwbProbeState,
    sample: TeslaUwbDistance,
    nowElapsedMs: Long,
): Boolean = state.stage == TeslaUwbStage.RANGING &&
    sample.peerIndex >= 0 && sample.peerIndex in state.livePeerIndices &&
    sample.meters.isFinite() && sample.meters >= 0.0 && sample.confidence in 0..2 &&
    sample.receivedAtElapsedMs >= 0 && nowElapsedMs >= sample.receivedAtElapsedMs &&
    nowElapsedMs - sample.receivedAtElapsedMs <= VEHICLE_UWB_SAMPLE_TTL_MS

/** Latest live callback wins; equal callback times select the lower peer index, not the nearest peer. */
internal fun vehicleUwbRecentSample(state: TeslaUwbProbeState, nowElapsedMs: Long): TeslaUwbDistance? {
    if (!state.serviceOwned || state.stage != TeslaUwbStage.RANGING) return null
    var selected: TeslaUwbDistance? = null
    for (sample in state.samples) {
        if (!teslaUwbSampleIsRecent(state, sample, nowElapsedMs)) continue
        val previous = selected
        if (previous == null || sample.receivedAtElapsedMs > previous.receivedAtElapsedMs ||
            (sample.receivedAtElapsedMs == previous.receivedAtElapsedMs && sample.peerIndex < previous.peerIndex)
        ) selected = sample
    }
    return selected
}

/** Angles remain raw: public API documentation and framework implementation disagree on units. */
internal fun vehicleUwbAngleStatus(supported: Boolean?, raw: Double?): String = when {
    supported == false -> "미지원"
    supported == null -> "지원 확인 전"
    raw == null || !raw.isFinite() -> "지원 · 미수신"
    else -> "수신 · 단위 확인 필요"
}

internal fun vehicleUwbNotificationText(state: TeslaUwbProbeState, nowElapsedMs: Long): String? {
    if (!state.serviceOwned || state.stage == TeslaUwbStage.IDLE) return null
    return when (state.stage) {
        TeslaUwbStage.CLEANUP_FAILED -> "UWB 종료 불명 · 앱 프로세스 재시작 필요"
        TeslaUwbStage.CLEANING_UP -> "UWB 종료 확인 중 · 과거 거리 제외"
        TeslaUwbStage.FAILED -> "UWB 측정 실패 · 설정·개발자 진단에서 확인"
        TeslaUwbStage.TIMED_OUT -> when (state.reason) {
            "DISTANCE_RECEIVE_TIMEOUT" -> "UWB 거리 수신 중단 · 측정 종료"
            "NO_DISTANCE_RECEIVED" -> "UWB 유효 거리 미수신 · 측정 종료"
            else -> "UWB 대기 시간 종료 · 과거 거리 제외"
        }
        TeslaUwbStage.BLOCKED -> when (state.reason) {
            "RANGING_PERMISSION_REQUIRED" -> "UWB 권한 필요 · 설정에서 거리 측정 권한을 직접 허용해 주세요"
            "EXISTING_KEY_UNAVAILABLE" -> "UWB 시작 안 됨 · 등록된 앱 키를 확인해 주세요"
            "UWB_NOT_ENABLED" -> "UWB 시작 안 됨 · 휴대전화 UWB 설정을 확인해 주세요"
            else -> "UWB 시작 차단 · 설정·개발자 진단에서 확인"
        }
        TeslaUwbStage.COMPLETE -> "UWB 측정 종료 · 과거 거리 제외"
        TeslaUwbStage.CANCELED -> "UWB 측정 중지 · 과거 거리 제외"
        TeslaUwbStage.PREPARING -> "UWB 지원·등록 키 확인 중 · 거리 미수신"
        TeslaUwbStage.AUTHENTICATING -> "UWB 차량 키 인증 중 · 거리 미수신"
        TeslaUwbStage.WAITING_FOR_VEHICLE -> "UWB 차량 응답 대기 · 거리 미수신"
        TeslaUwbStage.STARTING -> "UWB 측정 시작 중 · 거리 미수신"
        TeslaUwbStage.RANGING -> {
            val sample = vehicleUwbRecentSample(state, nowElapsedMs)
            when {
                sample != null -> "UWB 실측 ${String.format(Locale.ROOT, "%.2f", sample.meters)} m · peer ${sample.peerIndex + 1}" +
                    " · 방위각 ${vehicleUwbAngleStatus(state.azimuthSupported, sample.azimuthRaw)}" +
                    " · 고도각 ${vehicleUwbAngleStatus(state.elevationSupported, sample.elevationRaw)}" +
                    " · 최근 수신값이며 위치 보장 아님"
                state.samples.isEmpty() -> "UWB 측정 중 · 유효 거리 미수신"
                state.livePeerIndices.isEmpty() -> "UWB peer 측정 중지 · 과거 거리 제외"
                else -> "UWB 수신 지연 · 최근 2초 내 유효 거리 없음"
            }
        }
        TeslaUwbStage.IDLE -> null
    }
}

internal fun vehicleUwbCompactText(state: TeslaUwbProbeState, nowElapsedMs: Long): String {
    val sample = vehicleUwbRecentSample(state, nowElapsedMs)
    return if (sample != null) "UWB 실측 ${String.format(Locale.ROOT, "%.2f", sample.meters)} m"
        else "UWB 실측 미수신"
}

/** Only geometry's fresh-distance states may render a number. This never changes a radio gate. */
internal fun vehicleNavigationDistance(presentation: VehicleNavigationPresentation): Double? {
    val fresh = when (presentation.status) {
        VehicleNavigationStatus.DIRECTION_AVAILABLE, VehicleNavigationStatus.DISTANCE_ONLY,
        VehicleNavigationStatus.NEARBY, VehicleNavigationStatus.HEADING_UNAVAILABLE,
        VehicleNavigationStatus.HEADING_STALE, VehicleNavigationStatus.HEADING_UNSUPPORTED,
        VehicleNavigationStatus.HEADING_UNRELIABLE -> true
        else -> false
    }
    return presentation.distanceMeters?.takeIf { fresh && it.isFinite() && it >= 0.0 }
}

internal fun vehicleNavigationArrow(presentation: VehicleNavigationPresentation): Double? =
    presentation.arrowDegrees?.takeIf { presentation.status == VehicleNavigationStatus.DIRECTION_AVAILABLE &&
        vehicleNavigationDistance(presentation) != null && it.isFinite() && it >= 0.0 && it < 360.0 }

internal fun vehicleNavigationText(presentation: VehicleNavigationPresentation): String {
    val distance = vehicleNavigationDistance(presentation)
    val arrow = vehicleNavigationArrow(presentation)
    val reason = when (presentation.status) {
        VehicleNavigationStatus.OFF -> "서비스 OFF"
        VehicleNavigationStatus.START_REQUIRED -> "앱에서 위치 안내 재개 필요"
        VehicleNavigationStatus.STARTING -> "위치 서비스 시작 확인 중"
        VehicleNavigationStatus.LOCATION_PERMISSION_REQUIRED -> "정확한 위치 권한 필요"
        VehicleNavigationStatus.LOCATION_DISABLED -> "휴대전화 위치 서비스 꺼짐"
        VehicleNavigationStatus.LOCATION_UNAVAILABLE -> "휴대전화 위치 수신 불가"
        VehicleNavigationStatus.FOREGROUND_FAILED -> "위치 서비스 시작 안 됨 · 앱에서 재개"
        VehicleNavigationStatus.CLEANUP_FAILED -> "위치 센서·서비스 종료 불명 · 앱 재시작 필요"
        VehicleNavigationStatus.VEHICLE_LOCATION_UNAVAILABLE,
        VehicleNavigationStatus.WAITING_FOR_VEHICLE_LOCATION -> "차량 좌표 미제공"
        VehicleNavigationStatus.WAITING_FOR_PHONE_LOCATION -> "휴대전화 위치 수신 대기"
        VehicleNavigationStatus.VEHICLE_LOCATION_STALE -> "차량 좌표 기한 만료"
        VehicleNavigationStatus.PHONE_LOCATION_STALE -> "휴대전화 위치 기한 만료"
        VehicleNavigationStatus.PHONE_LOCATION_INACCURATE -> "휴대전화 위치 정확도 부족"
        VehicleNavigationStatus.HEADING_UNAVAILABLE -> "나침반 수신 대기"
        VehicleNavigationStatus.HEADING_STALE -> "나침반 기한 만료"
        VehicleNavigationStatus.HEADING_UNSUPPORTED -> "나침반 미지원"
        VehicleNavigationStatus.HEADING_UNRELIABLE -> "나침반 불안정"
        VehicleNavigationStatus.CLOCK_INVALID -> "위치 시각 확인 불가"
        VehicleNavigationStatus.CLOCK_SAMPLE_DELAYED -> "위치 시각 확인 지연"
        VehicleNavigationStatus.NEARBY -> "가까운 위치 · 방향 생략"
        VehicleNavigationStatus.DISTANCE_ONLY -> if (distance != null && distance > 1_000.0) "방향 안내 범위 밖" else "방향 확인 안 됨"
        VehicleNavigationStatus.DIRECTION_AVAILABLE -> "방향 확인 안 됨"
    }
    if (distance == null) return "좌표 안내 · $reason"
    val approximate = if (distance >= 1_000.0) String.format(Locale.ROOT, "약 %.1f km", distance / 1_000.0)
        else String.format(Locale.ROOT, "약 %.0f m", distance)
    val direction = if (arrow == null) reason else {
        val relative = when (((arrow + 22.5) / 45.0).toInt() % 8) {
            0 -> "정면"
            1 -> "오른쪽 앞"
            2 -> "오른쪽"
            3 -> "오른쪽 뒤"
            4 -> "뒤쪽"
            5 -> "왼쪽 뒤"
            6 -> "왼쪽"
            else -> "왼쪽 앞"
        }
        "폰 상단 기준 $relative"
    }
    return "좌표 안내 $approximate · $direction"
}
