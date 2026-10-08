package com.heytesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.heytesla.app.VehicleVin
import com.heytesla.app.VehicleVinState
import com.heytesla.app.VehicleVinStatus

/** 편집 중 원문만 RAM에 둔다. 저장 상태와 마스킹 표시는 런타임이 유일한 원본이다. */
@Composable
internal fun VehicleVinSection(state: VehicleVinState, actions: AppActions) {
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                input = ""
                editing = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            input = ""
            editing = false
        }
    }
    val blocked = actions.vehicleVinChangeBlockedReason()
    val canEdit = blocked == null && state.status != VehicleVinStatus.LOADING && state.status != VehicleVinStatus.SAVING
    val validInput = remember(input) { VehicleVin.isValid(input) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("공통 차량 VIN", style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (state.status) {
                VehicleVinStatus.LOADING -> "등록 VIN 복원 중"
                VehicleVinStatus.NOT_REGISTERED -> "VIN 등록 필요"
                VehicleVinStatus.READY -> "VIN 저장됨 · ${state.maskedVin.orEmpty()}"
                VehicleVinStatus.SAVING -> "VIN 암호화 저장 중 · 완료 전 실행 불가"
                VehicleVinStatus.FAILED -> "VIN 저장소 오류 · 차량 작업 차단"
            },
            color = if (state.status == VehicleVinStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        state.reason?.let { Text("저장소 상태 코드 · $it", style = MaterialTheme.typography.bodySmall) }
        if (editing) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("공통 VIN 입력 · 17자리") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = canEdit,
                isError = input.isNotEmpty() && !validInput,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("17자리 영문·숫자만 입력하세요. I·O·Q는 제외하며 앞뒤 공백과 소문자는 저장 시 정규화합니다.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    if (canEdit && validInput) {
                        val submitted = input
                        input = ""
                        editing = false
                        actions.saveVehicleVin(submitted)
                    }
                },
                enabled = canEdit && validInput,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("VIN 암호화 저장") }
            TextButton(onClick = { input = ""; editing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("VIN 입력 취소") }
        } else {
            TextButton(
                onClick = { if (canEdit) editing = true },
                enabled = canEdit,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (state.status == VehicleVinStatus.READY) "등록 VIN 변경" else "공통 VIN 등록") }
        }
        if (blocked != null) {
            Text(vehicleVinBlockedLabel(blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text("앱 전용 암호화 저장소에 보관합니다. 키 등록·인증·P 조회·BLE 감지는 등록된 VIN을 사용하며 저장·복원만으로 시작하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun vehicleVinConsumerLabel(state: VehicleVinState): String =
    if (state.status == VehicleVinStatus.READY) "등록된 VIN 사용 · ${state.maskedVin.orEmpty()}"
    else "개발자 진단 상단에서 공통 VIN을 먼저 등록하세요. 저장·복원 완료 후 직접 시작해야 합니다."

internal fun vehicleVinBlockedLabel(reason: String): String = when (reason) {
    "VIN_CHANGE_BLOCKED_VEHICLE_CHOOSER" -> "차량 선택 요청 중 · 선택 결과가 확인될 때까지 VIN을 변경할 수 없습니다."
    "VIN_CHANGE_BLOCKED_CLEANUP", "SPEECH_SUPPORT_CLEANUP_FAILED", "UWB_SUPPORT_CLEANUP_FAILED", "SPEECH_TRIAL_CLEANUP_FAILED" ->
        "진단 정리 불명 · VIN 변경 차단 · 앱 프로세스 재시작 필요"
    "VIN_LOADING" -> "등록 VIN 복원 중 · 완료 후 변경할 수 있습니다."
    "VIN_SAVING" -> "VIN 저장 중 · 완료 후 변경할 수 있습니다."
    "VISIBLE_UI_REQUIRED" -> "화면이 보이는 동안에만 VIN을 변경할 수 있습니다."
    "UNLOCKED_UI_REQUIRED" -> "휴대폰 잠금을 해제한 뒤 VIN을 변경하세요."
    else -> "VIN 변경 불가 · 실행 또는 정리를 먼저 마치세요. 코드 $reason"
}
