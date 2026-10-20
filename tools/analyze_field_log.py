#!/usr/bin/env python3
"""읽기 전용 v1–v22 현장 로그 분석. 회전 파일은 오래된 archive→active 순서로 입력한다."""
import argparse
import csv
import json
import sys
import uuid
from collections import Counter
from contextlib import ExitStack
from datetime import datetime, timezone

MAX_LINE = 65536
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
    safe['responseDisposition'] = allowed(value.get('responseDisposition'), RESPONSE_DISPOSITIONS) if version >= 11 else None
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
    """v18–v22의 두 번째 시작만 허용한다. 복구 관측은 광고 수신·검증 성공 자체가 아니다."""
    if len(attempts) != 2:
        return False
    first, second = attempts
    for a in attempts:
        if (a['versions'] not in ([18], [19], [20], [21], [22]) or a['startCount'] != 1 or a['timeBoundaryObserved'] or
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
        if version not in range(1, 23):
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
        if (version >= 21 and process != 'invalid' and row.get('event') in FRUNK_EVENTS and
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
        recovery_row = version >= 8 and isinstance(row.get('bleEvidence'), dict) and row['bleEvidence'].get('kind') in SCAN_RECOVERY_KINDS
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
                if version in (18, 19, 20, 21, 22) and event == 'BLE_EVIDENCE' and kind == 'RETRY_SCHEDULED' and evidence.get('gate') == 'COOLDOWN':
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
    return {'reportVersion': 1, 'counts': {key: totals[key] for key in ('lines', 'analyzedRows', 'malformedRows', 'unsupportedRows', 'excludedRows', 'unassignedRows')}, 'versions': dict(versions), 'processes': dict(processes), 'trials': output_trials, 'frunkEvents': frunk_events, 'issues': issues, 'excludedTrialsFound': sorted(found), 'excludedTrialsNotFound': sorted(excluded - found), 'clockMismatchObserved': clock_bad, 'logHealth': {'status': 'warnings' if issues else 'no_observed_parse_warnings', 'lossCount': None, 'writerHealth': 'not_in_record_schema', 'completeness': 'not_proven'}, 'visits': None}


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
    except (OSError, UnicodeError):
        print('INPUT_READ_ERROR', file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(report, ensure_ascii=True, allow_nan=False))
    else:
        counts = report['counts']
        print(f"전체 {counts['lines']}행 · 분석 {counts['analyzedRows']}행 · 제외 {counts['excludedRows']}행 · 오류 {len(report['issues'])}건")
        print('로그 유실 수/기록기 건강: 미확인. BLE COMPLETE·구독·0 RX는 차량 인증/거리/프렁크 성공의 증거가 아닙니다.')
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
                if any(version in (18, 19, 20, 21, 22) for version in trial['versions']):
                    print('v18–v22 제한복구는 같은 후보의 clean transient 종료·COOLDOWN 예약·3.5초 대기·최대 2회·첫 시작부터 관측된 40초 예산의 근거입니다. 반복 관측과 정책 위반을 구분하며 새 광고 수신 자체나 복구 성공을 뜻하지 않습니다.')
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
    return 1 if report['issues'] or (report['visits'] and report['visits']['errors']) else 0


if __name__ == '__main__':
    sys.exit(main())
