#!/usr/bin/env python3
"""읽기 전용 v1–v39 현장 로그 분석. 회전 파일은 오래된 archive→active 순서로 입력한다.

schema33 제품 음성은 고정 상태/사유만 보존하며 인식 원문·PCM 파일이 없다.
schema35 인증 BODY/단발 wake의 인증 ACK와 비인증 nominal ACK는 구분하며 기상·fresh P·열림을 뜻하지 않는다.
schema37 DRIVE 분류는 상관 일치 domain3 인증 응답에서만 보존하며 fresh P·시각 freshness를 뜻하지 않는다.
schema39 음성 진단은 bounded 수치·실제 정리 bool만 보존한다. voiceOutcomes null은 미관측이지 0회가 아니다.
명령 인식 승인과 SDK 전송·응답·열림 관측은 별개다. 과거 진단 음성 요약은 기존 의미로 유지한다.
"""
import argparse
import csv
import hashlib
import json
import math
import re
import sys
import uuid
from collections import Counter
from contextlib import ExitStack
from datetime import datetime, timezone

MAX_LINE = 65536
SUPPORTED_VERSIONS = range(1, 40)
APPROACH_RECOVERY_VERSIONS = range(18, 40)
STATUSES = set('IDLE BLOCKED CONNECTING DISCOVERING SUBSCRIBING OBSERVING CLEANING_UP COMPLETE CANCELED TIMED_OUT FAILED CLEANUP_FAILED'.split())
REASONS = set('INTERNAL_FAILURE SESSION_EXPIRED CONNECT_REQUEST_FAILED USER_CANCELED SUBSCRIPTION_OBSERVED CONNECTION_LOST CONNECTION_FAILED DISCOVERY_REQUEST_FAILED DISCOVERY_FAILED PROFILE_READ_FAILED TESLA_SERVICE_MISSING TESLA_TX_MISSING TESLA_RX_MISSING RX_SUBSCRIPTION_UNSUPPORTED RX_CCCD_MISSING LOCAL_SUBSCRIPTION_FAILED SUBSCRIPTION_REQUEST_FAILED SUBSCRIPTION_FAILED LOCAL_CLOSE_FAILED_RESTART_REQUIRED CONNECTING_TIMEOUT DISCOVERING_TIMEOUT SUBSCRIBING_TIMEOUT OBSERVING_TIMEOUT LEASE_UNAVAILABLE LEASE_LOST VISIBLE_UI_REQUIRED BLUETOOTH_PERMISSION_REQUIRED BLUETOOTH_PERMISSION_REVOKED BLUETOOTH_UNAVAILABLE BLUETOOTH_OFF SINGLE_ASSOCIATION_REQUIRED ASSOCIATION_ADDRESS_UNAVAILABLE ASSOCIATION_RESOLVE_FAILED ASSOCIATION_REMOVED DIAGNOSTIC_EXCLUSIVITY_LOST PREREQUISITE_READ_FAILED USER_STOP DISABLED DEPARTED BLE_FIELD_NOTIFICATION_STOP FIELD_OWNER_NOT_ARMED OBSERVATION_ACTIVE FIELD_MARKER_UNAVAILABLE FIELD_LOG_UNHEALTHY FIELD_MARKER_WRITE_FAILED CDM_UNSUPPORTED CDM_UNAVAILABLE ASSOCIATION_REQUIRED MULTIPLE_ASSOCIATIONS_UNSUPPORTED OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED OBSERVATION_NOTIFICATIONS_BLOCKED OBSERVATION_FGS_START_TIMEOUT OBSERVATION_FGS_SECURITY_DENIED OBSERVATION_FGS_BACKGROUND_START_DENIED OBSERVATION_FGS_START_FAILED OBSERVE_SECURITY_DENIED OBSERVE_UNAVAILABLE OBSERVATION_SERVICE_DESTROYED BLUETOOTH_SCAN_PERMISSION_REQUIRED BLE_SCAN_ADDRESS_UNAVAILABLE BLE_SCAN_PERMISSION_REQUIRED BLE_SCAN_PERMISSION_REVOKED BLE_SCAN_NO_OFFLOADED_FILTER BLE_SCAN_UNAVAILABLE BLE_SCAN_START_FAILED BLE_SCAN_STOP_FAILED BLE_SCAN_CALLBACK_FAILED BLE_NAME_DETECTION_OPTIONS_INVALID VIN_FORMAT_INVALID BLE_SCAN_NAME_UNAVAILABLE'.split())
REASONS.update(('BLE_SCAN_NO_OFFLOADED_BATCH', 'BLE_SCAN_PENDING_INTENT_FAILED',
                'BLE_SCAN_DELIVERY_FAILED', 'BLE_SCAN_ORPHAN_CLEANUP_FAILED'))
REASONS.update(('BLE_LOCATION_PERMISSION_REQUIRED', 'BLE_LOCATION_DISABLED',
                'BLE_SCAN_LOCATION_PERMISSION_REQUIRED', 'BLE_SCAN_LOCATION_SERVICES_DISABLED',
                'BLE_SCAN_FGS_FALLBACK', 'BLE_SCAN_FGS_NOT_RUNNING',
                'BLE_DETECTION_PREFERENCE_FAILED', 'BLE_DETECTION_USER_STOP_REQUIRES_START'))
REASONS.update(('VIN_LOADING', 'VIN_NOT_REGISTERED', 'VIN_SAVING', 'VIN_RESTORE_FAILED', 'VIN_SAVE_FAILED'))
REASONS.add('GATT_WRITE_EXCEPTION')
REASONS.add('GATT_SERVICES_INVALIDATED')
REASONS.add('BLE_FIELD_WAKE_LOCK_UNAVAILABLE')
REASONS.update('BLE_AUTHENTICATED_READ_OPTIONS_INVALID FRESH_SCAN_REQUIRED SCAN_RESULT_STALE SCAN_DEVICE_UNAVAILABLE SCAN_PAUSE_FAILED SCAN_RESUME_FAILED FIELD_OWNER_LOST APPROACH_EXPIRED CANDIDATE_WITHDRAWN ATTEMPT_SUPERSEDED EXISTING_KEY_UNAVAILABLE EXISTING_KEY_INVALID KEY_PREPARATION_FAILED BODY_STATUS_VERIFIED DRIVE_STATE_VERIFIED AUTHENTICATED_READ_COMPLETE BODY_READ_FAILED DRIVE_READ_FAILED BODY_READ_TIMEOUT DRIVE_READ_TIMEOUT READ_DEADLINE_EXPIRED DEADLINE_EXPIRED AUTHENTICATION_FAILED AUTHENTICATION_TIMEOUT ASSOCIATION_CHANGED READINESS_CHECK_FAILED GATT_CONNECT_REJECTED GATT_CONNECT_PERMISSION_DENIED GATT_CONNECT_FAILED GATT_CONNECTION_LOST GATT_DISCOVERY_REJECTED GATT_DISCOVERY_FAILED TESLA_GATT_PROFILE_UNSUPPORTED GATT_MTU_REJECTED GATT_MTU_FAILED GATT_NOTIFICATION_REJECTED GATT_SUBSCRIPTION_REJECTED GATT_SUBSCRIPTION_FAILED PROTOCOL_START_FAILED GATT_WRITE_FAILED GATT_WRITE_REJECTED RX_FRAME_INVALID RX_BUFFER_LIMIT GATT_CALLBACK_FAILED TX_FRAME_INVALID PROTOCOL_RESPONSE_INVALID LOCAL_CLEANUP_FAILED_RESTART_REQUIRED'.split())
KINDS = set('PRESENCE_RECEIVED PRESENCE_DISPATCHED PRESENCE_REJECTED CANDIDATE_SIGNAL CANDIDATE_MERGED CANDIDATE_EXPIRED SIGNAL_SHADOWED SIGNAL_SELF_SUPPRESSED SCAN_STARTED SCAN_STOPPED SCAN_FAILED SCAN_MATCH RETRY_SCHEDULED RETRY_SUPPRESSED GATT_REQUEST GATT_CALLBACK GATT_CALLBACK_IGNORED GATT_FIRST_RX'.split())
SCAN_RECOVERY_KINDS = {'SCAN_RECOVERY_SCHEDULED', 'SCAN_RECOVERY_EXHAUSTED'}
SCAN_V8_KINDS = SCAN_RECOVERY_KINDS | {'SCAN_TIMER_DELAYED'}
KINDS.update(SCAN_V8_KINDS)
ENUMS = {
    'signal': set('CDM_BLE_APPEARED CDM_BLE_DISAPPEARED BT_CONNECTED BT_DISCONNECTED FILTERED_SCAN'.split()),
    'action': set('CONNECT DISCOVER PROFILE NOTIFICATION_ON NOTIFICATION_OFF CCCD_ON CCCD_OFF CCCD_READ DISCONNECT CLOSE CONNECTION_CALLBACK SERVICES_CALLBACK DESCRIPTOR_WRITE_CALLBACK DESCRIPTOR_READ_CALLBACK RX_CALLBACK'.split()),
    'gate': set('UNARMED STOPPING WRONG_ASSOCIATION NOT_OBSERVING OWNER_MISSING UNSUPPORTED DUPLICATE LOCAL_GATT RECENT_LOCAL_GATT COOLDOWN STALE PERMISSION UNAVAILABLE NO_OFFLOADED_FILTER BUDGET SUCCESS BLOCKED CLEANUP_FAILED OBSERVATION_ONLY'.split()),
    'result': set('ACCEPTED REJECTED COMPLETED EXCEPTION'.split()),
    'phase': set('CONNECTING DISCOVERING SUBSCRIBING OBSERVING CLEANING_UP'.split()),
}
OPTIONS = ('bleFieldObservationOnly', 'bleFieldSupplementalScan', 'bleFieldBtAssist', 'bleFieldBackgroundConnect', 'bleFieldRetryEnabled', 'bleFieldAdvertisedNameFilter', 'bleFieldAuthenticatedRead')
OPTION_VERSIONS = {'bleFieldAdvertisedNameFilter': 7, 'bleFieldAuthenticatedRead': 9}
SUMMARY_NUMBERS = ('gattStatus', 'primaryGattStatus', 'cleanupGattStatus', 'elapsedMs', 'phaseElapsedMs', 'firstRxElapsedMs', 'notificationCount', 'batteryPercent')
SUMMARY_BOOLS = ('connected', 'serviceFound', 'txFound', 'rxFound', 'subscriptionConfirmed', 'remoteUnsubscribeConfirmed', 'disconnectConfirmed', 'localClosed', 'leaseRetained', 'interactive', 'deviceLocked', 'backgroundConnect')
EVENTS = set('BLE_FIELD_START_REQUESTED BLE_FIELD_RUNNING BLE_FIELD_STOPPED BLE_FIELD_PREVIOUS_RUN_UNCLOSED BLE_FIELD_MARKER_READ_FAILED BLE_FIELD_MARKER_WRITE_FAILED BLE_FIELD_TRIAL_FINISHED BLE_FIELD_CLEANUP_FAILED BLE_FIELD_START_REJECTED BLE_EVIDENCE PROCESS_START_OFF'.split())
EVENTS.add('BLE_CANDIDATE_DIAGNOSTICS')
APPROACH_EVENTS = set('TESLA_APPROACH_READ_STARTED TESLA_APPROACH_READ_STAGE TESLA_APPROACH_BODY_VERIFIED TESLA_APPROACH_DRIVE_VERIFIED TESLA_APPROACH_READ_FINISHED TESLA_APPROACH_READ_BLOCKED TESLA_APPROACH_READ_CLEANUP_FAILED'.split())
WRITE_EVENTS = {'TESLA_APPROACH_WRITE_REQUESTED', 'TESLA_APPROACH_WRITE_RESULT', 'TESLA_APPROACH_WRITE_CALLBACK'}
APPROACH_EVENTS.update(WRITE_EVENTS)
RX_EVENTS = {'TESLA_APPROACH_RX_NOTIFICATION', 'TESLA_APPROACH_RX_FRAME', 'TESLA_APPROACH_RX_RESPONSE'}
APPROACH_EVENTS.update(RX_EVENTS)
RESPONSE_DISPOSITIONS = set('DOMAIN_MISMATCH ROUTE_MISMATCH REQUEST_UUID_MISMATCH ACK_WAITING SESSION_INFO_INVALID SESSION_INFO_AUTH_REJECTED SESSION_READY STATUS_VERIFIED STATUS_INCOMPLETE RESPONSE_REJECTED RESPONSE_INVALID RESPONSE_WAITING'.split())
RESPONSE_DISPOSITIONS.update(('FIRA_REQUEST', 'FIRA_STOP', 'FIRA_UNRECOGNIZED', 'FIRA_INVALID'))
RESPONSE_DISPOSITIONS_V29 = RESPONSE_DISPOSITIONS | {'RESPONSE_BEFORE_START'}
DESTINATION_KINDS = {'DOMAIN', 'ROUTING_ADDRESS', 'UNSET', 'UNKNOWN'}
PAYLOAD_KINDS = {'SESSION_INFO', 'SESSION_INFO_REQUEST', 'PROTOBUF_MESSAGE_AS_BYTES', 'UNSET', 'UNKNOWN'}
DOMAIN_MISMATCH_REASONS = {'FROM_MISSING', 'FROM_NOT_DOMAIN', 'DOMAIN_DIFFERENT'}
GATT_EVENTS = {'TESLA_APPROACH_GATT_CONNECTION_CALLBACK', 'TESLA_APPROACH_GATT_DESCRIPTOR_RESULT',
    'TESLA_APPROACH_GATT_DESCRIPTOR_CALLBACK', 'TESLA_APPROACH_WRITE_QUEUED'}
APPROACH_EVENTS.update(GATT_EVENTS)
CONNECT_EVENTS = {'TESLA_APPROACH_GATT_CONNECT_RESULT',
    'TESLA_APPROACH_GATT_RETRY_SCHEDULED', 'TESLA_APPROACH_GATT_RETRY_STARTED',
    'TESLA_APPROACH_GATT_RETRY_CANCELED'}
APPROACH_EVENTS.update(CONNECT_EVENTS)
EVENTS.update(APPROACH_EVENTS)
APPROACH_STAGES = set('IDLE PAUSING_SCAN PREPARING_KEY CONNECTING DISCOVERING NEGOTIATING_MTU SUBSCRIBING HANDSHAKING READING_STATUS CLEANING_UP RESUMING_SCAN COMPLETE BLOCKED CANCELED TIMED_OUT FAILED CLEANUP_FAILED'.split())
APPROACH_OUTCOMES = set('NOT_SENT PENDING CANCELED FAILED UNKNOWN VERIFIED_STATUS PARTIAL'.split())
APPROACH_QUERIES = {'BODY_STATUS', 'DRIVE_STATE'}
APPROACH_BOOLS = ('bodyVerified', 'driveVerified', 'localClosed', 'leaseRetained')
APPROACH_RECEIVED_TIMES = ('bodyReceivedElapsedMs', 'driveReceivedElapsedMs')
WRITE_EXCEPTIONS = {'SECURITY_EXCEPTION', 'ILLEGAL_ARGUMENT_EXCEPTION', 'ILLEGAL_STATE_EXCEPTION',
                    'REMOTE_EXCEPTION', 'RUNTIME_EXCEPTION', 'OTHER_EXCEPTION'}
WRITE_INTS = ('writeType', 'txProperties', 'negotiatedMtu', 'frameLength', 'chunkLength', 'frameOffset',
              'writeRequestStatus', 'writeCallbackStatus')
WRITE_TIMES = ('writeRequestedElapsedMs', 'writeReturnedElapsedMs', 'writeCallbackElapsedMs')
WRITE_BOOLS = ('writePendingBefore', 'descriptorPendingBefore')
DEADLINE_REASONS = {'SESSION_EXPIRED', 'APPROACH_EXPIRED', 'READ_DEADLINE_EXPIRED', 'DEADLINE_EXPIRED'} | {reason for reason in REASONS if reason.endswith('_TIMEOUT')}
AUTHENTICATED_RETRY_FAILURES = {
    'GATT_CONNECT_FAILED', 'GATT_CONNECTION_LOST', 'GATT_DISCOVERY_FAILED',
    'GATT_MTU_FAILED', 'GATT_SUBSCRIPTION_FAILED', 'GATT_WRITE_FAILED',
}
AUTHENTICATED_RETRY_DELAY_MS = 3500
CANDIDATE_READ_BUDGET_MS = 40000
FRUNK_EVENTS = set('TESLA_FRUNK_BLOCKED TESLA_FRUNK_COMMAND_REQUESTED TESLA_FRUNK_FRESH_P_VERIFIED TESLA_FRUNK_COMMAND_SENT TESLA_FRUNK_COMMAND_ACKNOWLEDGED TESLA_FRUNK_OPEN_OBSERVED TESLA_FRUNK_ACTION_REJECTED TESLA_FRUNK_RESULT_UNKNOWN TESLA_FRUNK_CANCELED TESLA_FRUNK_COMPLETE TESLA_FRUNK_FAILED TESLA_FRUNK_CLEANUP_FAILED TESLA_FRUNK_LOCAL_CLOSED FRUNK_VOICE_HANDOFF_BLOCKED FRUNK_VOICE_CORE_STARTED FRUNK_VOICE_CORE_UNKNOWN FRUNK_VOICE_SOURCE_BLOCKED FRUNK_VOICE_GRAMMAR_BLOCKED FRUNK_VOICE_CANCELED FRUNK_VOICE_CONFIDENCE_BLOCKED FRUNK_VOICE_CLEANUP_FAILED FRUNK_VOICE_EXPIRED FRUNK_VOICE_ADMISSION_BLOCKED FRUNK_VOICE_CONSENT_REQUIRED FRUNK_VOICE_MODEL_REQUIRED FRUNK_VOICE_STARTED FRUNK_VOICE_COMMAND_ACCEPTED FRUNK_VOICE_CONFIRMATION_ACCEPTED FRUNK_VOICE_RECOGNITION_FAILED FRUNK_VOICE_READINESS_LOST FRUNK_VOICE_FINAL_REQUIRED'.split())
FRUNK_STATE_KEYS = ('frunkCommandRequested', 'frunkCommandSent', 'frunkCommandAcknowledged',
    'frunkOpenObserved', 'frunkLocalClosed', 'frunkCleanupFailed')
FRUNK_RECOGNITION_EVENT = 'FRUNK_VOICE_RECOGNITION_RESULT'
FRUNK_EVENTS.add(FRUNK_RECOGNITION_EVENT)
FRUNK_RECOGNITION_MAX_CANDIDATES = 3
FRUNK_RECOGNITION_MAX_TEXT = 160
FRUNK_RECOGNITION_MASK = '[마스킹]'
# 앱의 FrunkVoiceRecognition과 같은 전체 토큰 목록. 입력 로그도 신뢰하지 않는다.
FRUNK_RECOGNITION_VOCABULARY = set((
    '헤이', '테슬라', '헤이테슬라', '프렁크', '열어줘', '프렁크열어줘', '헤이테슬라프렁크열어줘',
    '취소', '취소해', '취소해줘', '아니', '아니야', '안', '안돼', '말고',
    '하지', '마', '하지마', '열지', '열지마', '마세요', '열지마세요', '열지말아줘',
    '프렁크열지마', '헤이테슬라프렁크열지마', '트렁크', '트렁크열어줘', '헤이테슬라트렁크열어줘',
))
FRUNK_RECOGNITION_REASONS = set((
    'FRUNK_VOICE_SOURCE_BLOCKED', 'FRUNK_VOICE_GRAMMAR_BLOCKED', 'FRUNK_VOICE_CANCELED',
    'FRUNK_VOICE_CONFIDENCE_BLOCKED', 'FRUNK_VOICE_EXPIRED', 'FRUNK_VOICE_FINAL_REQUIRED',
    'FRUNK_VOICE_READINESS_LOST', 'FRUNK_VOICE_RECOGNITION_FAILED', 'FRUNK_VOICE_CLEANUP_FAILED',
))

# FieldJson/ProductServiceLog와 같은 schema33 경계. 임의 event/reason은 출력하지 않는다.
PRODUCT_EVENTS = set('PRODUCT_VOICE_CONSENT_GRANTED PRODUCT_VOICE_CONSENT_REVOKED PRODUCT_VOICE_START_REQUESTED PRODUCT_VOICE_STATE_CHANGED PRODUCT_VOICE_CLEANUP_FAILED PRODUCT_VOICE_COMMAND_ACCEPTED PRODUCT_VOICE_STOPPED TESLA_CONNECTION_STARTED TESLA_CONNECTION_BLOCKED TESLA_CONNECTION_CLEANUP_FAILED TESLA_CONNECTION_STATE_CHANGED TESLA_CONNECTION_GATT_OBSERVATION'.split())
PRODUCT_VOICE_DIAGNOSTIC_EVENT = 'PRODUCT_VOICE_DIAGNOSTIC'
PRODUCT_EVENTS.add(PRODUCT_VOICE_DIAGNOSTIC_EVENT)
PRODUCT_VOICE_OUTCOMES = {'PREPARED', 'EMPTY', 'GRAMMAR_NO_MATCH', 'ACCEPTED', 'FAILURE', 'SEALED', 'CLOSED'}
PRODUCT_VOICE_BOOLS = ('modelReused', 'microphoneReleased', 'streamCleanupConfirmed', 'recognizerCleanupConfirmed')
PRODUCT_VOICE_CAPTURE_KEYS = ('utteranceBytes', 'voicedBytes', 'suppressedOnsets',
    'silenceBytesAtEnd', 'captureMs', 'decodeMs', 'modelReused')
PRODUCT_WAKE_EVENTS = set('TESLA_CONNECTION_BODY_VERIFIED TESLA_CONNECTION_WAKE_STARTED TESLA_CONNECTION_WAKE_ACKNOWLEDGED TESLA_CONNECTION_WAKE_NOMINAL_ACK TESLA_CONNECTION_WAKE_REJECTED TESLA_CONNECTION_WAKE_UNCONFIRMED TESLA_CONNECTION_WAKE_SKIPPED_AWAKE TESLA_CONNECTION_WAKE_SKIPPED_UNKNOWN TESLA_CONNECTION_SESSION_RETRANSMIT'.split())
# schema 36부터만 기록되는 사건. 이전 행의 같은 이름은 위조로 보고 버린다.
PRODUCT_V36_EVENTS = {'TESLA_CONNECTION_SESSION_RETRANSMIT'}
# schema38의 실제 새 초기 DRIVE 발급. 과거 행에 주입된 이름은 수용하지 않는다.
PRODUCT_V38_EVENTS = {'TESLA_CONNECTION_DRIVE_RETRY'}
PRODUCT_WAKE_EVENTS.update(PRODUCT_V38_EVENTS)
# VCSEC VehicleSleepStatus_E 정수. 진단 전용이며 P·기상·제어 판정에 쓰지 않는다.
VCSEC_SLEEP_STATUSES = {0: 'UNKNOWN', 1: 'AWAKE', 2: 'ASLEEP'}
PRODUCT_WAKE_STAGES = {'CHECKING_VEHICLE', 'WAKING_VEHICLE'}
PRODUCT_WAKE_REASONS = set((
    'CONNECTION_BODY_RECEIPT_REQUIRED', 'CONNECTION_BODY_RECEIPT_STALE',
    'CONNECTION_ASLEEP_REQUIRED', 'CONNECTION_WAKE_DEADLINE_EXPIRED',
    'CONNECTION_BODY_RESPONSE_UNEXPECTED', 'CONNECTION_DRIVE_RESPONSE_REQUIRED',
    'CONNECTION_QUERY_RESULT_UNEXPECTED', 'CONNECTION_WAKE_RESULT_UNEXPECTED',
    'CONNECTION_WAKE_FRAME_UNEXPECTED', 'CONNECTION_WAKE_FRAME_REQUIRED',
    'CONNECTION_WAKE_REJECTED', 'CONNECTION_WAKE_UNCONFIRMED',
    'WAKE_SEQUENCE_REQUIRED', 'WAKE_PURPOSE_INVALID', 'WAKE_EXPIRED', 'WAKE_CLOCK_CHANGED',
))
PRODUCT_REASONS = REASONS | set((
    'CONNECTION_EXPLICIT_P_REQUIRED', 'CONNECTION_P_RECEIPT_STALE',
    'CONNECTION_P_SOURCE_TIME_REQUIRED', 'CONNECTION_P_SOURCE_TIME_STALE',
    'CONNECTION_TIME_REVERSED', 'CONNECTION_OWNER_UNAVAILABLE',
    'CONNECTION_PROTOCOL_RESPONSE_INVALID', 'CONNECTION_PROTOCOL_START_FAILED',
    'CONNECTION_ENDED', 'CONNECTION_VIN_SNAPSHOT_CHANGED',
    'PRODUCT_VOICE_CONSENT_REQUIRED', 'VOICE_PREFERENCE_FAILED',
    'MIC_PERMISSION_REQUIRED', 'DEFAULT_ASSISTANT_REQUIRED', 'ASSISTANT_NOT_READY',
    'ASSISTANT_UNAVAILABLE', 'VOICE_FGS_START_FAILED', 'VOICE_FGS_START_TIMEOUT',
    'PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED', 'PRODUCT_VOICE_ON_DEVICE_UNAVAILABLE',
    'PRODUCT_VOICE_MODEL_MISSING', 'PRODUCT_VOICE_MODEL_LOAD_FAILED', 'PRODUCT_VOICE_DECODE_FAILED',
    'PRODUCT_VOICE_UTTERANCE_TRUNCATED', 'PRODUCT_VOICE_CLOCK_REVERSED',
    'PRODUCT_VOICE_UTTERANCE_INCOMPLETE', 'PRODUCT_VOICE_PCM_INCOMPLETE',
    'PRODUCT_VOICE_RECOGNITION_FAILED', 'PRODUCT_VOICE_FAILED', 'PRODUCT_VOICE_STATE_FAILED',
    'PRODUCT_VOICE_READINESS_UNKNOWN', 'PRODUCT_VOICE_AUDIO_MODE_BLOCKED',
    'PRODUCT_VOICE_AUDIO_STATE_UNKNOWN', 'PRODUCT_VOICE_STOPPED',
    'PRODUCT_VOICE_NOTIFICATION_STOP', 'PRODUCT_VOICE_NOTIFICATION_FAILED',
    'PRODUCT_VOICE_SERVICE_REJECTED', 'PRODUCT_VOICE_MIC_FGS_DENIED',
    'PRODUCT_VOICE_FGS_START_DENIED', 'PRODUCT_VOICE_SERVICE_FAILED',
    'PRODUCT_VOICE_SERVICE_DESTROYED', 'PRODUCT_VOICE_ASSISTANT_UNAVAILABLE',
    'PRODUCT_VOICE_OWNER_LOST', 'AUDIO_READ_FAILED', 'AUDIO_CAPTURE_FAILED',
    'INPUT_SILENCED', 'MIC_PERMISSION_REVOKED', 'UNLOCKED_UI_REQUIRED', 'SCREEN_ON_REQUIRED',
    'FRUNK_VOICE_SOURCE_BLOCKED', 'FRUNK_VOICE_GRAMMAR_BLOCKED', 'FRUNK_VOICE_CANCELED',
    'FRUNK_VOICE_CONFIDENCE_BLOCKED', 'FRUNK_VOICE_EXPIRED', 'FRUNK_VOICE_FINAL_REQUIRED',
    'FRUNK_VOICE_READINESS_LOST', 'FRUNK_VOICE_RECOGNITION_FAILED', 'FRUNK_VOICE_CLEANUP_FAILED',
    'FRUNK_VOICE_HANDOFF_BLOCKED', 'FRUNK_COMMAND_ALREADY_CONSUMED', 'FRUNK_CANCELED',
    'FRUNK_TIME_REVERSED', 'FRUNK_DRIVE_ALREADY_CONSUMED', 'FRUNK_FRESH_P_REQUIRED',
    'FRUNK_EXPLICIT_P_REQUIRED', 'FRUNK_P_RECEIPT_STALE', 'FRUNK_P_SOURCE_TIME_REQUIRED',
    'FRUNK_P_SOURCE_TIME_STALE', 'FRUNK_DUPLICATE_WRITE', 'FRUNK_COMMAND_INVALID',
    'FRUNK_COMMAND_EXPIRED', 'FRUNK_SEQUENCE_ALREADY_STARTED', 'FRUNK_SEQUENCE_INACTIVE',
    'FRUNK_COMMAND_NOT_WRITTEN', 'FRUNK_ACTION_REJECTED', 'FRUNK_VIN_SNAPSHOT_CHANGED',
    'FRUNK_ACTION_RESPONSE_UNKNOWN', 'FRUNK_OPEN_NOT_OBSERVED', 'FRUNK_SEQUENCE_REQUIRED',
    'FRUNK_PROTOCOL_RESPONSE_INVALID', 'FRUNK_PURPOSE_INVALID',
))
PRODUCT_ENUMS = {
    'connectionStage': set('IDLE PREPARING CONNECTING AUTHENTICATING MONITORING EXECUTING STOPPED BLOCKED FAILED'.split()),
    'connectionGear': {'P', 'R', 'N', 'D', 'UNKNOWN'},
    'connectionReason': PRODUCT_REASONS,
    'productVoiceStage': set('IDLE PREPARING LISTENING RECOGNIZING STOPPING COMMAND_READY STOPPED BLOCKED FAILED'.split()),
    'productVoiceReason': PRODUCT_REASONS,
}
PRODUCT_BOOLS = ('connectionParkFresh', 'productVoiceConsent')
PRODUCT_KEYS = (*PRODUCT_ENUMS, *PRODUCT_BOOLS)
PRODUCT_RELATED_EVENTS = PRODUCT_EVENTS | PRODUCT_WAKE_EVENTS | FRUNK_EVENTS | {'TESLA_KEY_CLEANUP_FAILED', 'TESLA_KEY_LOCAL_CLOSED'}
PRODUCT_CLEANUP_REASONS = {
    'PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED', 'FRUNK_VOICE_CLEANUP_FAILED',
    'LOCAL_CLEANUP_FAILED_RESTART_REQUIRED', 'LOCAL_CLOSE_FAILED_RESTART_REQUIRED',
}
PRODUCT_FRUNK_EVENT_KEYS = {
    'TESLA_FRUNK_COMMAND_REQUESTED': 'frunkCommandRequested',
    'TESLA_FRUNK_COMMAND_SENT': 'frunkCommandSent',
    'TESLA_FRUNK_COMMAND_ACKNOWLEDGED': 'frunkCommandAcknowledged',
    'TESLA_FRUNK_OPEN_OBSERVED': 'frunkOpenObserved',
    'TESLA_FRUNK_LOCAL_CLOSED': 'frunkLocalClosed',
    'TESLA_FRUNK_CLEANUP_FAILED': 'frunkCleanupFailed',
}


def product_state(value, version):
    """버전·타입·고정 코드만 검증한다. freshness나 정리 성공을 단계명으로 추론하지 않는다."""
    safe = dict.fromkeys(PRODUCT_KEYS)
    if version < 33 or not isinstance(value, dict):
        return safe
    safe.update({key: allowed(value.get(key), choices) for key, choices in PRODUCT_ENUMS.items()})
    if version >= 35:
        safe['connectionStage'] = allowed(value.get('connectionStage'),
            PRODUCT_ENUMS['connectionStage'] | PRODUCT_WAKE_STAGES)
        for key in ('connectionReason', 'productVoiceReason'):
            safe[key] = allowed(value.get(key), PRODUCT_REASONS | PRODUCT_WAKE_REASONS)
    safe.update({key: value.get(key) if type(value.get(key)) is bool else None for key in PRODUCT_BOOLS})
    return safe


def product_voice_diagnostic(value, version, event):
    """전용 schema39 사건의 bounded 수치·실제 bool만 보존하며 원문은 복사하지 않는다."""
    if version < 39 or event != PRODUCT_VOICE_DIAGNOSTIC_EVENT or not isinstance(value, dict):
        return None
    outcome = allowed(value.get('outcome'), PRODUCT_VOICE_OUTCOMES)
    utterance = integer(value.get('utteranceBytes'))
    if utterance is not None and (not 0 <= utterance <= 320000 or utterance % 2):
        utterance = None
    safe = {'outcome': outcome, 'utteranceBytes': utterance}
    for key in ('voicedBytes', 'silenceBytesAtEnd'):
        count = integer(value.get(key))
        safe[key] = count if (count is not None and utterance is not None
                             and 0 <= count <= utterance and count % 2 == 0) else None
    for key, upper in (('suppressedOnsets', 2**31 - 1), ('captureMs', 86400000),
                       ('loadMs', 600000), ('decodeMs', 600000), ('cleanupMs', 600000)):
        number = integer(value.get(key))
        safe[key] = number if number is not None and 0 <= number <= upper else None
    safe.update({key: value.get(key) if type(value.get(key)) is bool else None
                 for key in PRODUCT_VOICE_BOOLS})
    if outcome in {'PREPARED', 'CLOSED'}:
        safe.update(dict.fromkeys(PRODUCT_VOICE_CAPTURE_KEYS))
    audio_clean = safe['microphoneReleased'] is True and safe['streamCleanupConfirmed'] is True
    if ((outcome in {'EMPTY', 'GRAMMAR_NO_MATCH', 'ACCEPTED'} and not audio_clean) or
            (outcome in {'ACCEPTED', 'CLOSED'} and safe['recognizerCleanupConfirmed'] is not True)):
        safe['outcome'] = None
    return safe


def summarize_product(points):
    """연결 시작~KEY 정리와 바로 뒤 FRUNK 정리를 묶되 시작 미관측/다른 scope는 합치지 않는다."""
    scopes, cleanup_tails, sessions = {}, {}, []
    for point in points:
        scope = (point['processId'], point['trialId'], point['segment'])
        event = point['event']
        started = event == 'TESLA_CONNECTION_STARTED'
        session = scopes.get(scope)
        # KEY 정리로 활성 scope는 닫고, 같은 scope의 다음 제품 사건이 FRUNK 정리일 때만 잇는다.
        # 새 시작·다른 사건은 꼬리를 버리며 시작 미관측 세션은 꼬리로 보관하지 않는다.
        tail = cleanup_tails.pop(scope, None)
        if session is None and event in {'TESLA_FRUNK_LOCAL_CLOSED', 'TESLA_FRUNK_CLEANUP_FAILED'}:
            session = tail
        if started or session is None:
            session = {
                'processId': point['processId'], 'trialId': point['trialId'], 'segment': point['segment'],
                'firstLine': point['line'], 'lastLine': point['line'], 'startObserved': started,
                'eventCounts': {}, 'commandAcceptedCount': 0, 'voiceOutcomes': None,
                'frunkObserved': dict.fromkeys(FRUNK_STATE_KEYS),
                'cleanupFailureObserved': False, 'microphoneCleanupConfirmed': None,
            }
            sessions.append(session)
            if started:
                scopes[scope] = session
        session['lastLine'] = point['line']
        session['lastSnapshot'] = point['state']
        session['eventCounts'][event] = session['eventCounts'].get(event, 0) + 1
        session['commandAcceptedCount'] += event == 'PRODUCT_VOICE_COMMAND_ACCEPTED'
        diagnostic = point['productVoiceDiagnostic']
        if event == PRODUCT_VOICE_DIAGNOSTIC_EVENT and diagnostic is not None:
            if session['voiceOutcomes'] is None:
                session['voiceOutcomes'] = {}
            outcome = diagnostic['outcome']
            if outcome is not None:
                session['voiceOutcomes'][outcome] = session['voiceOutcomes'].get(outcome, 0) + 1
        # runtime snapshot에는 이전 수동/제품 회차 bool이 남을 수 있다.
        # 이 관측 구간의 실제 전이 event와 해당 bool이 함께 있을 때만 집계한다.
        key = PRODUCT_FRUNK_EVENT_KEYS.get(event)
        if key is not None:
            value = point['frunkState'][key]
            if value is True or session['frunkObserved'][key] is None:
                session['frunkObserved'][key] = value
        session['cleanupFailureObserved'] |= (
            event in {'PRODUCT_VOICE_CLEANUP_FAILED', 'TESLA_CONNECTION_CLEANUP_FAILED',
                      'TESLA_KEY_CLEANUP_FAILED', 'TESLA_FRUNK_CLEANUP_FAILED'}
            or point['state']['connectionReason'] in PRODUCT_CLEANUP_REASONS
            or point['state']['productVoiceReason'] in PRODUCT_CLEANUP_REASONS)
        if event in {'TESLA_KEY_LOCAL_CLOSED', 'TESLA_CONNECTION_CLEANUP_FAILED', 'TESLA_KEY_CLEANUP_FAILED'}:
            closed = scopes.pop(scope, None)
            if closed is not None and event != 'TESLA_CONNECTION_CLEANUP_FAILED':
                cleanup_tails[scope] = closed
    return sessions


UWB_EVENTS = set('TESLA_UWB_STARTED TESLA_UWB_BLOCKED TESLA_UWB_AUTHENTICATING TESLA_UWB_AUTHENTICATED TESLA_UWB_CAPABILITIES_UNSPECIFIED TESLA_UWB_CAPABILITIES_RECEIVED TESLA_UWB_REQUEST_RECEIVED TESLA_UWB_RESPONSE_SENT TESLA_UWB_BACKEND_OPENED TESLA_UWB_PEER_STARTED TESLA_UWB_DISTANCE_RECEIVED TESLA_UWB_INVALID_RESULT TESLA_UWB_PEER_STOPPED TESLA_UWB_PEERS_ENDED TESLA_UWB_ENDPOINT_CLOSING TESLA_UWB_VEHICLE_STOP_RECEIVED TESLA_UWB_BACKEND_CLOSED TESLA_UWB_SESSION_STOPPED_SENT TESLA_UWB_SESSION_STOPPED_NOT_SENT TESLA_UWB_CLEANING_UP TESLA_UWB_COMPLETE TESLA_UWB_CANCELED TESLA_UWB_FAILED TESLA_UWB_TIMED_OUT TESLA_UWB_CLEANUP_FAILED TESLA_UWB_LOCAL_CLOSED'.split())
UWB_NATIVE_RECORD_EVENTS = {
    'STARTING': 'TESLA_UWB_BACKEND_STARTING', 'OPENED': 'TESLA_UWB_BACKEND_OPENED',
    'PEER_STARTED': 'TESLA_UWB_PEER_STARTED', 'PEER_STOPPED': 'TESLA_UWB_PEER_STOPPED',
    'CLOSED': 'TESLA_UWB_BACKEND_CLOSED',
}
UWB_EVENTS.add('TESLA_UWB_BACKEND_STARTING')
UWB_NATIVE_RANGES = {
    'uwbNativeConfigId': (1, 5), 'uwbNativePreambleIndex': (9, 12),
    'uwbNativeUpdateRate': (1, 3), 'uwbNativePeerCount': (1, 2**31 - 1),
}
UWB_NATIVE_KEYS = ('uwbNativeSessionTag', 'uwbNativeConfigId', 'uwbNativeChannel',
    'uwbNativePreambleIndex', 'uwbNativeUpdateRate', 'uwbNativePeerCount',
    'uwbNativeEvent', 'uwbNativePeerIndex', 'uwbNativeElapsedSinceStartMs',
    'uwbNativeCloseRequested')
UWB_SESSION_TAG = re.compile(r'[0-9a-f]{64}')
UWB_METRIC_INTS = ('activeDurationMs', 'rangingCount', 'validRangingCount',
    'parallelSessionCount', 'channel', 'rangingIntervalMs')
UWB_METRIC_PACKAGES = {'android', 'com.teslamotors.tesla', 'com.google.android.gms'}
UWB_METRIC_TIMESTAMP = re.compile(r'[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}')
UWB_STAGES = set('IDLE PREPARING AUTHENTICATING WAITING_FOR_VEHICLE STARTING RANGING CLEANING_UP COMPLETE CANCELED FAILED TIMED_OUT BLOCKED CLEANUP_FAILED'.split())
UWB_GATT_EVENT = 'TESLA_UWB_GATT_OBSERVATION'
UWB_EVENTS.add(UWB_GATT_EVENT)
UWB_INITIAL_DEVICE_INFO_EVENT = 'TESLA_UWB_INITIAL_DEVICE_INFO_SENT'
UWB_EVENTS.add(UWB_INITIAL_DEVICE_INFO_EVENT)
UWB_GATT_EVENTS = set('STAGE CONNECT_RESULT CONNECTION_CALLBACK DESCRIPTOR_CALLBACK RX WRITE RETRY_SCHEDULED RETRY_STARTED RETRY_CANCELED LOCAL_CLOSED CLEANUP_FAILED'.split())
UWB_GATT_STAGES = set('IDLE PREPARING_KEY CONNECTING DISCOVERING NEGOTIATING_MTU SUBSCRIBING WAITING_FOR_CARD HANDSHAKING READING_STATUS CLEANING_UP COMPLETE BLOCKED CANCELED TIMED_OUT FAILED CLEANUP_FAILED'.split())
UWB_DEADLINE_CAUSES = {'OPERATION', 'INITIAL_NEGOTIATION', 'PROTOCOL_STAGE', 'WRITE'}
UWB_GATT_BOOLS = ('uwbGattAutoConnect', 'uwbGattConnectAccepted', 'uwbGattConnectedObserved',
    'uwbGattServiceFound', 'uwbGattTxFound', 'uwbGattRxFound', 'uwbGattSubscriptionConfirmed')
UWB_GATT_INTS = ('uwbGattConnectionStatus', 'uwbGattConnectionState',
    'uwbGattDescriptorRequestStatus', 'uwbGattDescriptorCallbackStatus')
UWB_GATT_TIMES = ('uwbGattOperationStartedElapsedMs', 'uwbGattStageStartedElapsedMs',
    'uwbGattObservedElapsedMs', 'uwbGattConnectIssuedElapsedMs', 'uwbGattConnectReturnedElapsedMs',
    'uwbGattAdvertisementAgeAtConnectMs', 'uwbGattConnectionCallbackElapsedMs',
    'uwbGattDescriptorCallbackElapsedMs', 'uwbGattFirstRxElapsedMs', 'uwbGattLastRxElapsedMs')
UWB_GATT_KEYS = ('uwbGattStage', 'uwbGattEvent', 'uwbGattConnectAction', 'uwbDeadlineCause',
    *UWB_GATT_BOOLS, *UWB_GATT_INTS, *UWB_GATT_TIMES, 'uwbGattRxNotificationCount')
UWB_RX_BOOLS = ('uwbRxDecoderIncomplete', 'uwbRxDecoderFailed', 'uwbRxFromPresent',
    'uwbRxToPresent', 'uwbRxRequestUuidPresent', 'uwbRxRequestUuidMatches',
    'uwbRxToRouteMatches', 'uwbRxSignedStatusPresent')
UWB_RX_INTS = ('uwbRxExpectedDomain', 'uwbRxFromDomain', 'uwbRxSignedMessageFault', 'uwbRxOperationStatus')
UWB_RX_TIMES = ('uwbRxProtocolReceivedElapsedMs', 'uwbRxProtocolObservedElapsedMs')
UWB_RX_COUNTS = ('uwbRxFrameCount', 'uwbRxProtocolCount')
UWB_RX_ENUMS = {
    'uwbRxStep': set('WAITING SEND COMPLETE FRUNK_RESULT FAILED UWB_READY UWB_INPUT'.split()),
    'uwbRxInputKind': set('ROUTABLE_MESSAGE RAW_FIRA WRAPPED_PLAINTEXT ENCRYPTED_RESPONSE'.split()),
    'uwbRxAead': {'NOT_ATTEMPTED', 'VERIFIED', 'FAILED'},
    'uwbRxFiraKind': {'REQUEST', 'STOP', 'UNRECOGNIZED', 'INVALID'},
    'uwbRxAuthEvidence': set('ACKNOWLEDGED REQUEST_REJECTED KEY_NOT_PAIRED_REPORTED OPERATION_REJECTED UNEXPECTED_PAYLOAD SESSION_STATUS_UNSUPPORTED SIGNATURE_MISSING TAG_MISSING TAG_LENGTH_INVALID PARAMETERS_INVALID HMAC_MISMATCH SESSION_INFO_INVALID'.split()),
    'uwbRxDisposition': RESPONSE_DISPOSITIONS,
    'uwbRxFromKind': DESTINATION_KINDS,
    'uwbRxToKind': DESTINATION_KINDS,
    'uwbRxPayloadKind': PAYLOAD_KINDS,
    'uwbRxDomainMismatchReason': DOMAIN_MISMATCH_REASONS,
}
UWB_RX_ENUMS_V29 = dict(UWB_RX_ENUMS,
    uwbRxStep=UWB_RX_ENUMS['uwbRxStep'] | {'IGNORED'},
    uwbRxDisposition=RESPONSE_DISPOSITIONS_V29)

# schema34 제품 GATT는 역사 UWB state와 분리하고 동일 protocol 어휘만 공유한다.
CONNECTION_GATT_EVENT = 'TESLA_CONNECTION_GATT_OBSERVATION'
CONNECTION_GATT_EVENTS = UWB_GATT_EVENTS | {'PROTOCOL'}
CONNECTION_BOOLS = ('autoConnect', 'connectAccepted', 'connectedObserved', 'serviceFound',
    'txFound', 'rxFound', 'subscriptionConfirmed', 'rxDecoderIncomplete', 'rxDecoderFailed')
CONNECTION_INTS = ('connectionStatus', 'connectionState', 'descriptorRequestStatus',
    'descriptorCallbackStatus')
CONNECTION_TIMES = ('stageStartedElapsedMs', 'connectIssuedElapsedMs', 'connectReturnedElapsedMs',
    'connectionCallbackElapsedMs', 'descriptorCallbackElapsedMs', 'firstRxElapsedMs',
    'lastRxElapsedMs', 'rxProtocolReceivedElapsedMs', 'rxProtocolObservedElapsedMs')
CONNECTION_COUNTERS = {'notification': 'rxNotificationCount', 'frame': 'rxFrameCount',
    'protocol': 'rxProtocolCount'}
CONNECTION_RESPONSE_ENUMS = {
    'disposition': RESPONSE_DISPOSITIONS_V29, 'fromKind': DESTINATION_KINDS,
    'toKind': DESTINATION_KINDS, 'payloadKind': PAYLOAD_KINDS,
    'domainMismatchReason': DOMAIN_MISMATCH_REASONS,
    'step': UWB_RX_ENUMS_V29['uwbRxStep'], 'inputKind': UWB_RX_ENUMS['uwbRxInputKind'],
    'aead': UWB_RX_ENUMS['uwbRxAead'], 'authEvidence': UWB_RX_ENUMS['uwbRxAuthEvidence'],
}
CONNECTION_DRIVE_ENUMS = {
    'driveShiftState': {'ABSENT', 'EMPTY', 'INVALID', 'P', 'R', 'N', 'D', 'SNA', 'UNRECOGNIZED'},
    'driveTimestampStatus': {'ABSENT', 'INVALID', 'NON_POSITIVE', 'VALID'},
}
CONNECTION_RESPONSE_BOOLS = ('fromPresent', 'toPresent', 'requestUuidPresent',
    'requestUuidMatches', 'toRouteMatches', 'signedStatusPresent')
CONNECTION_RESPONSE_INTS = ('expectedDomain', 'fromDomain', 'signedMessageFault', 'operationStatus')
CONNECTION_CLASSIFICATIONS = (*CONNECTION_RESPONSE_ENUMS, 'requestUuidMatches', 'toRouteMatches',
    'signedMessageFault', 'operationStatus', *CONNECTION_DRIVE_ENUMS)
CONNECTION_TERMINAL_STAGES = {'IDLE', 'CLEANING_UP', 'COMPLETE', 'BLOCKED', 'CANCELED',
    'TIMED_OUT', 'FAILED', 'CLEANUP_FAILED'}
UWB_RX_BODY_KEYS = ('uwbRxBodyBytes', 'uwbRxBodyStatus', 'uwbRxBodyFields')
UWB_RX_OBSERVATION_ENUMS = {
    'uwbRxBodyStatus': {'COMPLETE', 'TRUNCATED', 'MALFORMED', 'UNSUPPORTED'},
    'uwbRxRequestCorrelation': {'CURRENT', 'PREVIOUS', 'UNMATCHED'},
}
UWB_RX_OBSERVATION_KEYS = (*UWB_RX_BODY_KEYS, 'uwbRxRequestCorrelation')
UWB_RX_BODY_FIELD = re.compile(r'[1-9][0-9]{0,8}/[0125]/(?:0|[1-9][0-9]{0,3})')
UWB_RX_KEYS = (*UWB_RX_COUNTS, 'uwbRxNotificationFrameCount', *UWB_RX_BOOLS,
    *UWB_RX_TIMES, *UWB_RX_INTS, *UWB_RX_ENUMS, *UWB_RX_OBSERVATION_KEYS)
UWB_NONNEGATIVE_INTS = ('uwbPeerCount', 'uwbStartedPeerCount', 'uwbValidDistanceCount',
    'uwbInvalidResultCount', 'uwbLastPeerIndex')
UWB_REASONS = REASONS | set((
    'BACKEND_UNAVAILABLE', 'CAPABILITIES_TIMEOUT', 'CAPABILITIES_REQUEST_FAILED',
    'CAPABILITIES_CALLBACK_FAILED', 'CAPABILITIES_CHANGED', 'CAPABILITIES_INVALID',
    'CAPABILITIES_UNSPECIFIED', 'MEASUREMENT_SCHEDULE_FAILED', 'INVALID_RANGING_RESULT',
    'NATIVE_CLOSE_TIMEOUT', 'NATIVE_START_FAILED', 'NATIVE_OPEN_FAILED', 'NATIVE_BACKEND_FAILED',
    'NATIVE_CLEANUP_FAILED', 'NO_DISTANCE_RECEIVED', 'DISTANCE_RECEIVE_TIMEOUT',
    'NEGOTIATION_FAILED', 'UWB_NOT_ENABLED',
    'DISTANCE_UNSUPPORTED', 'CHANNEL_INTERSECTION_EMPTY', 'PREAMBLE_INTERSECTION_EMPTY',
    'SLOT_DURATION_UNSUPPORTED', 'CONFIG_UNSUPPORTED', 'UPDATE_RATE_UNSUPPORTED',
    'NEGOTIATION_ALREADY_ACTIVE', 'EMPTY_REQUEST', 'DUPLICATE_ENDPOINT', 'STOP_WITHOUT_SESSION',
    'INVALID_STOP_ENDPOINT', 'UNKNOWN_STOP_ENDPOINT', 'UNSUPPORTED_ENDPOINT',
    'INVALID_PEER_ADDRESSES',
    'VEHICLE_STS_UNSUPPORTED', 'STS_MODE_UNSUPPORTED', 'INVALID_FIRA_MESSAGE',
    'AMBIGUOUS_FIRA_MESSAGE', 'UWB_PURPOSE_INVALID', 'UWB_AUTHENTICATED_SESSION_REQUIRED',
    'UWB_SESSION_EXPIRED', 'UWB_ENCRYPTED_RESPONSE_REJECTED', 'UWB_PROTOCOL_RESPONSE_INVALID',
    'UWB_VIN_SNAPSHOT_CHANGED', 'RANGING_PERMISSION_REQUIRED', 'RANGING_PERMISSION_DENIED',
    'UI_PAUSED', 'UI_DESTROYED', 'DIAGNOSTICS_HIDDEN', 'USER_STOP',
))

NAVIGATION_EVENT = 'VEHICLE_NAVIGATION_OBSERVATION'
NAVIGATION_KINDS = set('ADMISSION FOREGROUND_ARMED FOREGROUND_STOPPED SOURCE_STARTED SOURCE_STOPPED PHONE_LOCATION_FIRST_VALID HEADING_FIRST_VALID PHONE_INPUT_UNAVAILABLE DISPLAY_STATUS_CHANGED SIDECAR_STARTED SIDECAR_SKIPPED HANDSHAKE_TX_COMPLETE QUERY_TX_COMPLETE HANDSHAKE_ACCEPTED SIDECAR_RX RESULT TERMINAL'.split())
NAVIGATION_REASONS = set('UNKNOWN LOCATION_PERMISSION_REQUIRED LOCATION_DISABLED LOCATION_UNAVAILABLE HEADING_UNSUPPORTED HEADING_UNRELIABLE SOURCE_START_FAILED CLEANUP_FAILED FOREGROUND_START_FAILED PRIMARY_NOT_AUTHENTICATED COORDINATES_NOT_WANTED CONVERSATION_UNAVAILABLE OPERATION_DEADLINE OWNER_ENDED SESSION_CLOSED ATTEMPT_ENDED VEHICLE_FIX_EXPIRED CLOCK_INVALID LOCATION_PROTOCOL_INVALID LOCATION_AUTHENTICATION_REJECTED LOCATION_OWNER_UNAVAILABLE LOCATION_TIMEOUT LOCATION_FIX_TIME_MISSING LOCATION_FIX_TIME_OVERFLOW LOCATION_FIX_TIME_FUTURE LOCATION_RECEIPT_TIME_INVALID LOCATION_COORDINATES_MISSING LOCATION_ESTIMATED_INVALID LOCATION_ESTIMATED_VALIDITY_MISSING LOCATION_NATIVE_PAIR_INCOMPLETE LOCATION_COORDINATE_SYSTEM_UNSUPPORTED LOCATION_NATIVE_UNSUPPORTED LOCATION_COORDINATES_INVALID LOCATION_PAIR_INCOMPLETE LOCATION_RAW_COORDINATES_INVALID LOCATION_RAW_PAIR_INCOMPLETE LOCATION_STATE_MISSING LOCATION_REQUEST_REJECTED'.split())
NAVIGATION_REASONS.add('OPERATION_ENDED_BEFORE_START')
NAVIGATION_REASONS_V32 = NAVIGATION_REASONS | {'VEHICLE_FIX_TIME_INVALID', 'LOCATION_STATE_TIME_INVALID'}
NAVIGATION_STATUSES = set('UNKNOWN OFF START_REQUIRED STARTING LOCATION_PERMISSION_REQUIRED LOCATION_DISABLED LOCATION_UNAVAILABLE HEADING_UNSUPPORTED HEADING_UNRELIABLE FOREGROUND_FAILED CLEANUP_FAILED VEHICLE_LOCATION_UNAVAILABLE WAITING_FOR_VEHICLE_LOCATION WAITING_FOR_PHONE_LOCATION VEHICLE_LOCATION_STALE PHONE_LOCATION_STALE PHONE_LOCATION_INACCURATE HEADING_UNAVAILABLE HEADING_STALE NEARBY DISTANCE_ONLY DIRECTION_AVAILABLE CLOCK_INVALID CLOCK_SAMPLE_DELAYED'.split())
NAVIGATION_STATUSES_V32 = NAVIGATION_STATUSES | {'VEHICLE_LOCATION_TIME_INVALID'}
NAVIGATION_DISPOSITIONS = set('UNKNOWN RESPONSE_INVALID RESPONSE_WAITING RESPONSE_REJECTED ACK_WAITING SESSION_INFO_INVALID SESSION_INFO_AUTH_REJECTED SESSION_READY DOMAIN_MISMATCH REQUEST_UUID_MISMATCH ROUTE_MISMATCH STATUS_VERIFIED STATUS_INCOMPLETE'.split())
NAVIGATION_DISPOSITIONS_V29 = NAVIGATION_DISPOSITIONS | {'RESPONSE_BEFORE_START'}
NAVIGATION_AUTH_EVIDENCE = set('UNKNOWN ACKNOWLEDGED REQUEST_REJECTED KEY_NOT_PAIRED_REPORTED OPERATION_REJECTED UNEXPECTED_PAYLOAD SESSION_STATUS_UNSUPPORTED SIGNATURE_MISSING TAG_MISSING TAG_LENGTH_INVALID PARAMETERS_INVALID HMAC_MISMATCH SESSION_INFO_INVALID'.split())
NAVIGATION_BOOLS = ('fineLocationGranted', 'backgroundLocationGranted', 'locationEnabled',
    'foregroundArmed', 'collecting', 'locationUpdatesRegistered', 'headingSensorRegistered',
    'cleanupSucceeded', 'requestUuidMatches', 'toRouteMatches', 'vehicleFixPresent')
NAVIGATION_PHONE_KINDS = {'SOURCE_STARTED', 'SOURCE_STOPPED', 'PHONE_LOCATION_FIRST_VALID',
    'HEADING_FIRST_VALID', 'PHONE_INPUT_UNAVAILABLE'}


def navigation_observation(value, version):
    """schema28+ 값 없는 allowlist. raw 입력이나 이전 관측을 복사하지 않는다."""
    if not isinstance(value, dict):
        return None
    kind = allowed(value.get('kind'), NAVIGATION_KINDS)
    if kind is None:
        return None
    safe = {'kind': kind, 'reason': allowed(value.get('reason'),
        NAVIGATION_REASONS_V32 if version >= 32 else NAVIGATION_REASONS)}
    for key in ('candidate', 'attempt'):
        number = integer(value.get(key))
        safe[key] = number if number is not None and number >= 0 else None
    safe.update({key: value.get(key) if type(value.get(key)) is bool else None
                 for key in NAVIGATION_BOOLS})
    safe['status'] = allowed(value.get('status'),
        NAVIGATION_STATUSES_V32 if version >= 32 else NAVIGATION_STATUSES)
    safe['disposition'] = allowed(value.get('disposition'),
        NAVIGATION_DISPOSITIONS_V29 if version >= 29 else NAVIGATION_DISPOSITIONS)
    safe['authEvidence'] = allowed(value.get('authEvidence'), NAVIGATION_AUTH_EVIDENCE)
    return safe


def summarize_navigation(points):
    """정확한 trial/process/clock/candidate/attempt별 사건 수. 불명 회차는 합치지 않는다."""
    scopes = {}
    for point in points:
        observation = point['observation']
        candidate, attempt = observation['candidate'], observation['attempt']
        complete_scope = candidate is not None and attempt is not None
        scope = (point['trialId'], point['processId'], point['segment'], candidate, attempt,
                 None if complete_scope else point['line'])
        group = scopes.setdefault(scope, {
            'trialId': point['trialId'], 'processId': point['processId'], 'segment': point['segment'],
            'candidate': candidate, 'attempt': attempt, 'scopeComplete': complete_scope,
            'firstLine': point['line'], 'lastLine': point['line'],
            'firstRecordElapsedMs': point['recordElapsedMs'],
            'lastRecordElapsedMs': point['recordElapsedMs'],
            'kindCounts': Counter(), 'phoneInputKindCounts': Counter(),
            'presentationStatusCounts': Counter(), 'lastResult': None, 'lastTerminal': None,
        })
        group['lastLine'] = point['line']
        group['lastRecordElapsedMs'] = point['recordElapsedMs']
        kind = observation['kind']
        group['kindCounts'][kind] += 1
        if kind in NAVIGATION_PHONE_KINDS:
            group['phoneInputKindCounts'][kind] += 1
        if kind == 'DISPLAY_STATUS_CHANGED':
            group['presentationStatusCounts'][observation['status'] or 'unobserved'] += 1
        if kind == 'RESULT':
            group['lastResult'] = point
        if kind == 'TERMINAL':
            group['lastTerminal'] = point
    for group in scopes.values():
        for key in ('kindCounts', 'phoneInputKindCounts', 'presentationStatusCounts'):
            group[key] = dict(group[key])
    return list(scopes.values())


def uwb_state(value, elapsed, version):
    """동일 거리 표본의 raw 스냅샷만 정제한다. 각도 단위·framework 시계는 해석하지 않는다."""
    safe = {'uwbStage': allowed(value.get('uwbStage'), UWB_STAGES),
        'uwbBackendOpened': value.get('uwbBackendOpened')
            if type(value.get('uwbBackendOpened')) is bool else None}
    for key in UWB_NONNEGATIVE_INTS:
        number = integer(value.get(key))
        safe[key] = number if number is not None and number >= 0 else None
    meters = value.get('uwbLastDistanceMeters')
    safe['uwbLastDistanceMeters'] = meters if (
        type(meters) in (int, float) and meters >= 0 and
        (type(meters) is int or math.isfinite(meters))) else None
    confidence = integer(value.get('uwbLastConfidence'))
    safe['uwbLastConfidence'] = confidence if confidence in (0, 1, 2) else None
    for key in ('uwbLastAzimuthRaw', 'uwbLastElevationRaw'):
        raw = value.get(key) if version >= 24 else None
        safe[key] = raw if type(raw) is float and math.isfinite(raw) else None
    for key in ('uwbLastAzimuthConfidence', 'uwbLastElevationConfidence'):
        confidence = integer(value.get(key)) if version >= 24 else None
        safe[key] = confidence if confidence in (0, 1, 2) else None
    for key in ('uwbAzimuthSupported', 'uwbElevationSupported'):
        supported = value.get(key) if version >= 24 else None
        safe[key] = supported if type(supported) is bool else None
    safe['uwbLastFrameworkTimestampMs'] = integer(value.get('uwbLastFrameworkTimestampMs'))
    received = integer(value.get('uwbLastReceivedElapsedMs'))
    safe['uwbLastReceivedElapsedMs'] = received if received is not None and 0 <= received <= elapsed else None
    # SDK reason 원값: 미정의 숫자도 보존하되 문자열·bool은 절대 내보내지 않는다.
    safe['uwbClosedReason'] = integer(value.get('uwbClosedReason'))
    safe['uwbOpenFailedReason'] = integer(value.get('uwbOpenFailedReason'))
    safe['uwbReason'] = allowed(value.get('uwbReason'), UWB_REASONS)
    safe.update(uwb_gatt_state(value, elapsed, version))
    safe.update(uwb_rx_state(value, elapsed, version))
    safe.update(uwb_native_state(value, elapsed, version))
    return safe


def uwb_native_state(value, elapsed, version):
    """schema31의 endpoint-local 관측만 허용한다. 과거 schema에서 추론하지 않는다."""
    safe = dict.fromkeys(UWB_NATIVE_KEYS)
    if version < 31:
        return safe
    tag = value.get('uwbNativeSessionTag')
    safe['uwbNativeSessionTag'] = tag if type(tag) is str and UWB_SESSION_TAG.fullmatch(tag) else None
    safe['uwbNativeEvent'] = allowed(value.get('uwbNativeEvent'), UWB_NATIVE_RECORD_EVENTS)
    for key, (lower, upper) in UWB_NATIVE_RANGES.items():
        number = integer(value.get(key))
        safe[key] = number if number is not None and lower <= number <= upper else None
    channel = integer(value.get('uwbNativeChannel'))
    safe['uwbNativeChannel'] = channel if channel in (5, 9) else None
    peer = integer(value.get('uwbNativePeerIndex'))
    count = safe['uwbNativePeerCount']
    safe['uwbNativePeerIndex'] = peer if peer is not None and count is not None and 0 <= peer < count else None
    since = integer(value.get('uwbNativeElapsedSinceStartMs'))
    safe['uwbNativeElapsedSinceStartMs'] = since if since is not None and 0 <= since <= elapsed else None
    close = value.get('uwbNativeCloseRequested')
    safe['uwbNativeCloseRequested'] = close if type(close) is bool else None
    return safe


def summarize_uwb_native(points):
    """operation·관측된 STARTING/CLOSED 경계별 집계. 누락된 시작·종료는 추론하지 않는다."""
    scopes = {}
    groups = []
    for point in points:
        state = point['state']
        tag, event = state['uwbNativeSessionTag'], state['uwbNativeEvent']
        operation = state['uwbGattOperationStartedElapsedMs']
        scope = (point['trialId'], point['processId'], point['segment'], operation, tag)
        since = state['uwbNativeElapsedSinceStartMs']
        observation = {key: state[key] for key in UWB_NATIVE_KEYS}
        identity = tuple(observation.values())
        complete = all(state[key] is not None for key in (
            'uwbNativeConfigId', 'uwbNativeChannel', 'uwbNativePreambleIndex',
            'uwbNativeUpdateRate', 'uwbNativePeerCount', 'uwbNativeCloseRequested'))
        peer_valid = (state['uwbNativePeerIndex'] is not None if event in ('PEER_STARTED', 'PEER_STOPPED')
                      else state['uwbNativePeerIndex'] is None)
        candidate = (complete and peer_valid and tag is not None and since is not None and
                     UWB_NATIVE_RECORD_EVENTS.get(event) == point['event'])
        point['nativeObservation'] = None
        if tag is None:
            continue
        current = scopes.get(scope)
        boundary = False
        if current is not None and candidate:
            if event == 'STARTING':
                # 같은 STARTING snapshot 반복만 제외한다. CLOSED 뒤에는 같은 값이어도 새 경계다.
                boundary = (current['group']['closedObserved'] or
                            current['lastEvent'] != 'STARTING' or identity not in current['seen'])
            elif current['group']['closedObserved'] and identity not in current['seen']:
                # 종료 뒤 새 callback만 있으면 시작 미관측인 별도 묶음이다. STARTING을 합성하지 않는다.
                boundary = True
        if current is None or boundary:
            group = {
                'trialId': point['trialId'], 'processId': point['processId'], 'segment': point['segment'],
                'operationStartedElapsedMs': operation, 'sessionTag': tag,
                # 이 입력 안의 묶음 순번이지 backend가 보고한 generation 값이 아니다.
                'lifecycleIndex': 0 if current is None else current['group']['lifecycleIndex'] + 1,
                'startingObserved': False, 'closedObserved': False,
                'eventCounts': Counter(), 'events': [],
            }
            current = {'group': group, 'seen': set(), 'lastEvent': None}
            scopes[scope] = current
            groups.append(group)
        if candidate and identity not in current['seen']:
            point['nativeObservation'] = observation
            current['seen'].add(identity)
            current['lastEvent'] = event
            group = current['group']
            group['startingObserved'] |= event == 'STARTING'
            group['closedObserved'] |= event == 'CLOSED'
            group['eventCounts'][event] += 1
            group['events'].append({'line': point['line'], 'recordElapsedMs': point['recordElapsedMs'],
                                    **observation})
    for group in groups:
        group['eventCounts'] = dict(group['eventCounts'])
    return groups


def uwb_session_tag(session_id):
    """signed int와 uint32를 같은 네 바이트로 정규화한다. 태그는 암호화가 아니다."""
    if type(session_id) is not int or not -(2**31) <= session_id <= 2**32 - 1:
        raise ValueError('INVALID_UWB_SESSION_ID')
    return hashlib.sha256(b'hey-tesla/uwb-session/v1:' +
                          (session_id & 0xffffffff).to_bytes(4, 'big')).hexdigest()


def uwb_metric_timestamp(value):
    """년·zone 없는 원 형식만 검증한다. epoch 변환·시각 자동 매칭은 하지 않는다."""
    if type(value) is not str or UWB_METRIC_TIMESTAMP.fullmatch(value) is None:
        return None
    try:
        datetime.strptime('2000-' + value, '%Y-%m-%d %H:%M:%S.%f')
    except ValueError:
        return None
    return value


def match_uwb_system(stream, report):
    """mRangingSessionList metric만 소비하며 raw ID·자유문구는 반환하지 않는다."""
    metrics, issues = [], []
    in_metrics = False
    section_observed = False
    for line, text, error in bounded_lines(stream):
        if error:
            issues.append({'line': line, 'code': error})
            continue
        text = text.strip()
        if text == '-- mRangingSessionList --':
            in_metrics = True
            section_observed = True
            continue
        if text.startswith('--'):
            in_metrics = False
        if not in_metrics or not text:
            continue
        # AOSP UwbMetrics.RangingSessionStats.toString의 쉼표 구분 key=value 한 행.
        fields = {}
        invalid = False
        for cell in text.split(','):
            key, separator, value = cell.strip().partition('=')
            if not separator or key in fields:
                invalid = True
                break
            fields[key] = value
        raw_id = fields.get('sessionId')
        if (not text.startswith('initTime=') or invalid or type(raw_id) is not str or
                re.fullmatch(r'-?(?:0|[1-9][0-9]{0,9})', raw_id) is None):
            issues.append({'line': line, 'code': 'INVALID_UWB_METRIC'})
            continue
        try:
            tag = uwb_session_tag(int(raw_id))
        except ValueError:
            issues.append({'line': line, 'code': 'INVALID_UWB_METRIC'})
            continue
        metric = {'sessionTag': tag, 'initTime': uwb_metric_timestamp(fields.get('initTime')),
                  'packageName': fields.get('packageName') if fields.get('packageName') in UWB_METRIC_PACKAGES else 'OTHER'}
        for key in UWB_METRIC_INTS:
            raw = fields.get(key)
            valid = type(raw) is str and re.fullmatch(r'(?:0|[1-9][0-9]{0,9})', raw) is not None
            number = int(raw) if valid else None
            metric[key] = number if number is not None and number <= 2**31 - 1 else None
        if metric['initTime'] is None or any(metric[key] is None for key in UWB_METRIC_INTS):
            issues.append({'line': line, 'code': 'INVALID_UWB_METRIC_FIELD'})
        metrics.append(metric)
    tags = sorted({point['state']['uwbNativeSessionTag'] for point in report['uwbEvents']
                   if point['state']['uwbNativeSessionTag'] is not None})
    matches = []
    for tag in tags:
        matching = [metric for metric in metrics if metric['sessionTag'] == tag]
        status = ('UNMATCHED' if not matching else
                  'UNIQUE_TAG_MATCH' if len(matching) == 1 else 'AMBIGUOUS_TAG_MATCH')
        matches.append({'sessionTag': tag, 'status': status, 'metrics': matching})
    return {'matches': matches, 'metrics': metrics, 'issues': issues, 'sectionObserved': section_observed,
            'timeBasis': 'MONTH_DAY_WITHOUT_YEAR_OR_ZONE', 'interpretation': 'TAG_CORRELATION_ONLY'}


def uwb_gatt_state(value, elapsed, version):
    safe = dict.fromkeys(UWB_GATT_KEYS)
    if version < 25:
        return safe
    safe['uwbGattStage'] = allowed(value.get('uwbGattStage'), UWB_GATT_STAGES)
    events = UWB_GATT_EVENTS | {'PROTOCOL'} if version >= 26 else UWB_GATT_EVENTS
    safe['uwbGattEvent'] = allowed(value.get('uwbGattEvent'), events)
    safe['uwbGattConnectAction'] = allowed(value.get('uwbGattConnectAction'), {'CREATE', 'RECONNECT'})
    safe['uwbDeadlineCause'] = allowed(value.get('uwbDeadlineCause'), UWB_DEADLINE_CAUSES) if version >= 30 else None
    for key in UWB_GATT_BOOLS:
        safe[key] = value.get(key) if type(value.get(key)) is bool else None
    for key in UWB_GATT_INTS:
        number = integer(value.get(key))
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
    for key in (*UWB_GATT_TIMES, 'uwbGattRxNotificationCount'):
        number = integer(value.get(key))
        upper = 2**63 - 1 if key == 'uwbGattRxNotificationCount' else elapsed
        safe[key] = number if number is not None and 0 <= number <= upper else None
    return safe


def uwb_rx_body(value):
    """본문 값은 받지 않고 canonical 숫자 shape 3개가 함께 유효할 때만 보존한다."""
    byte_count = integer(value.get('uwbRxBodyBytes'))
    status = allowed(value.get('uwbRxBodyStatus'), UWB_RX_OBSERVATION_ENUMS['uwbRxBodyStatus'])
    fields = value.get('uwbRxBodyFields')
    if (byte_count is None or not 0 <= byte_count <= 4096 or status is None or
            type(fields) is not str or len(fields) > 135):
        return dict.fromkeys(UWB_RX_BODY_KEYS)
    triples = fields.split(',') if fields else []
    if len(triples) > 8:
        return dict.fromkeys(UWB_RX_BODY_KEYS)
    minimum_bytes = 0
    for triple in triples:
        if UWB_RX_BODY_FIELD.fullmatch(triple) is None:
            return dict.fromkeys(UWB_RX_BODY_KEYS)
        number, wire, length = map(int, triple.split('/'))
        if (number > 536870911 or
                (wire == 0 and not 1 <= length <= 10) or
                (wire == 1 and length != 8) or
                (wire == 2 and length > 4096) or
                (wire == 5 and length != 4)):
            return dict.fromkeys(UWB_RX_BODY_KEYS)
        minimum_bytes += ((number << 3 | wire).bit_length() + 6) // 7 + length
        if wire == 2:
            minimum_bytes += max(1, (length.bit_length() + 6) // 7)
    # tag/length의 비최소 원 varint encoding은 shape만으로 복원할 수 없다.
    if (minimum_bytes > byte_count or
            (status == 'COMPLETE' and not triples and byte_count != 0) or
            (status == 'TRUNCATED' and (len(triples) != 8 or byte_count <= minimum_bytes)) or
            (status in ('MALFORMED', 'UNSUPPORTED') and
             (len(triples) >= 8 or byte_count <= minimum_bytes))):
        return dict.fromkeys(UWB_RX_BODY_KEYS)
    return dict(zip(UWB_RX_BODY_KEYS, (byte_count, status, fields)))


def uwb_rx_state(value, elapsed, version):
    safe = dict.fromkeys(UWB_RX_KEYS)
    if version < 26:
        return safe
    for key in UWB_RX_BOOLS:
        safe[key] = value.get(key) if type(value.get(key)) is bool else None
    for key in UWB_RX_INTS:
        number = integer(value.get(key))
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
    for key in (*UWB_RX_TIMES, *UWB_RX_COUNTS, 'uwbRxNotificationFrameCount'):
        number = integer(value.get(key))
        upper = (16 if key == 'uwbRxNotificationFrameCount' else
                 2**63 - 1 if key in UWB_RX_COUNTS else elapsed)
        safe[key] = number if number is not None and 0 <= number <= upper else None
    enums = UWB_RX_ENUMS_V29 if version >= 29 else UWB_RX_ENUMS
    for key, choices in enums.items():
        safe[key] = allowed(value.get(key), choices)
    if version >= 27:
        safe.update(uwb_rx_body(value))
        safe['uwbRxRequestCorrelation'] = allowed(value.get('uwbRxRequestCorrelation'),
            UWB_RX_OBSERVATION_ENUMS['uwbRxRequestCorrelation'])
    return safe


def summarize_uwb_rx(points):
    """작업·시계 구간·counter reset별 RX 분모와 최초 관측한 기한 원인을 분리한다. reason으로 원인을 추론하지 않는다."""
    scopes = {}
    operations = []
    count_keys = {'notification': 'uwbGattRxNotificationCount',
                  'frame': 'uwbRxFrameCount', 'protocol': 'uwbRxProtocolCount'}
    dimensions = ('uwbRxStep', 'uwbRxInputKind', 'uwbRxAead', 'uwbRxFiraKind',
                  'uwbRxAuthEvidence', 'uwbRxDisposition', 'uwbRxDomainMismatchReason',
                  *UWB_RX_OBSERVATION_ENUMS)
    for point in points:
        state = point['state']
        point['rxObservation'] = {'operationIndex': None, 'newNotification': False,
            'newProtocol': False, 'newFrameCount': None}
        started = state['uwbGattOperationStartedElapsedMs']
        if started is None:
            continue
        scope = (point['processId'], point['segment'], started)
        current = scopes.get(scope)
        counts = {name: state[key] for name, key in count_keys.items()}
        reset = current is not None and any(value is not None and
            current['counterTotals'][name] is not None and value < current['counterTotals'][name]
            for name, value in counts.items())
        if current is None or reset:
            current = {'operationIndex': len(operations), 'processId': scope[0], 'segment': scope[1],
                'operationStartedElapsedMs': started,
                'counterResetIndex': 0 if current is None else current['counterResetIndex'] + 1,
                'counterResetObserved': reset, 'firstLine': point['line'], 'lastLine': point['line'],
                'counterTotals': dict.fromkeys(count_keys),
                'observedEvents': {'notification': 0, 'frame': None, 'protocol': 0},
                'notificationFrameCountUnobserved': 0,
                'decoder': {key: Counter() for key in ('uwbRxDecoderIncomplete', 'uwbRxDecoderFailed')},
                'classifications': {key: Counter() for key in dimensions},
                'protocolQueueDelayMs': Counter(),
                'deadlineCause': None,
                '_seenNotification': set(), '_seenProtocol': set()}
            scopes[scope] = current
            operations.append(current)
        current['lastLine'] = point['line']
        if current['deadlineCause'] is None:
            current['deadlineCause'] = state['uwbDeadlineCause']
        for name, value in counts.items():
            if value is not None:
                old = current['counterTotals'][name]
                current['counterTotals'][name] = value if old is None else max(old, value)
        observed = point['rxObservation']
        observed['operationIndex'] = current['operationIndex']
        if point['event'] != UWB_GATT_EVENT:
            continue
        kind = state['uwbGattEvent']
        for event, name, seen_key, flag in (
                ('RX', 'notification', '_seenNotification', 'newNotification'),
                ('PROTOCOL', 'protocol', '_seenProtocol', 'newProtocol')):
            count = counts[name]
            if kind != event or count is None or count <= 0 or count in current[seen_key]:
                continue
            current[seen_key].add(count)
            current['observedEvents'][name] += 1
            observed[flag] = True
        if observed['newNotification']:
            frames = state['uwbRxNotificationFrameCount']
            observed['newFrameCount'] = frames
            if frames is not None:
                current['observedEvents']['frame'] = (current['observedEvents']['frame'] or 0) + frames
            else:
                current['notificationFrameCountUnobserved'] += 1
            for key in current['decoder']:
                value = state[key]
                current['decoder'][key]['unobserved' if value is None else str(value).lower()] += 1
        if observed['newProtocol']:
            for key in dimensions:
                value = state[key]
                current['classifications'][key][value if value is not None else 'unobserved'] += 1
            delay = point['gattTimingMs']['protocolQueueDelayMs']
            if delay is not None:
                current['protocolQueueDelayMs'][delay] += 1
    for operation in operations:
        del operation['_seenNotification'], operation['_seenProtocol']
        operation['decoder'] = {key: dict(value) for key, value in operation['decoder'].items()}
        operation['classifications'] = {key: dict(value) for key, value in operation['classifications'].items()}
        operation['protocolQueueDelayMs'] = distribution(operation['protocolQueueDelayMs'])
    return operations


def uwb_gatt_timing(state, elapsed):
    """원 elapsed를 보존하며 관측된 두 시각이 순서대로 있을 때만 차이를 낸다."""
    def difference(end, start):
        return end - start if end is not None and start is not None and end >= start else None
    started = state['uwbGattOperationStartedElapsedMs']
    observed = state['uwbGattObservedElapsedMs']
    return {
        'operationElapsedMs': difference(observed, started),
        'stageElapsedMs': difference(observed, state['uwbGattStageStartedElapsedMs']),
        'snapshotAgeMs': difference(elapsed, observed),
        'connectCallElapsedMs': difference(state['uwbGattConnectReturnedElapsedMs'],
            state['uwbGattConnectIssuedElapsedMs']),
        'protocolQueueDelayMs': difference(state['uwbRxProtocolObservedElapsedMs'],
            state['uwbRxProtocolReceivedElapsedMs']),
        **{name: difference(state[key], started) for name, key in (
            ('connectIssuedSinceStartMs', 'uwbGattConnectIssuedElapsedMs'),
            ('connectReturnedSinceStartMs', 'uwbGattConnectReturnedElapsedMs'),
            ('connectionCallbackSinceStartMs', 'uwbGattConnectionCallbackElapsedMs'),
            ('descriptorCallbackSinceStartMs', 'uwbGattDescriptorCallbackElapsedMs'),
            ('firstRxSinceStartMs', 'uwbGattFirstRxElapsedMs'),
            ('lastRxSinceStartMs', 'uwbGattLastRxElapsedMs'))},
    }


def connection_number(value, lower, upper):
    number = integer(value)
    return number if (number is not None and lower is not None and upper is not None
                      and lower <= number <= upper) else None


def connection_response(value, version):
    """인증되지 않은 header와 실제 protocol 판정을 분리하고 자유 문자열은 버린다."""
    if not isinstance(value, dict):
        return None
    safe = {key: allowed(value.get(key), choices) for key, choices in CONNECTION_RESPONSE_ENUMS.items()}
    if version >= 35:
        safe['disposition'] = allowed(value.get('disposition'),
            RESPONSE_DISPOSITIONS_V29 | {'WAKE_ACKNOWLEDGED', 'WAKE_NOMINAL_ACK', 'WAKE_UNCONFIRMED',
                                        'FRUNK_ACKNOWLEDGED', 'FRUNK_UNCONFIRMED'})
        safe['step'] = allowed(value.get('step'), CONNECTION_RESPONSE_ENUMS['step'] | {'WAKE_RESULT'})
    safe.update({key: value.get(key) if type(value.get(key)) is bool else None
                 for key in CONNECTION_RESPONSE_BOOLS})
    safe.update({key: connection_number(value.get(key), -(2**31), 2**31-1)
                 for key in CONNECTION_RESPONSE_INTS})
    if safe['fromPresent'] is not True or safe['fromKind'] != 'DOMAIN':
        safe['fromDomain'] = None
    if safe['requestUuidPresent'] is not True:
        safe['requestUuidMatches'] = None
    if safe['toPresent'] is not True:
        safe['toRouteMatches'] = None
    if safe['signedStatusPresent'] is not True:
        safe['signedMessageFault'] = safe['operationStatus'] = None
    if version >= 36:
        # domain2 UUID 없는 broadcast의 sleep enum만 남긴다. 범위 밖·다른 사유면 버린다.
        sleep = value.get('domainMismatchVcsecSleepStatus')
        safe['domainMismatchVcsecSleepStatus'] = (
            VCSEC_SLEEP_STATUSES[sleep] if type(sleep) is int and sleep in VCSEC_SLEEP_STATUSES and
            safe['domainMismatchReason'] == 'DOMAIN_DIFFERENT' else None)
    if version >= 37:
        # 인증·상관 경계 전체를 만족해도 현재 P·양수 시각의 freshness는 추론하지 않는다.
        drive_verified = (
            safe['expectedDomain'] == 3 and safe['fromPresent'] is True and
            safe['fromKind'] == 'DOMAIN' and safe['fromDomain'] == 3 and
            safe['toPresent'] is True and safe['toKind'] == 'ROUTING_ADDRESS' and
            safe['requestUuidPresent'] is True and safe['requestUuidMatches'] is True and
            safe['toRouteMatches'] is True and safe['inputKind'] == 'ENCRYPTED_RESPONSE' and
            safe['aead'] == 'VERIFIED' and safe['disposition'] == 'STATUS_VERIFIED' and
            safe['step'] == 'COMPLETE')
        safe.update({key: allowed(value.get(key), choices) if drive_verified else None
                     for key, choices in CONNECTION_DRIVE_ENUMS.items()})
    return safe


def connection_write(value, started, observed):
    """생성·큐·SDK 접수·callback을 별도로 보존하며 현재 작업 밖 시각은 받지 않는다."""
    if not isinstance(value, dict):
        return None
    safe = {
        'writeSequence': connection_number(value.get('writeSequence'), 1, 2**63-1),
        'writeBusyRetryCount': connection_number(value.get('writeBusyRetryCount'), 0, 2),
        'writeException': allowed(value.get('writeException'), WRITE_EXCEPTIONS),
    }
    for key in ('writeType', 'writeRequestStatus', 'writeCallbackStatus'):
        safe[key] = connection_number(value.get(key), -(2**31), 2**31-1)
    for key in ('writePendingBefore', 'writeDispatchQueued'):
        safe[key] = value.get(key) if type(value.get(key)) is bool else None
    requested = connection_number(value.get('writeRequestedElapsedMs'), started, observed)
    safe['writeRequestedElapsedMs'] = requested
    for key in ('writeReturnedElapsedMs', 'writeCallbackElapsedMs', 'writeDispatchElapsedMs'):
        lower = requested if requested is not None else started
        if key == 'writeCallbackElapsedMs' and safe['writeReturnedElapsedMs'] is not None:
            lower = safe['writeReturnedElapsedMs']
        safe[key] = connection_number(value.get(key), lower, observed)
    return safe


def connection_observation(value, elapsed, version, event):
    """schema34의 해당 GATT 행만 정제한다. 구버전/미관측을 0이나 성공으로 보충하지 않는다."""
    if version < 34 or event != CONNECTION_GATT_EVENT or not isinstance(value, dict):
        return None
    started = connection_number(value.get('operationStartedElapsedMs'), 0, elapsed)
    observed = connection_number(value.get('observedElapsedMs'), started, elapsed)
    safe = {
        'stage': allowed(value.get('stage'), UWB_GATT_STAGES),
        'event': allowed(value.get('event'), CONNECTION_GATT_EVENTS),
        'deadlineCause': allowed(value.get('deadlineCause'), UWB_DEADLINE_CAUSES),
        'connectAction': allowed(value.get('connectAction'), {'CREATE', 'RECONNECT'}),
        'operationStartedElapsedMs': started, 'observedElapsedMs': observed,
        'advertisementAgeAtConnectMs': connection_number(value.get('advertisementAgeAtConnectMs'), 0, elapsed),
        'rxNotificationFrameCount': connection_number(value.get('rxNotificationFrameCount'), 0, 16),
        'rxResponse': connection_response(value.get('rxResponse'), version),
        'write': connection_write(value.get('write'), started, observed),
    }
    safe.update({key: value.get(key) if type(value.get(key)) is bool else None
                 for key in CONNECTION_BOOLS})
    safe.update({key: connection_number(value.get(key), -(2**31), 2**31-1)
                 for key in CONNECTION_INTS})
    safe.update({key: connection_number(value.get(key), 0, 2**63-1)
                 for key in CONNECTION_COUNTERS.values()})
    safe.update({key: connection_number(value.get(key), started, observed) for key in CONNECTION_TIMES})
    for end, start in (('connectReturnedElapsedMs', 'connectIssuedElapsedMs'),
                       ('lastRxElapsedMs', 'firstRxElapsedMs'),
                       ('rxProtocolObservedElapsedMs', 'rxProtocolReceivedElapsedMs')):
        if safe[end] is not None and safe[start] is not None and safe[end] < safe[start]:
            safe[end] = None
    return safe


def connection_gatt_timing(state, elapsed):
    if state is None:
        return None
    def difference(end, start):
        return end - start if end is not None and start is not None and end >= start else None
    started = state['operationStartedElapsedMs']
    observed = state['observedElapsedMs']
    write = state['write'] or {}
    return {
        'operationElapsedMs': difference(observed, started),
        'stageElapsedMs': difference(observed, state['stageStartedElapsedMs']),
        'snapshotAgeMs': difference(elapsed, observed),
        'connectCallElapsedMs': difference(state['connectReturnedElapsedMs'], state['connectIssuedElapsedMs']),
        'protocolQueueDelayMs': difference(state['rxProtocolObservedElapsedMs'], state['rxProtocolReceivedElapsedMs']),
        **{name: difference(state[key], started) for name, key in (
            ('connectIssuedSinceStartMs', 'connectIssuedElapsedMs'),
            ('connectReturnedSinceStartMs', 'connectReturnedElapsedMs'),
            ('connectionCallbackSinceStartMs', 'connectionCallbackElapsedMs'),
            ('descriptorCallbackSinceStartMs', 'descriptorCallbackElapsedMs'),
            ('firstRxSinceStartMs', 'firstRxElapsedMs'),
            ('lastRxSinceStartMs', 'lastRxElapsedMs'))},
        **{name: difference(write.get(key), write.get('writeRequestedElapsedMs')) for name, key in (
            ('writeCallElapsedMs', 'writeReturnedElapsedMs'),
            ('writeCallbackSinceRequestMs', 'writeCallbackElapsedMs'),
            ('writeDispatchSinceRequestMs', 'writeDispatchElapsedMs'))},
    }


def summarize_connection(points):
    """작업별 실제 신규 counter만 소비한다. 누락·복제·역행을 새 protocol 판정으로 만들지 않는다."""
    scopes, operations = {}, []
    for point in points:
        state = point['connectionObservation']
        point['connectionRxObservation'] = None
        if state is None:
            continue
        started = state['operationStartedElapsedMs']
        scope = (point['processId'], point['trialId'], point['segment'], started)
        # 시작을 모르면 같은 counter/단계라도 행끼리 묶을 근거가 없다.
        current = scopes.get(scope) if started is not None else None
        if current is None:
            current = {
                'operationIndex': len(operations), 'processId': point['processId'],
                'trialId': point['trialId'], 'segment': point['segment'],
                'operationStartedElapsedMs': started, 'scopeComplete': started is not None,
                'firstLine': point['line'], 'lastLine': point['line'],
                'counterTotals': dict.fromkeys(CONNECTION_COUNTERS),
                'counterRegressionObserved': False,
                'observedEvents': {'notification': 0, 'frame': None, 'protocol': 0},
                'notificationFrameCountUnobserved': 0,
                'decoder': {key: Counter() for key in ('rxDecoderIncomplete', 'rxDecoderFailed')},
                'classifications': {key: Counter() for key in CONNECTION_CLASSIFICATIONS},
                'protocolQueueDelayMs': Counter(), 'stages': [], 'lastActiveStage': None,
                'deadlineCause': None, 'deadlineObservation': None,
                '_lastNotificationCounter': 0,
            }
            operations.append(current)
            if started is not None:
                scopes[scope] = current
        current['lastLine'] = point['line']
        current['lastObservation'] = state
        stage = state['stage']
        if stage is not None:
            stages = current['stages']
            if (not stages or stages[-1]['stage'] != stage or
                    stages[-1]['stageStartedElapsedMs'] != state['stageStartedElapsedMs']):
                stages.append({key: state[key] for key in ('stage', 'stageStartedElapsedMs', 'observedElapsedMs')})
                stages[-1]['line'] = point['line']
            if stage not in CONNECTION_TERMINAL_STAGES:
                current['lastActiveStage'] = stage
        if current['deadlineCause'] is None and state['deadlineCause'] is not None:
            current['deadlineCause'] = state['deadlineCause']
            current['deadlineObservation'] = {
                'line': point['line'], 'stage': stage,
                'observedElapsedMs': state['observedElapsedMs'],
                'operationElapsedMs': point['gattTimingMs']['operationElapsedMs'],
                'stageElapsedMs': point['gattTimingMs']['stageElapsedMs'],
            }
        counts = {name: state[key] for name, key in CONNECTION_COUNTERS.items()}
        # PROTOCOL 행이 유실된 뒤 과거 결과를 복제한 PROTOCOL도 새 판정으로 세지 않는다.
        previous_protocol_count = current['counterTotals']['protocol']
        for name, count in counts.items():
            if count is None:
                continue
            old = current['counterTotals'][name]
            current['counterRegressionObserved'] |= old is not None and count < old
            current['counterTotals'][name] = count if old is None else max(old, count)
        evidence = {'operationIndex': current['operationIndex'], 'newNotification': False,
            'newProtocol': False, 'newFrameCount': None}
        point['connectionRxObservation'] = evidence
        for event, name, previous, flag in (
                ('RX', 'notification', current['_lastNotificationCounter'], 'newNotification'),
                ('PROTOCOL', 'protocol', previous_protocol_count, 'newProtocol')):
            count = counts[name]
            if state['event'] == event and count is not None and count > (previous or 0):
                current['observedEvents'][name] += 1
                evidence[flag] = True
        if evidence['newNotification']:
            current['_lastNotificationCounter'] = counts['notification']
            frames = state['rxNotificationFrameCount']
            evidence['newFrameCount'] = frames
            if frames is None:
                current['notificationFrameCountUnobserved'] += 1
            else:
                current['observedEvents']['frame'] = (current['observedEvents']['frame'] or 0) + frames
            for key in current['decoder']:
                value = state[key]
                current['decoder'][key]['unobserved' if value is None else str(value).lower()] += 1
        if evidence['newProtocol']:
            response = state['rxResponse'] or {}
            for key in CONNECTION_CLASSIFICATIONS:
                value = response.get(key)
                label = 'unobserved' if value is None else str(value).lower() if type(value) is bool else str(value)
                current['classifications'][key][label] += 1
            delay = point['gattTimingMs']['protocolQueueDelayMs']
            if delay is not None:
                current['protocolQueueDelayMs'][delay] += 1
    for operation in operations:
        del operation['_lastNotificationCounter']
        operation['decoder'] = {key: dict(counts) for key, counts in operation['decoder'].items()}
        operation['classifications'] = {key: dict(counts) for key, counts in operation['classifications'].items()}
        operation['protocolQueueDelayMs'] = distribution(operation['protocolQueueDelayMs'])
    return operations


def integer(value):
    return value if type(value) is int and -(2**63) <= value < 2**63 else None


def identifier(value):
    if not isinstance(value, str) or len(value) != 36:
        return 'invalid'
    try:
        parsed = str(uuid.UUID(value))
        return parsed if parsed == value.lower() else 'invalid'
    except ValueError:
        return 'invalid'


def allowed(value, choices):
    return value if isinstance(value, str) and value in choices else None


def frunk_recognition_text(value):
    if not isinstance(value, str):
        return FRUNK_RECOGNITION_MASK, True, False
    if len(value) > FRUNK_RECOGNITION_MAX_TEXT:
        return FRUNK_RECOGNITION_MASK, True, True
    tokens = value.split()
    masked = any(token not in FRUNK_RECOGNITION_VOCABULARY for token in tokens)
    safe = ' '.join(token if token in FRUNK_RECOGNITION_VOCABULARY else FRUNK_RECOGNITION_MASK
                    for token in tokens)
    if len(safe) > FRUNK_RECOGNITION_MAX_TEXT:
        return FRUNK_RECOGNITION_MASK, True, True
    return safe, masked, False


def frunk_recognition(value):
    if not isinstance(value, dict):
        return None
    raw_candidates = value.get('candidates')
    raw_candidates = raw_candidates if isinstance(raw_candidates, list) else []
    candidates = []
    for candidate in raw_candidates[:FRUNK_RECOGNITION_MAX_CANDIDATES]:
        candidate = candidate if isinstance(candidate, dict) else {}
        text, masked, truncated = frunk_recognition_text(candidate.get('text'))
        score = candidate.get('confidence')
        confidence = score if type(score) in (int, float) and 0 <= score <= 1 else None
        candidates.append({'text': text, 'confidence': confidence,
            'masked': masked or candidate.get('masked') is True,
            'truncated': truncated or candidate.get('truncated') is True})
    policy = value.get('policy') if isinstance(value.get('policy'), dict) else {}
    return {'candidates': candidates,
        'candidatesTruncated': value.get('candidatesTruncated') is True or
                               len(raw_candidates) > FRUNK_RECOGNITION_MAX_CANDIDATES,
        'policy': {'accepted': policy.get('accepted') if type(policy.get('accepted')) is bool else None,
                   'reason': allowed(policy.get('reason'), FRUNK_RECOGNITION_REASONS)}}


def summary(value):
    result = {k: integer(value.get(k)) for k in SUMMARY_NUMBERS}
    result.update({k: value.get(k) if type(value.get(k)) is bool else None for k in SUMMARY_BOOLS})
    result.update(status=allowed(value.get('status'), STATUSES), reason=allowed(value.get('reason'), REASONS))
    return result


def distribution(values):
    count = sum(values.values())
    if not count:
        return {'count': 0, 'min': None, 'max': None, 'mean': None, 'p50': None, 'p95': None}
    ordered = sorted(values)
    def quantile(percent):
        target = (count * percent + 99) // 100
        running = 0
        for value in ordered:
            running += values[value]
            if running >= target:
                return value
    return {'count': count, 'min': ordered[0], 'max': ordered[-1], 'mean': sum(k*v for k, v in values.items()) / count, 'p50': quantile(50), 'p95': quantile(95)}


def bounded_lines(stream):
    """행 크기를 제한하며 초과행의 나머지도 제한된 chunk로 소비한다."""
    number = 0
    while True:
        line = stream.readline(MAX_LINE + 1)
        if not line:
            return
        number += 1
        oversized = len(line) > MAX_LINE
        if oversized:
            newline = b'\n' if isinstance(line, bytes) else '\n'
            while line and not line.endswith(newline):
                line = stream.readline(MAX_LINE + 1)
            yield number, None, 'LINE_TOO_LONG'
        else:
            if isinstance(line, bytes):
                try:
                    line = line.decode('utf-8', errors='strict')
                except UnicodeDecodeError:
                    yield number, None, 'INVALID_UTF8'
                    continue
            yield number, line, None


def bounded_log_lines(streams):
    """파일 경계를 넘겨 잘린/초과 행을 합치지 않고 전역 행 번호를 부여한다."""
    offset = 0
    for stream in streams:
        count = 0
        for count, text, error in bounded_lines(stream):
            yield offset + count, text, error
        offset += count


def approach_summary(value, wall, elapsed, clock_bad, version=9):
    """불명 필드는 복사하지 않으며 원 wall 시각과 수신 elapsed 투영을 분리한다."""
    safe = {
        'candidate': integer(value.get('candidate')),
        'attempt': integer(value.get('attempt')),
        'stage': allowed(value.get('stage'), APPROACH_STAGES),
        'query': allowed(value.get('query'), APPROACH_QUERIES),
        'outcome': allowed(value.get('outcome'), APPROACH_OUTCOMES),
        'reason': allowed(value.get('reason'), REASONS),
        'gear': allowed(value.get('gear'), {'P', 'R', 'N', 'D', 'UNKNOWN'}),
    }
    for key in ('candidate', 'attempt'):
        if safe[key] is not None and safe[key] < 1:
            safe[key] = None
    for key in APPROACH_BOOLS:
        safe[key] = value.get(key) if type(value.get(key)) is bool else None
    for key in ('lockState', 'frontTrunkState'):
        number = integer(value.get(key))
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
    invalid_time = False
    for key in (*APPROACH_RECEIVED_TIMES, 'driveSourceWallMs'):
        number = integer(value.get(key))
        valid = number is not None and number >= 0 and (key == 'driveSourceWallMs' or number <= elapsed)
        safe[key] = number if valid else None
        invalid_time |= value.get(key) is not None and not valid
    sleep_status = integer(value.get('bodyVehicleSleepStatus')) if version >= 20 else None
    safe['bodyVehicleSleepStatus'] = (sleep_status if safe['bodyVerified'] is True and
        safe['bodyReceivedElapsedMs'] is not None and sleep_status is not None and 0 <= sleep_status < 2**31 else None)
    safe['writeSequence'] = integer(value.get('writeSequence')) if version >= 10 else None
    if safe['writeSequence'] is not None and safe['writeSequence'] < 1:
        safe['writeSequence'] = None
    busy_retries = integer(value.get('writeBusyRetryCount')) if version >= 20 else None
    safe['writeBusyRetryCount'] = busy_retries if busy_retries is not None and 0 <= busy_retries <= 2 else None
    for key in WRITE_INTS:
        number = integer(value.get(key)) if version >= 10 else None
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
        if key in ('frameLength', 'chunkLength', 'frameOffset') and safe[key] is not None and safe[key] < 0:
            safe[key] = None
        if key == 'negotiatedMtu' and safe[key] is not None and not 23 <= safe[key] <= 517:
            safe[key] = None
    for key in WRITE_BOOLS:
        safe[key] = value.get(key) if version >= 10 and type(value.get(key)) is bool else None
    safe['writeException'] = allowed(value.get('writeException'), WRITE_EXCEPTIONS) if version >= 10 else None
    for key in WRITE_TIMES:
        number = integer(value.get(key)) if version >= 10 else None
        valid = number is not None and 0 <= number <= elapsed
        safe[key] = number if valid else None
        invalid_time |= version >= 10 and value.get(key) is not None and not valid
    for key in ('rxNotificationCount', 'rxFrameCount', 'rxProtocolCount'):
        number = integer(value.get(key)) if version >= 11 else None
        safe[key] = number if number is not None and 0 <= number < 2**63 else None
    safe['responseDisposition'] = allowed(value.get('responseDisposition'),
        RESPONSE_DISPOSITIONS_V29 if version >= 29 else RESPONSE_DISPOSITIONS) if version >= 11 else None
    safe['responseQuery'] = allowed(value.get('responseQuery'), {'BODY_STATUS', 'DRIVE_STATE'}) if version >= 11 else None
    for key in ('responseExpectedDomain', 'responseFromDomain'):
        number = integer(value.get(key)) if version >= 13 else None
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
    for key in ('responseFromPresent', 'responseToPresent'):
        safe[key] = value.get(key) if version >= 13 and type(value.get(key)) is bool else None
    for key in ('responseFromKind', 'responseToKind'):
        safe[key] = allowed(value.get(key), DESTINATION_KINDS) if version >= 13 else None
    safe['responsePayloadKind'] = allowed(value.get('responsePayloadKind'), PAYLOAD_KINDS) if version >= 13 else None
    safe['responseDomainMismatchReason'] = allowed(value.get('responseDomainMismatchReason'), DOMAIN_MISMATCH_REASONS) if version >= 13 else None
    if safe['responseFromPresent'] is not True or safe['responseFromKind'] != 'DOMAIN':
        safe['responseFromDomain'] = None
    for key in ('responseRequestUuidPresent', 'responseRequestUuidMatches', 'responseToRouteMatches'):
        safe[key] = value.get(key) if version >= 15 and type(value.get(key)) is bool else None
    if safe['responseRequestUuidPresent'] is not True:
        safe['responseRequestUuidMatches'] = None
    if safe['responseToPresent'] is not True:
        safe['responseToRouteMatches'] = None
    safe['responseSignedStatusPresent'] = value.get('responseSignedStatusPresent') if version >= 16 and type(value.get('responseSignedStatusPresent')) is bool else None
    for key in ('responseSignedMessageFault', 'responseOperationStatus'):
        number = integer(value.get(key)) if version >= 16 and safe['responseSignedStatusPresent'] is True else None
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
    number = integer(value.get('libraryWriteFailureCode')) if version == 16 else None
    safe['libraryWriteFailureCode'] = number if number is not None and -(2**31) <= number < 2**31 else None
    safe['advertisementConnectable'] = value.get('advertisementConnectable') if version >= 13 and type(value.get('advertisementConnectable')) is bool else None
    for key in ('gattAutoConnect', 'gattConnectAccepted'):
        safe[key] = value.get(key) if version >= 19 and type(value.get(key)) is bool else None
    safe['gattConnectAction'] = allowed(value.get('gattConnectAction'), {'CREATE', 'RECONNECT'}) if version >= 19 else None
    for key in ('gattConnectIssuedElapsedMs', 'gattConnectReturnedElapsedMs'):
        number = integer(value.get(key)) if version >= 13 else None
        valid = number is not None and 0 <= number <= elapsed
        safe[key] = number if valid else None
        invalid_time |= version >= 13 and value.get(key) is not None and not valid
    age = integer(value.get('advertisementAgeAtConnectMs')) if version >= 13 else None
    safe['advertisementAgeAtConnectMs'] = age if age is not None and 0 <= age < 2**63 else None
    safe['gattTransport'] = allowed(value.get('gattTransport'), {'NATIVE'} if version >= 17 else {'NORDIC'}) if version >= 14 else None
    for key in ('connectSettleStartedElapsedMs', 'connectSettleCompletedElapsedMs', 'libraryConnectQueuedElapsedMs',
                'libraryConnectionObservedElapsedMs', 'libraryDescriptorCompletedElapsedMs',
                'libraryWriteCompletedElapsedMs', 'libraryLastRxDeliveredElapsedMs'):
        supported = version >= 14 if key.startswith('connectSettle') else 14 <= version <= 16
        number = integer(value.get(key)) if supported else None
        valid = number is not None and 0 <= number <= elapsed
        safe[key] = number if valid else None
        invalid_time |= supported and value.get(key) is not None and not valid
    age = integer(value.get('advertisementAgeAtConnectQueueMs')) if 14 <= version <= 16 else None
    safe['advertisementAgeAtConnectQueueMs'] = age if age is not None and 0 <= age < 2**63 else None
    for key in ('lastRxElapsedMs', 'lastFrameElapsedMs'):
        number = integer(value.get(key)) if version >= 11 else None
        valid = number is not None and 0 <= number <= elapsed
        safe[key] = number if valid else None
        invalid_time |= version >= 11 and value.get(key) is not None and not valid
    for key in ('gattConnectionStatus', 'gattConnectionState', 'gattDescriptorRequestStatus', 'gattDescriptorCallbackStatus'):
        number = integer(value.get(key)) if version >= 12 else None
        safe[key] = number if number is not None and -(2**31) <= number < 2**31 else None
    safe['writeDispatchQueued'] = value.get('writeDispatchQueued') if version >= 12 and type(value.get('writeDispatchQueued')) is bool else None
    for key in ('gattConnectionElapsedMs', 'gattDescriptorCallbackElapsedMs', 'writeDispatchElapsedMs'):
        number = integer(value.get(key)) if version >= 12 else None
        valid = number is not None and 0 <= number <= elapsed
        safe[key] = number if valid else None
        invalid_time |= version >= 12 and value.get(key) is not None and not valid
    times = {'recordWallMs': wall, 'recordElapsedMs': elapsed, 'clockAlignmentWarning': clock_bad}
    for key in APPROACH_RECEIVED_TIMES:
        projected_key = key.replace('ElapsedMs', 'ProjectedWallMs')
        times[projected_key] = wall + safe[key] - elapsed if safe[key] is not None and not clock_bad else None
    return safe, times, invalid_time


def candidate_diagnostics(value, elapsed):
    """원 수신·앱 전달·스냅샷 시각과 미래의 만료 예정 시각을 구분한다."""
    safe = {}
    candidate = integer(value.get('candidate'))
    safe['candidate'] = candidate if candidate is not None and candidate > 0 else None
    for key in ('candidatePresent', 'readInFlight', 'waitingForDeparture', 'scanRunning'):
        safe[key] = value.get(key) if type(value.get(key)) is bool else None
    for key in ('acceptedAdvertisementCount', 'candidateAdvertisementCount', 'suppressedReadCount', 'lastExpiryDelayMs'):
        number = integer(value.get(key))
        safe[key] = number if number is not None and number >= 0 else None
    observed = integer(value.get('observedElapsedMs'))
    safe['observedElapsedMs'] = observed if observed is not None and 0 <= observed <= elapsed else None
    for key in ('lastAcceptedReceivedElapsedMs', 'lastAcceptedDeliveredElapsedMs', 'lastExpiredElapsedMs'):
        number = integer(value.get(key))
        safe[key] = number if number is not None and safe['observedElapsedMs'] is not None and 0 <= number <= observed else None
    expiry = integer(value.get('expiresElapsedMs'))
    safe['expiresElapsedMs'] = expiry if expiry is not None and expiry >= 0 and safe['candidatePresent'] is True else None
    return safe


def record_approach(t, event, raw, process, segment, wall, elapsed, line, clock_bad, issue, version=9):
    if event not in APPROACH_EVENTS:
        return
    if event in WRITE_EVENTS and version < 10:
        issue(line, 'UNSUPPORTED_WRITE_EVIDENCE')
        return
    if event in RX_EVENTS and version < 11:
        issue(line, 'UNSUPPORTED_RX_EVIDENCE')
        return
    if event in GATT_EVENTS and version < 12:
        issue(line, 'UNSUPPORTED_GATT_EVIDENCE')
        return
    if event in CONNECT_EVENTS and version < 13:
        issue(line, 'UNSUPPORTED_CONNECT_EVIDENCE')
        return
    if not isinstance(raw, dict):
        issue(line, 'APPROACH_SUMMARY_MISSING')
        if event == 'TESLA_APPROACH_READ_BLOCKED':
            t['approachReadBlocks'].append({'line': line, 'processId': process, 'segment': segment,
                'summary': None, 'time': {'recordWallMs': wall, 'recordElapsedMs': elapsed}})
        return
    safe, times, invalid_time = approach_summary(raw, wall, elapsed, clock_bad, version)
    if invalid_time:
        issue(line, 'INVALID_APPROACH_TIME')
    for key in ('candidate', 'attempt'):
        if raw.get(key) is not None and safe[key] is None:
            issue(line, 'INVALID_APPROACH_' + key.upper())
    point = {'line': line, 'event': event, 'processId': process, 'segment': segment,
             'summary': safe, 'time': times}
    if event == 'TESLA_APPROACH_READ_BLOCKED':
        t['approachReadBlocks'].append(point)
    attempt = safe['attempt']
    if attempt is None:
        if raw.get('attempt') is None and event != 'TESLA_APPROACH_READ_BLOCKED' and safe['stage'] not in ('IDLE', 'BLOCKED'):
            issue(line, 'INVALID_APPROACH_ATTEMPT')
        return
    scope = (process, segment, attempt)
    a = t['approachReadAttempts'].setdefault(scope, {
        'processId': process, 'segment': segment, 'attempt': attempt, 'candidateIds': set(), 'versions': set(),
        'startCount': 0, 'firstStartTime': None, 'finalTime': None, 'stages': [],
        'observedQueries': set(), 'bodyVerifiedObserved': False, 'driveVerifiedObserved': False,
        'verificationRegressed': False, 'cleanupFailedObserved': False, 'invalidTimeObserved': False,
        'blockedObserved': False, 'deadlineExpiredObserved': False,
        'final': None, 'finalEvent': None, 'lastSummary': None, 'duplicateFinals': 0, 'conflictingFinals': 0,
    })
    t['approachAttemptSegments'].setdefault((process, attempt), set()).add(segment)
    a['stages'].append(point)
    a['versions'].add(version)
    a['lastSummary'] = safe
    a['invalidTimeObserved'] |= invalid_time
    if safe['candidate'] is not None:
        a['candidateIds'].add(safe['candidate'])
    if safe['query'] is not None:
        a['observedQueries'].add(safe['query'])
    if event == 'TESLA_APPROACH_READ_STARTED':
        a['startCount'] += 1
        if a['firstStartTime'] is None:
            a['firstStartTime'] = times
        else:
            issue(line, 'DUPLICATE_APPROACH_START')
    for key in ('bodyVerified', 'driveVerified'):
        observed = key + 'Observed'
        if a[observed] and safe[key] is False:
            a['verificationRegressed'] = True
            issue(line, 'APPROACH_VERIFICATION_REGRESSED')
        a[observed] |= safe[key] is True
    a['cleanupFailedObserved'] |= event == 'TESLA_APPROACH_READ_CLEANUP_FAILED' or safe['stage'] == 'CLEANUP_FAILED'
    a['blockedObserved'] |= event == 'TESLA_APPROACH_READ_BLOCKED'
    a['deadlineExpiredObserved'] |= safe['stage'] == 'TIMED_OUT' or safe['reason'] in DEADLINE_REASONS
    if event in ('TESLA_APPROACH_READ_FINISHED', 'TESLA_APPROACH_READ_BLOCKED', 'TESLA_APPROACH_READ_CLEANUP_FAILED'):
        if a['final'] is None:
            a['final'], a['finalTime'], a['finalEvent'] = safe, times, event
        elif event != 'TESLA_APPROACH_READ_CLEANUP_FAILED':
            a['duplicateFinals'] += 1
            issue(line, 'DUPLICATE_APPROACH_FINAL')
            if a['final'] != safe:
                a['conflictingFinals'] += 1
                issue(line, 'CONFLICTING_APPROACH_FINAL')


def is_approach_recovery(attempts, retries, blocks):
    """v18–v25의 두 번째 시작만 허용한다. 복구 관측은 광고 수신·검증 성공 자체가 아니다."""
    if len(attempts) != 2:
        return False
    first, second = attempts
    for a in attempts:
        if (len(a['versions']) != 1 or a['versions'][0] not in APPROACH_RECOVERY_VERSIONS or a['startCount'] != 1 or a['timeBoundaryObserved'] or
            a['invalidTimeObserved'] or a['duplicateFinals'] or
            a['stages'][0]['event'] != 'TESLA_APPROACH_READ_STARTED'):
            return False
        if any(p['time']['clockAlignmentWarning'] or p['summary']['candidate'] != a['candidate']
               for p in a['stages']):
            return False
    final = first['final']
    if (first['cleanupStatus'] != 'clean' or first['blockedObserved'] or first['verificationRegressed'] or
        first['stages'][-1]['event'] != 'TESLA_APPROACH_READ_FINISHED' or
        not (final['stage'] == 'FAILED' and final['reason'] in AUTHENTICATED_RETRY_FAILURES or
             final['stage'] == 'TIMED_OUT' and final['reason'] == 'DEADLINE_EXPIRED')):
        return False
    for p in first['stages']:
        safe = p['summary']
        if (safe['stage'] in ('COMPLETE', 'BLOCKED', 'CANCELED', 'CLEANUP_FAILED') or
            safe['writeException'] == 'SECURITY_EXCEPTION'):
            return False
        if safe['stage'] in ('CLEANING_UP', 'RESUMING_SCAN', 'FAILED', 'TIMED_OUT'):
            if safe['reason'] != final['reason']:
                return False
        if safe['stage'] in ('FAILED', 'TIMED_OUT') and safe['stage'] != final['stage']:
            return False
    cleaning = next((p for p in first['stages'] if p['summary']['stage'] == 'CLEANING_UP'), None)
    if cleaning is None:
        return False
    closed = next((p for p in first['stages'] if p['line'] > cleaning['line'] and
        p['summary']['stage'] == final['stage'] and p['summary']['localClosed'] is True and
        p['summary']['leaseRetained'] is False), None)
    if closed is None:
        return False
    start = first['firstStartTime']['recordElapsedMs']
    second_start = second['stages'][0]
    second_elapsed = second_start['time']['recordElapsedMs']
    if (first['stages'][-1]['line'] >= second_start['line'] or
        any(blocked['line'] < second_start['line'] for blocked in blocks)):
        return False
    if any(not start <= p['time']['recordElapsedMs'] <= start + CANDIDATE_READ_BUDGET_MS
           for a in attempts for p in a['stages']):
        return False
    # 서비스는 예약을 READ_FINISHED 바로 전에 기록한다. 최종행 뒤라는 조건은 금지한다.
    # 실제 close 확인과 예약 중 늦은 시각부터 대기하며, 최종행 기록 지연은 더하지 않는다.
    return any(not retry['clockAlignmentWarning'] and retry['version'] == first['versions'][0] and
        cleaning['line'] < retry['line'] < second_start['line'] and
        cleaning['time']['recordElapsedMs'] <= retry['elapsedMs'] <= second_elapsed and
        second_elapsed - max(closed['time']['recordElapsedMs'], retry['elapsedMs']) >= AUTHENTICATED_RETRY_DELAY_MS
        for retry in retries)


def finish_approaches(t, issue):
    supported = any(v >= 9 for v in t['versions'])
    t['approachReadSupported'] = supported
    reads = list(t['approachReadAttempts'].values())
    scopes = t.pop('approachAttemptSegments')
    retries = t.pop('approachRetryEvidence')
    groups = {}
    for a in reads:
        candidates = a.pop('candidateIds')
        a['versions'] = sorted(a['versions'])
        a['candidate'] = next(iter(candidates)) if len(candidates) == 1 else None
        a['candidateConflict'] = len(candidates) > 1
        a['timeBoundaryObserved'] = len(scopes[(a['processId'], a['attempt'])]) > 1
        if a['candidateConflict']:
            issue(a['stages'][-1]['line'], 'APPROACH_CANDIDATE_CONFLICT')
        if a['timeBoundaryObserved']:
            issue(a['stages'][-1]['line'], 'APPROACH_TIME_BOUNDARY')
        a['observedQueries'] = sorted(a['observedQueries'])
        final = a['final']
        a['ended'] = final is not None
        a['partialSuccess'] = ((final['bodyVerified'] is True) != (final['driveVerified'] is True)) if final is not None else None
        a['observedPartialSuccess'] = a['bodyVerifiedObserved'] != a['driveVerifiedObserved']
        a['deadlineExpired'] = a['deadlineExpiredObserved'] if final is not None or a['deadlineExpiredObserved'] else None
        cleanup_failed = a['cleanupFailedObserved'] or (final is not None and
            (final['stage'] == 'CLEANUP_FAILED' or final['leaseRetained'] is True))
        clean = final is not None and final['localClosed'] is True and final['leaseRetained'] is False and not cleanup_failed
        a['cleanupStatus'] = 'failed' if cleanup_failed else 'clean' if clean else 'unconfirmed'
        a['countedComplete'] = (clean and final['stage'] == 'COMPLETE' and
            final['outcome'] == 'VERIFIED_STATUS' and final['bodyVerified'] is True and
            final['driveVerified'] is True and a['candidate'] is not None and
            a['conflictingFinals'] == 0 and not a['verificationRegressed'] and
            not a['timeBoundaryObserved'] and not a['invalidTimeObserved'] and
            not a['deadlineExpiredObserved'] and not a['blockedObserved'])
        a['result'] = ('cleanup_failed' if cleanup_failed else 'blocked' if a['blockedObserved'] and final is None else 'unfinished' if final is None else
            'timed_out' if a['deadlineExpired'] else 'canceled' if final['stage'] == 'CANCELED' else
            'blocked' if final['stage'] == 'BLOCKED' else 'complete' if a['countedComplete'] else
            'partial' if a['partialSuccess'] else 'failed' if final['stage'] == 'FAILED' else 'unconfirmed')
        start, end = a['firstStartTime'], a['finalTime']
        a['elapsedReadMs'] = (end['recordElapsedMs'] - start['recordElapsedMs'] if start is not None and
            end is not None and not a['timeBoundaryObserved'] and not a['invalidTimeObserved'] and
            end['recordElapsedMs'] >= start['recordElapsedMs'] else None)
        if a['candidate'] is not None:
            key = (a['processId'], a['segment'], a['candidate'])
            group = groups.setdefault(key, {'processId': key[0], 'segment': key[1], 'candidate': key[2], 'reads': [], 'blocks': [], 'blockedCount': 0})
            group['reads'].append(a)
    for blocked in t['approachReadBlocks']:
        safe = blocked['summary']
        if safe is not None and safe['candidate'] is not None:
            key = (blocked['processId'], blocked['segment'], safe['candidate'])
            group = groups.setdefault(key, {'processId': key[0], 'segment': key[1], 'candidate': key[2], 'reads': [], 'blocks': [], 'blockedCount': 0})
            group['blockedCount'] += 1
            group['blocks'].append(blocked)
    approaches = []
    for group in groups.values():
        attempts = group.pop('reads')
        blocks = group.pop('blocks')
        starts = sum(a['startCount'] for a in attempts)
        repeated = len(attempts) > 1 or starts > 1
        key = (group['processId'], group['segment'], group['candidate'])
        recovery = repeated and is_approach_recovery(attempts, retries.get(key, ()), blocks)
        violation = repeated and not recovery
        if violation:
            issue(attempts[-1]['stages'][-1]['line'], 'REPEATED_APPROACH_READ')
            for a in attempts:
                a['countedComplete'] = False
                if a['result'] == 'complete':
                    a['result'] = 'policy_violation'
        group.update(readAttemptCount=len(attempts), startedReadCount=starts,
            attempts=[a['attempt'] for a in attempts], repeatedReadObserved=repeated,
            recoveryReadObserved=recovery, repeatedReadPolicyViolation=violation,
            observedQueries=sorted({query for a in attempts for query in a['observedQueries']}),
            completeReadCount=sum(a['countedComplete'] for a in attempts),
            partialReadCount=sum(a['partialSuccess'] is True for a in attempts))
        approaches.append(group)
    t['approachReadAttempts'] = reads
    t['approaches'] = approaches
    t['approachStartedReadCount'] = sum(a['startCount'] for a in reads) if supported else None
    t['approachFinalReadCount'] = sum(a['ended'] for a in reads) if supported else None
    t['approachCompleteReadCount'] = sum(a['countedComplete'] for a in reads) if supported else None
    t['approachPartialReadCount'] = sum(a['partialSuccess'] is True for a in reads) if supported else None
    t['approachRecoveryReadCount'] = sum(group['recoveryReadObserved'] for group in approaches) if supported else None
    t['approachObservedPartialReadCount'] = sum(a['observedPartialSuccess'] for a in reads) if supported else None
    t['approachCleanupFailedCount'] = sum(a['cleanupStatus'] == 'failed' for a in reads) if supported else None
    t['approachDeadlineExpiredCount'] = sum(a['deadlineExpired'] is True for a in reads) if supported else None
    t['approachResults'] = dict(Counter(a['result'] for a in reads)) if supported else None


def analyze(stream, exclude=(), *, additional_streams=()):
    excluded = set(exclude)
    found = set()
    issues = []
    versions = Counter()
    processes = Counter()
    trials = {}
    frunk_events = []
    uwb_events = []
    navigation_events = []
    product_events = []
    totals = Counter()
    previous = None
    segment = 0
    clock_bad = False
    def issue(line, code):
        issues.append({'line': line, 'code': code})
    for line_no, text, error in bounded_log_lines((stream, *additional_streams)):
        totals['lines'] += 1
        if error:
            issue(line_no, error)
            totals['malformedRows'] += 1
            continue
        try:
            row = json.loads(text, parse_constant=lambda _: None)
        except (ValueError, RecursionError):
            issue(line_no, 'PARTIAL_LINE' if not text.endswith('\n') else 'MALFORMED_JSON')
            totals['malformedRows'] += 1
            continue
        if not isinstance(row, dict):
            issue(line_no, 'INVALID_RECORD')
            totals['malformedRows'] += 1
            continue
        version = integer(row.get('version'))
        if version not in SUPPORTED_VERSIONS:
            issue(line_no, 'UNSUPPORTED_VERSION')
            totals['unsupportedRows'] += 1
            continue
        process = identifier(row.get('processId'))
        trial = identifier(row.get('trialId')) if row.get('trialId') is not None else None
        if process == 'invalid' or trial == 'invalid':
            issue(line_no, 'INVALID_IDENTIFIER')
        wall, elapsed = integer(row.get('wallMs')), integer(row.get('elapsedMs'))
        if wall is None or elapsed is None or elapsed < 0:
            issue(line_no, 'INVALID_TIME')
            totals['malformedRows'] += 1
            continue
        boundary = previous is None or previous[0] != process or process == 'invalid'
        if previous is not None and not boundary:
            if elapsed < previous[2]:
                issue(line_no, 'ELAPSED_REGRESSION')
                boundary = True
                clock_bad = True
            elif abs((wall - previous[1]) - (elapsed - previous[2])) > 1000:
                issue(line_no, 'CLOCK_MISMATCH')
                boundary = True
                clock_bad = True
        if boundary:
            segment += 1
        previous = (process, wall, elapsed)
        # 시험 제외는 집계만 제외한다. 같은 프로세스의 시계 변경 근거는 보존한다.
        if trial in excluded:
            found.add(trial)
            totals['excludedRows'] += 1
            continue
        versions[str(version)] += 1
        totals['analyzedRows'] += 1
        processes[process] += 1
        raw_state = row.get('state') if isinstance(row.get('state'), dict) else {}
        product_event = allowed(row.get('event'), PRODUCT_RELATED_EVENTS)
        if (version >= 33 and process != 'invalid' and trial != 'invalid' and
                product_event is not None and (version >= 35 or product_event not in PRODUCT_WAKE_EVENTS) and
                (version >= 36 or product_event not in PRODUCT_V36_EVENTS) and
                (version >= 38 or product_event not in PRODUCT_V38_EVENTS) and
                (version >= 39 or product_event != PRODUCT_VOICE_DIAGNOSTIC_EVENT) and
                (product_event in PRODUCT_EVENTS | PRODUCT_WAKE_EVENTS or
                 any(raw_state.get(key) is not None for key in PRODUCT_KEYS))):
            observation = connection_observation(row.get('connectionObservation'), elapsed, version, product_event)
            product_events.append({
                'line': line_no, 'processId': process, 'segment': segment,
                'trialId': trial,
                'event': product_event, 'wallMs': wall, 'elapsedMs': elapsed,
                'clockAlignmentWarning': clock_bad, 'state': product_state(raw_state, version),
                'frunkState': {key: raw_state.get(key) if type(raw_state.get(key)) is bool else None
                               for key in FRUNK_STATE_KEYS},
                'connectionObservation': observation,
                'productVoiceDiagnostic': product_voice_diagnostic(
                    row.get('productVoiceDiagnostic'), version, product_event),
                'gattTimingMs': connection_gatt_timing(observation, elapsed),
            })
        if (version >= 21 and process != 'invalid' and allowed(row.get('event'), FRUNK_EVENTS) is not None and
            (row['event'] != FRUNK_RECOGNITION_EVENT or version >= 22)):
            raw_state = row.get('state') if isinstance(row.get('state'), dict) else {}
            frunk_events.append({'line': line_no, 'processId': process, 'segment': segment,
                'trialId': trial if trial != 'invalid' else None,
                'event': row['event'], 'wallMs': wall, 'elapsedMs': elapsed,
                'clockAlignmentWarning': clock_bad,
                'frunkVoiceRecognition': frunk_recognition(row.get('frunkVoiceRecognition'))
                    if version >= 22 and row['event'] == FRUNK_RECOGNITION_EVENT else None,
                'state': {key: raw_state.get(key) if type(raw_state.get(key)) is bool else None
                          for key in FRUNK_STATE_KEYS}})
        if (version >= 23 and process != 'invalid' and allowed(row.get('event'), UWB_EVENTS) is not None
                and (row['event'] != UWB_GATT_EVENT or version >= 25)
                and (row['event'] != UWB_INITIAL_DEVICE_INFO_EVENT or version >= 28)
                and (row['event'] != 'TESLA_UWB_BACKEND_STARTING' or version >= 31)):
            raw_state = row.get('state') if isinstance(row.get('state'), dict) else {}
            safe_state = uwb_state(raw_state, elapsed, version)
            uwb_events.append({'line': line_no, 'processId': process, 'segment': segment,
                'trialId': trial if trial != 'invalid' else None,
                'recordWallMs': wall, 'recordElapsedMs': elapsed, 'event': row['event'],
                'state': safe_state,
                'gattTimingMs': uwb_gatt_timing(safe_state, elapsed),
                'newDistanceReceived': row['event'] == 'TESLA_UWB_DISTANCE_RECEIVED' and
                    safe_state['uwbLastDistanceMeters'] is not None and
                    safe_state['uwbLastReceivedElapsedMs'] is not None})
        if version >= 28 and process != 'invalid' and row.get('event') == NAVIGATION_EVENT:
            observation = navigation_observation(row.get('navigationObservation'), version)
            if observation is not None:
                navigation_events.append({'line': line_no, 'processId': process, 'segment': segment,
                    'trialId': trial if trial != 'invalid' else None,
                    'recordWallMs': wall, 'recordElapsedMs': elapsed,
                    'clockAlignmentWarning': clock_bad, 'event': NAVIGATION_EVENT,
                    'observation': observation})
        if trial is None or trial == 'invalid' or process == 'invalid':
            totals['unassignedRows'] += 1
            continue
        t = trials.setdefault(trial, {'trialId': trial, 'processIds': set(), 'versions': set(), 'rows': 0, 'firstStartWallMs': None, 'lastStopWallMs': None, 'unclosedMarkers': 0, 'markerErrors': 0, 'options': [], 'attempts': {}, 'candidateIds': set(), 'candidateStateMax': None, 'candidates': {}, 'evidence': Counter(), 'evidenceDimensions': {}, 'queue': Counter(), 'speechSummaryRows': 0})
        t['rows'] += 1
        t['processIds'].add(process)
        t['versions'].add(version)
        t.setdefault('scanTimerDelays', Counter())
        t.setdefault('scanMatches', [])
        t.setdefault('approachReadAttempts', {})
        t.setdefault('approachAttemptSegments', {})
        t.setdefault('approachRetryEvidence', {})
        t.setdefault('approachReadBlocks', [])
        t.setdefault('candidateDiagnostics', [])
        event = allowed(row.get('event'), EVENTS)
        state = row.get('state') if isinstance(row.get('state'), dict) else {}
        if event == 'BLE_CANDIDATE_DIAGNOSTICS':
            raw_diagnostics = row.get('candidateDiagnostics')
            if version < 15:
                issue(line_no, 'UNSUPPORTED_CANDIDATE_DIAGNOSTICS')
            elif not isinstance(raw_diagnostics, dict):
                issue(line_no, 'CANDIDATE_DIAGNOSTICS_MISSING')
            else:
                t['candidateDiagnostics'].append({'line': line_no, 'processId': process, 'segment': segment,
                    'wallMs': wall, 'elapsedMs': elapsed, 'clockAlignmentWarning': clock_bad,
                    'summary': candidate_diagnostics(raw_diagnostics, elapsed)})
        t.setdefault('lastStartLine', None)
        t.setdefault('lastStopLine', None)
        t.setdefault('stopReason', None)
        if event == 'BLE_FIELD_START_REQUESTED':
            t['lastStartLine'] = line_no
        if event == 'BLE_FIELD_START_REQUESTED' and t['firstStartWallMs'] is None:
            t['firstStartWallMs'] = wall
        if event == 'BLE_FIELD_STOPPED':
            t['lastStopWallMs'] = wall
            t['stopReason'] = allowed(state.get('bleFieldTrialStopReason'), REASONS)
            t['lastStopLine'] = line_no
        if event == 'BLE_FIELD_PREVIOUS_RUN_UNCLOSED':
            t['unclosedMarkers'] += 1
        if event in ('BLE_FIELD_MARKER_READ_FAILED', 'BLE_FIELD_MARKER_WRITE_FAILED'):
            t['markerErrors'] += 1
        # v1–v6의 미지원은 False가 아니다. 옵션은 행마다 읽고 이전 실행 값을 채우지 않는다.
        opts = {key: state.get(key) if type(state.get(key)) is bool and version >= OPTION_VERSIONS.get(key, 1) else None for key in OPTIONS}
        if any(v is not None for v in opts.values()) and (not t['options'] or t['options'][-1]['values'] != opts):
            t['options'].append({'line': line_no, 'values': opts})
        if version >= 6:
            maximum = integer(state.get('bleFieldCandidateCount'))
            if maximum is not None and maximum >= 0:
                t['candidateStateMax'] = max(t['candidateStateMax'] or 0, maximum)
        if version >= 2 and isinstance(row.get('speechTrial'), dict):
            t['speechSummaryRows'] += 1
        if version >= 9:
            record_approach(t, event, row.get('teslaApproachRead'), process, segment,
                wall, elapsed, line_no, clock_bad, issue, version)
        raw = row.get('bleTrial')
        recovery_row = version >= 8 and isinstance(row.get('bleEvidence'), dict) and allowed(row['bleEvidence'].get('kind'), SCAN_RECOVERY_KINDS) is not None
        if version >= 5 and isinstance(raw, dict) and not recovery_row:
            attempt = integer(raw.get('attempt'))
            no_attempt_snapshot = attempt == 0 and raw.get('status') in ('IDLE', 'BLOCKED') and event != 'BLE_FIELD_TRIAL_FINISHED'
            if no_attempt_snapshot:
                pass
            elif attempt is None or attempt < 1:
                issue(line_no, 'INVALID_ATTEMPT')
            else:
                a = t['attempts'].setdefault(attempt, {'attempt': attempt, 'final': None, 'lastSummary': None, 'duplicateFinals': 0, 'conflictingFinals': 0, 'processIds': set()})
                if a['processIds'] and process not in a['processIds']:
                    issue(line_no, 'ATTEMPT_PROCESS_COLLISION')
                a['processIds'].add(process)
                segments = a.setdefault('segments', set())
                if segments and segment not in segments:
                    issue(line_no, 'ATTEMPT_TIME_BOUNDARY')
                segments.add(segment)
                safe = summary(raw)
                a['lastSummary'] = safe
                if event == 'BLE_FIELD_TRIAL_FINISHED':
                    if a['final'] is None:
                        a['final'] = safe
                    else:
                        a['duplicateFinals'] += 1
                        issue(line_no, 'DUPLICATE_FINAL')
                        if a['final'] != safe:
                            a['conflictingFinals'] += 1
                            issue(line_no, 'CONFLICTING_FINAL')
        evidence = row.get('bleEvidence')
        if version >= 6 and isinstance(evidence, dict):
            kind = allowed(evidence.get('kind'), KINDS)
            if kind is None or (version < 8 and kind in SCAN_V8_KINDS):
                issue(line_no, 'INVALID_EVIDENCE_KIND')
                continue
            t['evidence'][kind] += 1
            if version >= 8 and kind == 'SCAN_TIMER_DELAYED':
                timer_delay = integer(evidence.get('timerDelayMs'))
                if timer_delay is not None and timer_delay >= 1000:
                    t['scanTimerDelays'][timer_delay] += 1
            if kind == 'SCAN_MATCH':
                received = integer(evidence.get('receivedElapsedMs'))
                received = received if received is not None and 0 <= received <= elapsed else None
                delivery_delay = integer(evidence.get('queueDelayMs'))
                delivery_delay = delivery_delay if delivery_delay is not None and delivery_delay >= 0 else None
                rssi = integer(evidence.get('rssi'))
                # 잠금/interactive는 광고 수신 시점이 아닌 앱 처리 시점 값이다.
                # 수신 elapsed를 다른 process/clock segment의 wall로 투영하지 않는다.
                t['scanMatches'].append({
                    'receivedElapsedMs': received,
                    'processedWallMs': wall,
                    'queueDelayMs': delivery_delay,
                    'rssi': rssi if rssi is not None and -127 <= rssi <= 20 else None,
                    'interactive': evidence.get('interactive') if version >= 8 and type(evidence.get('interactive')) is bool else None,
                    'deviceLocked': evidence.get('deviceLocked') if version >= 8 and type(evidence.get('deviceLocked')) is bool else None,
                })
            for key, choices in ENUMS.items():
                val = allowed(evidence.get(key), choices)
                if val is not None:
                    t['evidenceDimensions'].setdefault(key, Counter())[val] += 1
            for key in ('accepted', 'localGattActive', 'localGattRecent', 'scanRunning'):
                val = evidence.get(key)
                if type(val) is bool:
                    t['evidenceDimensions'].setdefault(key, Counter())[str(val).lower()] += 1
            for key in ('gattStatus', 'scanFailureCode'):
                val = integer(evidence.get(key))
                t['evidenceDimensions'].setdefault(key, Counter())[str(val) if val is not None else 'unknown'] += 1
            delay = integer(evidence.get('queueDelayMs'))
            if delay is not None and delay >= 0:
                t['queue'][delay] += 1
            candidate = integer(evidence.get('candidate'))
            if candidate is not None and candidate > 0 and kind not in SCAN_V8_KINDS:
                t['candidateIds'].add((process, segment, candidate))
                if version in APPROACH_RECOVERY_VERSIONS and event == 'BLE_EVIDENCE' and kind == 'RETRY_SCHEDULED' and evidence.get('gate') == 'COOLDOWN':
                    t['approachRetryEvidence'].setdefault((process, segment, candidate), []).append({
                        'line': line_no, 'elapsedMs': elapsed, 'clockAlignmentWarning': clock_bad, 'version': version})
                if kind == 'CANDIDATE_SIGNAL':
                    key = (process, segment, candidate)
                    received = integer(evidence.get('receivedElapsedMs'))
                    projected = wall + received - elapsed if received is not None and 0 <= received <= elapsed else None
                    if evidence.get('receivedElapsedMs') is not None and projected is None:
                        issue(line_no, 'INVALID_RECEIVED_TIME')
                        clock_bad = True
                    point = {'processId': process, 'candidate': candidate, 'segment': segment, 'firstCandidateRecordedAt': wall, 'receivedProjectedWallMs': projected, 'timeSource': 'received_elapsed_projection' if projected is not None else 'record_wall', 'clockAlignmentWarning': clock_bad}
                    t['candidates'].setdefault(key, point)
    output_trials = []
    for t in trials.values():
        t['processIds'] = sorted(t['processIds'])
        t['versions'] = sorted(t['versions'])
        finish_approaches(t, issue)
        t['bleTypedSupported'] = any(v >= 5 for v in t['versions'])
        t['candidateSupported'] = any(v >= 6 for v in t['versions'])
        scan_v8_supported = any(v >= 8 for v in t['versions'])
        timer_delays = t.pop('scanTimerDelays')
        t['scanTimerDelayMs'] = distribution(timer_delays) if scan_v8_supported else None
        t['scanRecoveryCount'] = t['evidence']['SCAN_RECOVERY_SCHEDULED'] if scan_v8_supported else None
        t['scanRecoveryExhaustedCount'] = t['evidence']['SCAN_RECOVERY_EXHAUSTED'] if scan_v8_supported else None
        candidate_count = len(t.pop('candidateIds'))
        t['candidateTypedCount'] = candidate_count if t['candidateSupported'] else None
        t['candidateDiagnosticsSupported'] = any(v >= 15 for v in t['versions'])
        t['candidates'] = list(t['candidates'].values())
        t['queueDelayMs'] = distribution(t.pop('queue'))
        t['evidence'] = dict(t['evidence']) if t['candidateSupported'] else None
        t['evidenceDimensions'] = {k: dict(v) for k, v in t['evidenceDimensions'].items()} if t['candidateSupported'] else None
        t['attempts'] = list(t['attempts'].values())
        for a in t['attempts']:
            a['processIds'] = sorted(a['processIds'])
            a['segments'] = sorted(a['segments'])
            a['ended'] = a['final'] is not None
            a['countedComplete'] = a['ended'] and a['conflictingFinals'] == 0 and len(a['processIds']) == 1 and len(a['segments']) == 1 and a['final']['status'] == 'COMPLETE'
        t['finalAttemptCount'] = sum(a['ended'] for a in t['attempts'])
        t['completeAttemptCount'] = sum(a['countedComplete'] for a in t['attempts'])
        t['termination'] = 'abnormal_marker' if t['unclosedMarkers'] else 'normal_stop' if t['lastStopLine'] is not None and (t['lastStartLine'] is None or t['lastStopLine'] > t['lastStartLine']) else 'stop_not_recorded'
        output_trials.append(t)
    uwb_rx_operations = summarize_uwb_rx(uwb_events)
    uwb_native_sessions = summarize_uwb_native(uwb_events)
    connection_operations = summarize_connection(product_events)
    return {'reportVersion': 1, 'counts': {key: totals[key] for key in ('lines', 'analyzedRows', 'malformedRows', 'unsupportedRows', 'excludedRows', 'unassignedRows')}, 'versions': dict(versions), 'processes': dict(processes), 'trials': output_trials, 'frunkEvents': frunk_events, 'uwbEvents': uwb_events, 'uwbRxOperations': uwb_rx_operations, 'uwbNativeSessions': uwb_native_sessions, 'navigationEvents': navigation_events, 'navigationOperations': summarize_navigation(navigation_events), 'productEvents': product_events, 'productSessions': summarize_product(product_events), 'connectionOperations': connection_operations, 'issues': issues, 'excludedTrialsFound': sorted(found), 'excludedTrialsNotFound': sorted(excluded - found), 'clockMismatchObserved': clock_bad, 'logHealth': {'status': 'warnings' if issues else 'no_observed_parse_warnings', 'lossCount': None, 'writerHealth': 'not_in_record_schema', 'completeness': 'not_proven'}, 'visits': None}


def iso_ms(value):
    if not isinstance(value, str) or len(value) > 64:
        raise ValueError
    dt = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if dt.tzinfo is None or dt.utcoffset() is None:
        raise ValueError
    return int(dt.timestamp() * 1000)


def compare_visits(stream, report, offset=None, exclude=()):
    required = ['visitId', 'trialId', 'approachAt', 'handleAt', 'departAt']
    errors, rows = [], []
    # 로그와 같은 행 크기 상한. 여러 줄 CSV 셀은 시각 계약 밖이므로 거부한다.
    lines = iter(bounded_lines(stream))
    _, header, header_error = next(lines, (1, None, 'CSV_HEADER'))
    try:
        valid_header = header_error is None and next(csv.reader([header], strict=True), []) == required
    except csv.Error:
        valid_header = False
    if not valid_header:
        return {'validVisitCount': 0, 'excludedVisitCount': 0, 'clockAligned': offset is not None, 'errors': [{'line': 1, 'code': header_error or 'CSV_HEADER'}], 'observations': [], 'detectedVisitRate': None, 'beforeHandleRate': None}
    excluded_count = 0
    for number, text, error in lines:
        if error:
            errors.append({'line': number, 'code': error})
            continue
        try:
            cells = next(csv.reader([text], strict=True))
            if len(cells) != 5 or not cells[0].isascii() or not cells[0].isdigit():
                raise ValueError
            if any(not value for value in cells[2:]):
                errors.append({'line': number, 'code': 'INCOMPLETE_VISIT'})
                continue
            visit_id = int(cells[0])
            trial = identifier(cells[1])
            if visit_id < 1 or trial == 'invalid':
                raise ValueError
            approach, handle, depart = map(iso_ms, cells[2:])
            if not approach <= handle <= depart:
                raise ValueError
        except (ValueError, OverflowError, csv.Error, StopIteration):
            errors.append({'line': number, 'code': 'INVALID_VISIT'})
            continue
        if trial in exclude:
            excluded_count += 1
            continue
        rows.append({'line': number, 'visitId': visit_id, 'trialId': trial, 'approach': approach, 'handle': handle, 'depart': depart})
    rejected = set()
    ids = {}
    for index, row in enumerate(rows):
        ids.setdefault(row['visitId'], []).append(index)
    for indices in ids.values():
        if len(indices) > 1:
            rejected.update(indices)
            errors.extend({'line': rows[i]['line'], 'code': 'DUPLICATE_VISIT_ID'} for i in indices)
    indices = sorted(range(len(rows)), key=lambda i: rows[i]['approach'])
    active = []
    for index in indices:
        active = [i for i in active if rows[i]['depart'] >= rows[index]['approach']]
        for other in active:
            rejected.update((index, other))
            errors.append({'line': rows[index]['line'], 'code': 'OVERLAPPING_VISIT'})
        active.append(index)
    trials = {t['trialId']: t for t in report['trials']}
    observations = []
    for index, row in enumerate(rows):
        if index in rejected:
            continue
        observation = {'visitId': row['visitId'], 'trialId': row['trialId'], 'detection': 'clock_unverified', 'firstCandidateRecordedAt': None, 'candidateTimeSource': None, 'delayMs': None, 'beforeHandle': None}
        if offset is not None:
            t = trials.get(row['trialId'])
            if report['clockMismatchObserved']:
                observation['detection'] = 'clock_mismatch_unverified'
            elif t is None or not t['candidateSupported']:
                observation['detection'] = 'log_schema_unavailable'
            else:
                matches = []
                for candidate in t['candidates']:
                    at = candidate['receivedProjectedWallMs'] if candidate['receivedProjectedWallMs'] is not None else candidate['firstCandidateRecordedAt']
                    if row['approach'] + offset <= at <= row['depart'] + offset:
                        matches.append((at, candidate))
                if matches:
                    at, candidate = min(matches, key=lambda item: item[0])
                    observation.update(detection='candidate_recorded', firstCandidateRecordedAt=candidate['firstCandidateRecordedAt'], candidateTimeSource=candidate['timeSource'], delayMs=at-row['approach']-offset, beforeHandle=at < row['handle']+offset)
                else:
                    observation['detection'] = 'not_confirmed_in_log'
        observations.append(observation)
    valid = len(observations)
    measurable = valid > 0 and offset is not None and not report['clockMismatchObserved'] and all(o['detection'] in ('candidate_recorded', 'not_confirmed_in_log') for o in observations)
    return {'validVisitCount': valid, 'excludedVisitCount': excluded_count, 'clockAligned': offset is not None and not report['clockMismatchObserved'], 'errors': errors, 'observations': observations, 'detectedVisitRate': sum(o['detection'] == 'candidate_recorded' for o in observations)/valid if measurable else None, 'beforeHandleRate': sum(o['beforeHandle'] is True for o in observations)/valid if measurable else None}


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError('ARGUMENT_ERROR')


def main(argv=None):
    parser = PrivateParser(description='현장 로그 읽기 전용 분석; BLE COMPLETE와 인증 읽기 완료를 구분하며 제어/물리 완료를 뜻하지 않습니다.')
    parser.add_argument('log', nargs='+', metavar='LOG',
                        help='오래된 archive부터 active까지 순서대로 지정; 입력 파일은 수정하지 않습니다.')
    parser.add_argument('--exclude-trial', action='append', default=[], metavar='UUID')
    parser.add_argument('--visits', metavar='CSV')
    parser.add_argument('--uwb-dump', metavar='PATH',
                        help='dumpsys uwb의 mRangingSessionList metric만 태그 대조; 시간 매칭·성공/원인 추론 없음.')
    parser.add_argument('--observer-offset-ms', type=int, metavar='INTEGER')
    parser.add_argument('--json', action='store_true')
    try:
        args = parser.parse_args(argv)
        exclusions = [identifier(v) for v in args.exclude_trial]
        if 'invalid' in exclusions or (args.observer_offset_ms is not None and integer(args.observer_offset_ms) is None):
            raise ValueError
    except ValueError:
        print('ARGUMENT_ERROR', file=sys.stderr)
        return 2
    try:
        with ExitStack() as stack:
            streams = [stack.enter_context(open(path, 'rb')) for path in args.log]
            report = analyze(streams[0], exclusions, additional_streams=streams[1:])
        if args.visits:
            with open(args.visits, 'rb') as stream:
                report['visits'] = compare_visits(stream, report, args.observer_offset_ms, exclusions)
        if args.uwb_dump:
            with open(args.uwb_dump, 'rb') as stream:
                report['uwbSystemMatches'] = match_uwb_system(stream, report)
    except (OSError, UnicodeError):
        print('INPUT_READ_ERROR', file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(report, ensure_ascii=True, allow_nan=False))
    else:
        counts = report['counts']
        print(f"전체 {counts['lines']}행 · 분석 {counts['analyzedRows']}행 · 제외 {counts['excludedRows']}행 · 오류 {len(report['issues'])}건")
        print('로그 유실 수/기록기 건강: 미확인. BLE COMPLETE·구독·0 RX는 차량 인증/거리/프렁크 성공의 증거가 아닙니다.')
        if report['navigationEvents']:
            kinds = Counter(point['observation']['kind'] for point in report['navigationEvents'])
            print(f"위치 sidecar · TX handshake {kinds['HANDSHAKE_TX_COMPLETE']} / query {kinds['QUERY_TX_COMPLETE']} · 인증 handshake accepted {kinds['HANDSHAKE_ACCEPTED']} · RX {kinds['SIDECAR_RX']} · 결과 {kinds['RESULT']} · 종료 {kinds['TERMINAL']} · 시작 {kinds['SIDECAR_STARTED']} · 생략 {kinds['SIDECAR_SKIPPED']}")
            print(f"폰 입력 관측 {sum(kinds[kind] for kind in NAVIGATION_PHONE_KINDS)} · presentation 상태 변경 {kinds['DISPLAY_STATUS_CHANGED']}")
        else:
            print('위치 관측 없음: 구버전/로그 누락/미관측을 구분할 수 없으며 미시도·권한 없음으로 단정하지 않습니다.')
        for point in report['navigationEvents']:
            print(f"위치 정제 관측 {json.dumps(point, ensure_ascii=False, allow_nan=False)}")
        for operation in report['navigationOperations']:
            print(f"위치 회차별 TX/인증/결과/종료·폰 입력·presentation {json.dumps(operation, ensure_ascii=False, allow_nan=False)}")
        if report['navigationEvents']:
            print('위치 sidecar TX·인증·차량 fix 존재·폰 입력·presentation은 별도 관측입니다. 좌표 기반 대략적 안내이며 UWB 실측 거리/정밀 방위/차량 제어 성공이 아닙니다. 결과/종료 미관측은 null이며 이전 회차를 이월하지 않습니다. kindCounts는 기록된 사건 수로 전송/결과의 고유 분모를 증명하지 않습니다. scopeComplete=false는 후보/회차 불명으로 각 행을 분리합니다.')
        if report['uwbEvents']:
            print(f"UWB 측정 · 행 {len(report['uwbEvents'])} · 새 거리 수신 {sum(point['newDistanceReceived'] for point in report['uwbEvents'])}")
        for point in report['uwbEvents']:
            state = point['state']
            distance = state['uwbLastDistanceMeters'] if point['newDistanceReceived'] else None
            print(f"UWB 행 {point['line']} · {point['event']} · 새 거리(m) {distance} · 종료 사유 {state['uwbClosedReason']} · 열기 실패 사유 {state['uwbOpenFailedReason']} · 진단 사유 {state['uwbReason']} · 만료 기한 원인 {state['uwbDeadlineCause']}")
            gatt = {key: state[key] for key in UWB_GATT_KEYS}
            print(f"  GATT 현재 관측 {json.dumps(gatt, ensure_ascii=False, allow_nan=False)}")
            print(f"  GATT 상대 경과(ms) {json.dumps(point['gattTimingMs'], ensure_ascii=False, allow_nan=False)}")
            print(f"  RX 마지막 관측 {json.dumps({key: state[key] for key in UWB_RX_KEYS}, ensure_ascii=False, allow_nan=False)}")
            print(f"  RX 새 사건 {json.dumps(point['rxObservation'], ensure_ascii=False, allow_nan=False)}")
            print(f"  native 마지막 snapshot {json.dumps({key: state[key] for key in UWB_NATIVE_KEYS}, ensure_ascii=False, allow_nan=False)}")
            print(f"  native 새 사건 {json.dumps(point['nativeObservation'], ensure_ascii=False, allow_nan=False)}")
            snapshot_kind = '새 거리 표본' if point['newDistanceReceived'] else '과거 표본 스냅샷'
            sample = {key: state[key] for key in (
                'uwbLastPeerIndex', 'uwbLastDistanceMeters', 'uwbLastConfidence',
                'uwbLastFrameworkTimestampMs', 'uwbLastReceivedElapsedMs',
                'uwbLastAzimuthRaw', 'uwbLastAzimuthConfidence',
                'uwbLastElevationRaw', 'uwbLastElevationConfidence',
                'uwbAzimuthSupported', 'uwbElevationSupported')}
            print(f"  {snapshot_kind} {json.dumps(sample, ensure_ascii=False, allow_nan=False)}")
        if report['uwbEvents']:
            print('backend opened·peer started는 거리 수신이 아닙니다. 과거 샘플 스냅샷은 새 측정으로 세지 않으며 실차 거리/제어 성공을 단정하지 않습니다. raw 각도는 단위·방향을 해석하지 않은 관측이며 차량 위치나 정밀 방위가 아닙니다. framework timestamp의 시계 기준은 미확인이고 보정하지 않습니다.')
            print('GATT 접수 true는 SDK 접수일 뿐 연결 완료가 아닙니다. connectedObserved false는 이번 작업의 연결 성공 미관측이며 연결 불가 확정이 아닙니다. null은 미관측/구버전, 0 status와 RX 횟수도 원값입니다. GATT 현재 관측은 마지막 단계/사건의 스냅샷이며 현재 연결 유지나 새 거리 수신을 뜻하지 않습니다.')
            print('RX 총분모(counterTotals)는 작업별 누적 notification/frame/protocol이며 observedEvents는 로그에 실제 남은 중복 없는 새 사건의 분모입니다. 마지막 metadata 스냅샷은 분류에 재합산하지 않습니다. decoder false·0·null은 성공이 아니며 AEAD VERIFIED와 HMAC 인증·FiRa 입력·거리 성공은 별개입니다. domain/fault/status 원값은 진위 증명이 아닙니다.')
            print('schema27 본문 shape와 request correlation은 새 PROTOCOL 사건의 부분집합입니다. COMPLETE 빈 본문은 미관측 null과 다르며 TRUNCATED는 항목 관측 상한입니다. CURRENT/PREVIOUS는 동일 identity의 header 대조일 뿐 인증·수락·복호 성공이 아닙니다. schema1–26에서는 새 4개 key를 미관측으로 둡니다.')
        for operation in report['uwbRxOperations']:
            print(f"UWB RX 작업별 분모/decoder/분류/대기(ms) {json.dumps(operation, ensure_ascii=False, allow_nan=False)}")
        for session in report['uwbNativeSessions']:
            print(f"UWB native endpoint 세션 {json.dumps(session, ensure_ascii=False, allow_nan=False)}")
        if 'uwbSystemMatches' in report:
            print(f"UWB OS 별도 관측·태그 대조 {json.dumps(report['uwbSystemMatches'], ensure_ascii=False, allow_nan=False)}")
            print('태그의 고유 일치는 세션 상관 근거일 뿐 SESSION_SUCCESS·충돌 원인이 아닙니다. OS initTime은 년·zone 없는 별도 관측이며 시각만으로 자동 매칭하지 않습니다. closeRequested는 해당 endpoint의 로컬 정리 선행 여부이지 SYSTEM_POLICY 원인 확정이 아닙니다.')
        for operation in report['connectionOperations']:
            print(f"제품 연결 작업별 단계/기한/수신 분모/인증 판정 {json.dumps(operation, ensure_ascii=False, allow_nan=False)}")
        if report['connectionOperations']:
            print('연결 counterTotals는 notification/frame/protocol 누적 분모이고 observedEvents는 기록된 신규 counter 사건만 셉니다. RX 조각·STAGE·종료 복제 snapshot은 protocol 판정이 아닙니다. counter 역행은 같은 작업의 새 성공으로 합산하지 않습니다.')
            print('HANDSHAKING/READING_STATUS는 관측 단계이며 인증 성공·거절이 아닙니다. SESSION_READY는 실제 신규 PROTOCOL의 세션 인증 판정, STATUS_VERIFIED/AEAD VERIFIED는 별도 판정이며 fresh P·프렁크·음성 시작을 증명하지 않습니다. SDK writeRequestStatus=0·CCCD 성공도 인증 성공이 아닙니다. deadlineCause는 최초 기록된 원인만 보존하고 null/unobserved는 미확인입니다.')
            print('schema37 driveShiftState는 인증 DRIVE의 shift 필드 분류이며 명시 P/R/N/D 외에는 기어를 추론하지 않습니다. driveTimestampStatus VALID는 canonical 양수 시각일 뿐 fresh P·최신 시각이 아닙니다. 두 분류는 상관 일치 domain3 인증 응답에서만 유효하며 null/unobserved는 미확인입니다.')
        else:
            print('제품 연결 GATT 상세 미관측: 구버전/로그 누락을 무수신 0·인증 성공·인증 거절로 보충하지 않습니다. 단계·기한·수신 분모·인증 원인은 미확인입니다.')
        missing_connection_observations = sum(point['event'] == CONNECTION_GATT_EVENT and
            point['connectionObservation'] is None for point in report['productEvents'])
        if missing_connection_observations:
            print(f"제품 연결 상세 없는 GATT 행 {missing_connection_observations}개: 해당 행의 수신·protocol 판정은 미확인입니다.")
        for point in report['productEvents']:
            if point['event'] == PRODUCT_VOICE_DIAGNOSTIC_EVENT:
                print(f"제품 음성 진단 {json.dumps(point['productVoiceDiagnostic'], ensure_ascii=False, allow_nan=False)}")
        if any(point['productVoiceDiagnostic'] is not None for point in report['productEvents']):
            print('voiceOutcomes는 전용 사건의 정제된 outcome을 행당 한 번 집계하며 null은 구버전/미관측이지 0회가 아닙니다. ACCEPTED는 실제 마이크·stream·recognizer 정리 true가 필요하고 CLOSED는 recognizer 정리 true가 필요합니다. PREPARED의 recognizer 정리 false는 소유 중 관측이며 실패가 아닙니다. 진단은 fresh P·마이크 시작·차량 명령 허가나 물리 성공의 근거가 아닙니다.')
        for session in report['productSessions']:
            print(f"연결·제품 음성 관측 세션 {json.dumps(session, ensure_ascii=False, allow_nan=False)}")
        if report['productEvents']:
            print('제품 인식 원문·PCM 파일은 기록하지 않습니다. commandAcceptedCount는 인식 승인·handoff 사건 수이며 actuator 전송·물리 성공이 아닙니다. frunkObserved는 연결 시작~KEY 정리와 같은 scope에서 바로 뒤에 관측된 FRUNK 정리까지의 실제 프렁크 전이 event와 해당 Boolean만 집계하며 과거 snapshot true는 재사용하지 않습니다. 새 연결 시작·다른 제품 사건·시계 segment·프로세스·시험 경계는 이전 종료 관측과 합치지 않습니다. STOPPED/COMMAND_READY만으로 마이크 정리 성공을 추론하지 않으며 microphoneCleanupConfirmed는 미관측 null입니다. fresh P는 당시 snapshot이지 현재 운행 안전 증명이 아닙니다.')
        for point in report['frunkEvents']:
            print(f"실차 프렁크 관측 {json.dumps(point, ensure_ascii=False, allow_nan=False)}")
        if report['frunkEvents']:
            print('요청·SDK 전송·차량 응답·후속 열림 상태를 구분합니다. 누락/UNKNOWN을 열림으로 승격하지 않으며 실제 후드 개방 범위는 사용자 실차 확인이 필요합니다.')
        termination_labels = {'normal_stop': '정상 정지', 'abnormal_marker': '비정상 종료 표식', 'stop_not_recorded': '종료 미기록'}
        for trial in report['trials']:
            print(f"시험 {trial['trialId']} · 종료 {termination_labels[trial['termination']]} · 최종 회차 {trial['finalAttemptCount']} · COMPLETE {trial['completeAttemptCount']} · 후보 {trial['candidateTypedCount'] if trial['candidateSupported'] else '미지원'}")
            print(f"스캔 타이머 지연(ms) {trial['scanTimerDelayMs']} · 복구 예약 {trial['scanRecoveryCount']} · 복구 소진 {trial['scanRecoveryExhaustedCount']}")
            for match in trial['scanMatches']:
                print(f"스캔 일치 {json.dumps(match, ensure_ascii=True, allow_nan=False)}")
            if trial['scanMatches']:
                print('스캔 잠금/interactive는 앱 처리 시점 값이며 광고 수신 시점 상태를 증명하지 않습니다.')
            if trial['approachReadSupported']:
                print(f"접근 인증 읽기 · 시작 {trial['approachStartedReadCount']} · 최종 {trial['approachFinalReadCount']} · 전체완료 {trial['approachCompleteReadCount']} · 제한복구 {trial['approachRecoveryReadCount']} · 부분검증 {trial['approachPartialReadCount']} · 정리실패 {trial['approachCleanupFailedCount']} · 기한만료 {trial['approachDeadlineExpiredCount']}")
                for approach in trial['approaches']:
                    print(f"접근당 조회 {json.dumps(approach, ensure_ascii=True, allow_nan=False)}")
                for read in trial['approachReadAttempts']:
                    print(f"인증 읽기 결과/단계/시각 {json.dumps(read, ensure_ascii=True, allow_nan=False)}")
                for blocked in trial['approachReadBlocks']:
                    print(f"인증 읽기 차단 {json.dumps(blocked, ensure_ascii=True, allow_nan=False)}")
                print('전체완료는 BODY·DRIVE 검증 및 최종 COMPLETE·local close·lease 반환의 로그 근거입니다. 지난 관측이며 제어 명령·물리 완료가 아닙니다. 원 DRIVE wall 시각 미관측은 null입니다.')
                if any(version in APPROACH_RECOVERY_VERSIONS for version in trial['versions']):
                    print('v18–v37 제한복구는 같은 후보의 clean transient 종료·COOLDOWN 예약·3.5초 대기·최대 2회·첫 시작부터 관측된 40초 예산의 근거입니다. 반복 관측과 정책 위반을 구분하며 새 광고 수신 자체나 복구 성공을 뜻하지 않습니다.')
        for issue in report['issues']:
            print(f"행 {issue['line']}: {issue['code']}")
        for trial in report['excludedTrialsNotFound']:
            print(f'제외 UUID 미발견: {trial}')
        if report['visits'] is not None:
            visits = report['visits']
            print(f"유효 방문 기록 {visits['validVisitCount']} · 시계 정합 {'확인' if visits['clockAligned'] else '미확인'} · 로그 후보 근거율 {visits['detectedVisitRate']} · 핸들 전 로그 후보 근거율 {visits['beforeHandleRate']}")
            for error in visits['errors']:
                print(f"CSV 행 {error['line']}: {error['code']}")
        else:
            print('방문 기록 분모/로그 후보 근거율: 별도 CSV 없음, 미확인.')
    return 1 if (report['issues'] or (report['visits'] and report['visits']['errors']) or
                 report.get('uwbSystemMatches', {}).get('issues')) else 0


if __name__ == '__main__':
    sys.exit(main())
