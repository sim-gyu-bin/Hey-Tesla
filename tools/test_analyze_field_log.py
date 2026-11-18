"""파일 생성/삭제 없이 StringIO로 검증하는 소비자 계약 회귀."""
import contextlib
import hashlib
import io
import json
import unittest
from unittest.mock import patch

from analyze_field_log import (MAX_LINE, UWB_NATIVE_KEYS, PRODUCT_KEYS, PRODUCT_ENUMS, PRODUCT_BOOLS,
                               FRUNK_STATE_KEYS, PRODUCT_EVENTS, product_state,
                               analyze, compare_visits, main, match_uwb_system,
                               uwb_native_state, uwb_rx_state, uwb_session_tag)

P = '11111111-1111-4111-8111-111111111111'
Q = '22222222-2222-4222-8222-222222222222'
T = '33333333-3333-4333-8333-333333333333'
U = '44444444-4444-4444-8444-444444444444'
BASE = 1767225600000
HEADER = 'visitId,trialId,approachAt,handleAt,departAt\n'
GATT_BOOLS = ('uwbGattAutoConnect', 'uwbGattConnectAccepted', 'uwbGattConnectedObserved',
    'uwbGattServiceFound', 'uwbGattTxFound', 'uwbGattRxFound', 'uwbGattSubscriptionConfirmed')
GATT_INTS = ('uwbGattConnectionStatus', 'uwbGattConnectionState',
    'uwbGattDescriptorRequestStatus', 'uwbGattDescriptorCallbackStatus')
GATT_TIMES = ('uwbGattOperationStartedElapsedMs', 'uwbGattStageStartedElapsedMs',
    'uwbGattObservedElapsedMs', 'uwbGattConnectIssuedElapsedMs', 'uwbGattConnectReturnedElapsedMs',
    'uwbGattAdvertisementAgeAtConnectMs', 'uwbGattConnectionCallbackElapsedMs',
    'uwbGattDescriptorCallbackElapsedMs', 'uwbGattFirstRxElapsedMs', 'uwbGattLastRxElapsedMs')
GATT_KEYS = ('uwbGattStage', 'uwbGattEvent', 'uwbGattConnectAction', 'uwbDeadlineCause',
    *GATT_BOOLS, *GATT_INTS, *GATT_TIMES, 'uwbGattRxNotificationCount')
RX_BOOLS = ('uwbRxDecoderIncomplete', 'uwbRxDecoderFailed', 'uwbRxFromPresent',
    'uwbRxToPresent', 'uwbRxRequestUuidPresent', 'uwbRxRequestUuidMatches',
    'uwbRxToRouteMatches', 'uwbRxSignedStatusPresent')
RX_INTS = ('uwbRxExpectedDomain', 'uwbRxFromDomain', 'uwbRxSignedMessageFault', 'uwbRxOperationStatus')
RX_TIMES = ('uwbRxProtocolReceivedElapsedMs', 'uwbRxProtocolObservedElapsedMs')
RX_COUNTS = ('uwbRxFrameCount', 'uwbRxProtocolCount')
RX_ENUMS = {
    'uwbRxStep': ('WAITING', 'SEND', 'COMPLETE', 'FRUNK_RESULT', 'FAILED', 'UWB_READY', 'UWB_INPUT'),
    'uwbRxInputKind': ('ROUTABLE_MESSAGE', 'RAW_FIRA', 'WRAPPED_PLAINTEXT', 'ENCRYPTED_RESPONSE'),
    'uwbRxAead': ('NOT_ATTEMPTED', 'VERIFIED', 'FAILED'),
    'uwbRxFiraKind': ('REQUEST', 'STOP', 'UNRECOGNIZED', 'INVALID'),
    'uwbRxAuthEvidence': ('ACKNOWLEDGED', 'REQUEST_REJECTED', 'KEY_NOT_PAIRED_REPORTED',
        'OPERATION_REJECTED', 'UNEXPECTED_PAYLOAD', 'SESSION_STATUS_UNSUPPORTED',
        'SIGNATURE_MISSING', 'TAG_MISSING', 'TAG_LENGTH_INVALID', 'PARAMETERS_INVALID',
        'HMAC_MISMATCH', 'SESSION_INFO_INVALID'),
    'uwbRxDisposition': ('DOMAIN_MISMATCH', 'ROUTE_MISMATCH', 'REQUEST_UUID_MISMATCH',
        'ACK_WAITING', 'SESSION_INFO_INVALID', 'SESSION_INFO_AUTH_REJECTED', 'SESSION_READY',
        'STATUS_VERIFIED', 'STATUS_INCOMPLETE', 'RESPONSE_REJECTED', 'RESPONSE_INVALID',
        'RESPONSE_WAITING', 'FIRA_REQUEST', 'FIRA_STOP', 'FIRA_UNRECOGNIZED', 'FIRA_INVALID'),
    'uwbRxFromKind': ('DOMAIN', 'ROUTING_ADDRESS', 'UNSET', 'UNKNOWN'),
    'uwbRxToKind': ('DOMAIN', 'ROUTING_ADDRESS', 'UNSET', 'UNKNOWN'),
    'uwbRxPayloadKind': ('SESSION_INFO', 'SESSION_INFO_REQUEST', 'PROTOBUF_MESSAGE_AS_BYTES', 'UNSET', 'UNKNOWN'),
    'uwbRxDomainMismatchReason': ('FROM_MISSING', 'FROM_NOT_DOMAIN', 'DOMAIN_DIFFERENT'),
}
RX_KEYS = (*RX_COUNTS, 'uwbRxNotificationFrameCount', *RX_BOOLS, *RX_TIMES, *RX_INTS, *RX_ENUMS)
RX_BODY_KEYS = ('uwbRxBodyBytes', 'uwbRxBodyStatus', 'uwbRxBodyFields')
RX_OBSERVATION_KEYS = (*RX_BODY_KEYS, 'uwbRxRequestCorrelation')
NAVIGATION_BOOL_KEYS = ('fineLocationGranted', 'backgroundLocationGranted', 'locationEnabled',
    'foregroundArmed', 'collecting', 'locationUpdatesRegistered', 'headingSensorRegistered',
    'cleanupSucceeded', 'requestUuidMatches', 'toRouteMatches', 'vehicleFixPresent')
NAVIGATION_KEYS = ('kind', 'reason', 'candidate', 'attempt', *NAVIGATION_BOOL_KEYS,
    'status', 'disposition', 'authEvidence')


def record(**changes):
    row = dict(version=6, processId=P, trialId=T, wallMs=BASE, elapsedMs=1000, event='BLE_EVIDENCE', state={})
    row.update(changes)
    return row


def parse(*rows, exclude=()):
    return analyze(io.StringIO(''.join(json.dumps(row)+'\n' for row in rows)), exclude)


def uwb(event='TESLA_UWB_DISTANCE_RECEIVED', offset=0, **changes):
    state = dict(uwbStage='RANGING', uwbBackendOpened=True, uwbPeerCount=2,
        uwbStartedPeerCount=1, uwbValidDistanceCount=1, uwbInvalidResultCount=0,
        uwbLastPeerIndex=0, uwbLastDistanceMeters=1.25, uwbLastConfidence=2,
        uwbLastFrameworkTimestampMs=9876543210123, uwbLastReceivedElapsedMs=990,
        uwbLastAzimuthRaw=-12.5, uwbLastAzimuthConfidence=0,
        uwbLastElevationRaw=-0.25, uwbLastElevationConfidence=2,
        uwbAzimuthSupported=True, uwbElevationSupported=False,
        uwbClosedReason=None, uwbOpenFailedReason=None, uwbReason=None)
    state.update(dict.fromkeys(GATT_KEYS))
    state.update(dict.fromkeys(RX_KEYS))
    state.update(dict.fromkeys(RX_OBSERVATION_KEYS))
    state.update(changes)
    return record(version=24, trialId=None, event=event,
        wallMs=BASE+offset, elapsedMs=1000+offset, state=state)


def uwb_gatt(event='TESLA_UWB_GATT_OBSERVATION', offset=0, **changes):
    state = dict(uwbGattStage='CONNECTING', uwbGattEvent='CONNECT_RESULT',
        uwbGattOperationStartedElapsedMs=100, uwbGattStageStartedElapsedMs=200,
        uwbGattObservedElapsedMs=900, uwbGattConnectIssuedElapsedMs=200,
        uwbGattConnectReturnedElapsedMs=210, uwbGattAutoConnect=False,
        uwbGattConnectAction='CREATE', uwbGattConnectAccepted=False,
        uwbGattAdvertisementAgeAtConnectMs=0, uwbGattConnectionStatus=None,
        uwbGattConnectionState=None, uwbGattConnectionCallbackElapsedMs=None,
        uwbGattDescriptorRequestStatus=None, uwbGattDescriptorCallbackStatus=None,
        uwbGattDescriptorCallbackElapsedMs=None, uwbGattFirstRxElapsedMs=None,
        uwbGattLastRxElapsedMs=None, uwbGattConnectedObserved=False,
        uwbGattServiceFound=False, uwbGattTxFound=False, uwbGattRxFound=False,
        uwbGattSubscriptionConfirmed=False, uwbGattRxNotificationCount=0)
    state.update(changes)
    return dict(uwb(event, offset, **state), version=25)


def uwb_rx(event='TESLA_UWB_GATT_OBSERVATION', offset=0, **changes):
    return dict(uwb_gatt(event, offset, **changes), version=26)

def uwb_rx_observation(event='TESLA_UWB_GATT_OBSERVATION', offset=0, **changes):
    return dict(uwb_rx(event, offset, **changes), version=27)


def navigation(kind='SIDECAR_STARTED', offset=0, **changes):
    observation = dict.fromkeys(NAVIGATION_KEYS)
    observation.update(kind=kind, candidate=1, attempt=1)
    observation.update(changes)
    return record(version=28, event='VEHICLE_NAVIGATION_OBSERVATION',
        wallMs=BASE+offset, elapsedMs=1000+offset, navigationObservation=observation)



def visits(*rows):
    return io.StringIO(HEADER + ''.join(','.join(map(str, row))+'\n' for row in rows))


def approach(event='TESLA_APPROACH_READ_STAGE', offset=0, **changes):
    snapshot = dict(candidate=1, attempt=1, stage='READING_STATUS', query='BODY_STATUS',
        outcome='PENDING', reason=None, bodyVerified=False, driveVerified=False,
        bodyReceivedElapsedMs=None, driveReceivedElapsedMs=None, driveSourceWallMs=None,
        gear=None, lockState=None, frontTrunkState=None, localClosed=False, leaseRetained=False)
    snapshot.update(changes)
    return record(version=9, event=event, wallMs=BASE+offset, elapsedMs=1000+offset,
        state={'bleFieldAuthenticatedRead': True}, teslaApproachRead=snapshot)


def complete_approach(offset=0, **changes):
    snapshot = dict(stage='COMPLETE', query='DRIVE_STATE', outcome='VERIFIED_STATUS',
        reason='AUTHENTICATED_READ_COMPLETE', bodyVerified=True, driveVerified=True,
        localClosed=True, leaseRetained=False)
    snapshot.update(changes)
    return approach('TESLA_APPROACH_READ_FINISHED', offset, **snapshot)


def recovery_rows(stage='FAILED', reason='GATT_CONNECT_FAILED', second_start=3700):
    rows = [
        approach('TESLA_APPROACH_READ_STARTED', stage='PAUSING_SCAN', query=None),
        approach(offset=100, stage='CLEANING_UP', outcome='FAILED', reason=reason),
        approach(offset=200, stage=stage, outcome='FAILED', reason=reason,
            localClosed=True, leaseRetained=False),
        record(version=18, wallMs=BASE+200, elapsedMs=1200,
            bleEvidence={'kind': 'RETRY_SCHEDULED', 'gate': 'COOLDOWN', 'candidate': 1}),
        approach('TESLA_APPROACH_READ_FINISHED', 201, stage=stage, outcome='FAILED',
            reason=reason, localClosed=True, leaseRetained=False),
        approach('TESLA_APPROACH_READ_STARTED', second_start, attempt=2, stage='PAUSING_SCAN', query=None),
        approach('TESLA_APPROACH_BODY_VERIFIED', second_start+100, attempt=2,
            bodyVerified=True, bodyReceivedElapsedMs=1100+second_start),
        approach('TESLA_APPROACH_DRIVE_VERIFIED', second_start+200, attempt=2, query='DRIVE_STATE',
            bodyVerified=True, driveVerified=True, bodyReceivedElapsedMs=1100+second_start,
            driveReceivedElapsedMs=1200+second_start),
        complete_approach(second_start+300, attempt=2,
            bodyReceivedElapsedMs=1100+second_start, driveReceivedElapsedMs=1200+second_start),
    ]
    for row in rows:
        row['version'] = 18
    return rows


def native_uwb(event='PEER_STOPPED', offset=0, session_id=-123456789, **changes):
    state = dict(uwbNativeSessionTag=uwb_session_tag(session_id), uwbNativeConfigId=1,
        uwbNativeChannel=9, uwbNativePreambleIndex=10, uwbNativeUpdateRate=1,
        uwbNativePeerCount=5, uwbNativeEvent=event,
        uwbNativePeerIndex=4 if event in ('PEER_STARTED', 'PEER_STOPPED') else None,
        uwbNativeElapsedSinceStartMs=123, uwbNativeCloseRequested=False)
    state.update(changes)
    event_code = {'STARTING': 'TESLA_UWB_BACKEND_STARTING', 'OPENED': 'TESLA_UWB_BACKEND_OPENED',
        'PEER_STARTED': 'TESLA_UWB_PEER_STARTED', 'PEER_STOPPED': 'TESLA_UWB_PEER_STOPPED',
        'CLOSED': 'TESLA_UWB_BACKEND_CLOSED'}[event]
    return dict(uwb(event_code, offset, **state), version=31)


def uwb_metric(session_id=-123456789, **changes):
    fields = dict(initTime='10-29 16:32:10.123', sessionId=str(session_id), initLatencyMs='2',
        activeDurationMs='6000', rangingCount='61', validRangingCount='0', startCount='1',
        startFailureCount='0', initStatus='1', channel='9', uid='12345',
        packageName='com.google.android.gms', rangingIntervalMs='100', parallelSessionCount='2')
    fields.update(changes)
    return ', '.join(f'{key}={value}' for key, value in fields.items()) + '\n'


def uwb_dump(*metrics):
    return io.StringIO('---- Dump of UwbMetrics ----\n-- mRangingSessionList --\n' +
        ''.join(metrics) + '-- mOpenedSessionMap --\n' + ''.join(metrics) +
        '-- mRangingReportList --\ntime=PRIVATE-COORDINATES\n')


class ProductVoiceDiagnosticTests(unittest.TestCase):
    @staticmethod
    def row(outcome='ACCEPTED', offset=10, **diagnostic):
        payload = dict(outcome=outcome, utteranceBytes=32000, voicedBytes=16000,
            suppressedOnsets=2, silenceBytesAtEnd=8000, captureMs=1000,
            loadMs=20, decodeMs=30, cleanupMs=4, modelReused=True,
            microphoneReleased=True, streamCleanupConfirmed=True, recognizerCleanupConfirmed=True)
        payload.update(diagnostic)
        return record(version=39, event='PRODUCT_VOICE_DIAGNOSTIC',
            wallMs=BASE+offset, elapsedMs=1000+offset, productVoiceDiagnostic=payload)

    @staticmethod
    def start(offset=0):
        return record(version=39, event='TESLA_CONNECTION_STARTED',
            wallMs=BASE+offset, elapsedMs=1000+offset)

    def test_only_dedicated_sanitized_outcomes_are_counted_without_granting_control(self):
        rows = [self.start(),
            self.row('PREPARED', 1, recognizerCleanupConfirmed=False),
            self.row('EMPTY', 2, recognizerCleanupConfirmed=False),
            self.row('GRAMMAR_NO_MATCH', 3, recognizerCleanupConfirmed=False),
            self.row('ACCEPTED', 4),
            dict(self.row('ACCEPTED', 5), event='PRODUCT_VOICE_STATE_CHANGED'),
            self.row('CLOSED', 6)]
        report = parse(*rows)
        session, = report['productSessions']
        self.assertEqual(session['voiceOutcomes'],
            {'PREPARED': 1, 'EMPTY': 1, 'GRAMMAR_NO_MATCH': 1, 'ACCEPTED': 1, 'CLOSED': 1})
        self.assertIsNone(report['productEvents'][5]['productVoiceDiagnostic'])
        self.assertEqual(session['commandAcceptedCount'], 0)
        self.assertEqual(session['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
        self.assertFalse(session['cleanupFailureObserved'])
        self.assertIsNone(session['microphoneCleanupConfirmed'])
        self.assertTrue(all(point['state']['connectionParkFresh'] is None
                            for point in report['productEvents']))
        self.assertTrue(all(point['state']['productVoiceStage'] is None
                            for point in report['productEvents']))
        self.assertEqual(report['reportVersion'], 1)

    def test_cleanup_false_missing_or_wrong_type_cannot_forge_accepted_or_closed(self):
        for outcome in ('EMPTY', 'GRAMMAR_NO_MATCH', 'ACCEPTED', 'CLOSED'):
            required = ['recognizerCleanupConfirmed'] if outcome == 'CLOSED' else [
                'microphoneReleased', 'streamCleanupConfirmed']
            if outcome == 'ACCEPTED':
                required.append('recognizerCleanupConfirmed')
            for key in required:
                for value in (None, False, 1, 0, 'true', [], {}):
                    with self.subTest(outcome=outcome, key=key, value=value):
                        row = self.row(outcome, **{key: value})
                        report = parse(self.start(), row)
                        safe = report['productEvents'][1]['productVoiceDiagnostic']
                        self.assertIsNone(safe['outcome'])
                        self.assertIs(safe[key], False if value is False else None)
                        self.assertEqual(report['productSessions'][0]['voiceOutcomes'], {})
                        self.assertEqual(report['productSessions'][0]['commandAcceptedCount'], 0)
                        self.assertIsNone(report['productSessions'][0]['frunkObserved']['frunkCommandSent'])
        for outcome in ('FAILURE', 'SEALED'):
            report = parse(self.start(), self.row(outcome, microphoneReleased=False,
                streamCleanupConfirmed=False, recognizerCleanupConfirmed=False))
            safe = report['productEvents'][1]['productVoiceDiagnostic']
            self.assertEqual(safe['outcome'], outcome)
            for key in ('microphoneReleased', 'streamCleanupConfirmed', 'recognizerCleanupConfirmed'):
                self.assertIs(safe[key], False)
            self.assertEqual(report['productSessions'][0]['voiceOutcomes'], {outcome: 1})
        closed = parse(self.start(), self.row('CLOSED', microphoneReleased=False,
            streamCleanupConfirmed=False))
        self.assertEqual(closed['productSessions'][0]['voiceOutcomes'], {'CLOSED': 1})

    def test_private_text_numeric_strings_and_bad_metrics_do_not_escape(self):
        secret = 'PRIVATE-TRANSCRIPT-PCM-VIN-ADDRESS-KEY-UUID-RAW-RESPONSE'
        ranges = {'utteranceBytes': (0, 320000), 'voicedBytes': (0, 320000),
            'silenceBytesAtEnd': (0, 320000), 'suppressedOnsets': (0, 2**31-1),
            'captureMs': (0, 86400000), 'loadMs': (0, 600000),
            'decodeMs': (0, 600000), 'cleanupMs': (0, 600000)}
        for key, (lower, upper) in ranges.items():
            for value in (secret, '123456789012345678901234567890', str(upper),
                          True, False, 0.0, [], {'raw': secret}, -1, upper+1, 2**63):
                with self.subTest(key=key, value=value):
                    row = self.row('SEALED', utteranceBytes=320000)
                    row['productVoiceDiagnostic'].update({key: value, 'transcript': secret,
                        'pcm': secret, 'vin': secret, 'uuid': secret, 'hash': secret,
                        'vehicleTimestamp': secret, 'rawResponse': secret})
                    report = parse(self.start(), row)
                    safe = report['productEvents'][1]['productVoiceDiagnostic']
                    self.assertIsNone(safe[key])
                    self.assertEqual(report['productSessions'][0]['voiceOutcomes'], {'SEALED': 1})
                    self.assertNotIn(secret, json.dumps(report))
                    self.assertNotIn('123456789012345678901234567890', json.dumps(report))
            for value in (lower, upper):
                row = self.row('SEALED', utteranceBytes=320000)
                row['productVoiceDiagnostic'][key] = value
                safe = parse(row)['productEvents'][0]['productVoiceDiagnostic']
                self.assertEqual(safe[key], value)
        for key in ('utteranceBytes', 'voicedBytes', 'silenceBytesAtEnd'):
            row = self.row('SEALED', **{key: 1})
            self.assertIsNone(parse(row)['productEvents'][0]['productVoiceDiagnostic'][key])
        for utterance in (None, -2, 1, 320002):
            safe = parse(self.row('SEALED', utteranceBytes=utterance))['productEvents'][0]['productVoiceDiagnostic']
            self.assertIsNone(safe['utteranceBytes'])
            self.assertIsNone(safe['voicedBytes'])
            self.assertIsNone(safe['silenceBytesAtEnd'])
        for key in ('voicedBytes', 'silenceBytesAtEnd'):
            safe = parse(self.row('SEALED', **{key: 32002}))['productEvents'][0]['productVoiceDiagnostic']
            self.assertIsNone(safe[key])
        for outcome in (secret, 'accepted', ' ACCEPTED', 1, True, [], {}):
            report = parse(self.start(), self.row(outcome))
            self.assertIsNone(report['productEvents'][1]['productVoiceDiagnostic']['outcome'])
            self.assertEqual(report['productSessions'][0]['voiceOutcomes'], {})
            self.assertNotIn(secret, json.dumps(report))
        for value in (secret, [], True, None):
            report = parse(self.start(), dict(self.row(), productVoiceDiagnostic=value))
            self.assertIsNone(report['productEvents'][1]['productVoiceDiagnostic'])
            self.assertIsNone(report['productSessions'][0]['voiceOutcomes'])

    def test_prepared_and_closed_cannot_reuse_utterance_snapshot(self):
        for outcome in ('PREPARED', 'CLOSED'):
            safe = parse(self.row(outcome))['productEvents'][0]['productVoiceDiagnostic']
            for key in ('utteranceBytes', 'voicedBytes', 'suppressedOnsets',
                        'silenceBytesAtEnd', 'captureMs', 'decodeMs', 'modelReused'):
                self.assertIsNone(safe[key])
            self.assertEqual(safe['loadMs'], 20)
            self.assertEqual(safe['cleanupMs'], 4)
        for value in ('true', 1, [], {}):
            safe = parse(self.row('SEALED', modelReused=value))['productEvents'][0]['productVoiceDiagnostic']
            self.assertIsNone(safe['modelReused'])

    def test_no_backport_of_diagnostic_event_payload_or_counts_and_future_40_rejected(self):
        for version in range(1, 39):
            injected = dict(self.row(), version=version)
            report = parse(injected)
            self.assertEqual(report['productEvents'], [])
            self.assertEqual(report['productSessions'], [])
            historical = dict(injected, event='PRODUCT_VOICE_STATE_CHANGED')
            report = parse(historical)
            if version >= 33:
                self.assertIsNone(report['productEvents'][0]['productVoiceDiagnostic'])
                self.assertIsNone(report['productSessions'][0]['voiceOutcomes'])
            else:
                self.assertEqual(report['productEvents'], [])
        report = parse(dict(self.row(), version=40), self.row())
        self.assertEqual(report['counts']['unsupportedRows'], 1)
        self.assertEqual(report['productSessions'][0]['voiceOutcomes'], {'ACCEPTED': 1})
        self.assertEqual(len(report['productEvents']), 1)

    def test_voice_outcomes_do_not_cross_start_cleanup_or_clock_process_trial_boundaries(self):
        report = parse(self.start(), self.row('EMPTY', 1), self.start(2), self.row('ACCEPTED', 3))
        self.assertEqual([session['voiceOutcomes'] for session in report['productSessions']],
            [{'EMPTY': 1}, {'ACCEPTED': 1}])
        for changes in (dict(processId=Q), dict(trialId=U), dict(elapsedMs=900),
                        dict(wallMs=BASE+5000)):
            report = parse(self.start(), self.row('EMPTY', 1), dict(self.row('ACCEPTED', 2), **changes))
            self.assertEqual([session['voiceOutcomes'] for session in report['productSessions']],
                [{'EMPTY': 1}, {'ACCEPTED': 1}])
            self.assertFalse(report['productSessions'][1]['startObserved'])
        key_closed = dict(self.start(2), event='TESLA_KEY_LOCAL_CLOSED',
            state={'connectionStage': 'STOPPED'})
        report = parse(self.start(), self.row('EMPTY', 1), key_closed, self.row('CLOSED', 3))
        self.assertEqual([session['voiceOutcomes'] for session in report['productSessions']],
            [{'EMPTY': 1}, {'CLOSED': 1}])
        self.assertFalse(report['productSessions'][1]['startObserved'])

    def test_cli_json_and_human_show_sanitized_diagnostics_and_session_counts(self):
        secret = 'PRIVATE-TRANSCRIPT-PCM-VIN-ADDRESS-KEY'
        rows = [self.start(), self.row('PREPARED', 1, recognizerCleanupConfirmed=False),
            self.row('ACCEPTED', 2), self.row('ACCEPTED', 3, recognizerCleanupConfirmed=False)]
        rows[2]['productVoiceDiagnostic'].update(transcript=secret, pcm=secret, vin=secret)
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode()
        for flags in ([], ['--json']):
            out, err = io.StringIO(), io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(main(['input.jsonl', *flags]), 0)
            self.assertEqual(err.getvalue(), '')
            text = out.getvalue()
            self.assertNotIn(secret, text)
            if flags:
                report = json.loads(text)
                sessions = report['productSessions']
                diagnostics = [point['productVoiceDiagnostic'] for point in report['productEvents'][1:]]
            else:
                sessions = [json.loads(line.split('관측 세션 ', 1)[1]) for line in text.splitlines()
                            if line.startswith('연결·제품 음성 관측 세션 ')]
                diagnostics = [json.loads(line.split('진단 ', 1)[1]) for line in text.splitlines()
                               if line.startswith('제품 음성 진단 ')]
                self.assertIn('차량 명령 허가나 물리 성공의 근거가 아닙니다', text)
            self.assertEqual(sessions[0]['voiceOutcomes'], {'PREPARED': 1, 'ACCEPTED': 1})
            self.assertEqual([item['outcome'] for item in diagnostics], ['PREPARED', 'ACCEPTED', None])
            self.assertIs(diagnostics[-1]['recognizerCleanupConfirmed'], False)
            self.assertEqual(sessions[0]['commandAcceptedCount'], 0)


class ProductServiceTests(unittest.TestCase):
    @staticmethod
    def row(event='TESLA_CONNECTION_STARTED', offset=0, **state):
        return record(version=33, event=event, wallMs=BASE+offset, elapsedMs=1000+offset,
            state=dict(connectionStage='MONITORING', connectionGear='P',
                connectionParkFresh=True, productVoiceStage='LISTENING',
                productVoiceConsent=True, **state))

    def test_product_snapshot_rejects_free_text_wrong_types_and_older_schema(self):
        secret = 'PRIVATE-TRANSCRIPT-PCM-VIN-ID-VEHICLE-TIME'
        for key, choices in PRODUCT_ENUMS.items():
            for value in choices:
                self.assertEqual(product_state({key: value}, 33)[key], value)
        for key in PRODUCT_BOOLS:
            for value in (True, False):
                self.assertIs(product_state({key: value}, 33)[key], value)
        for key in PRODUCT_KEYS:
            bad = [secret, '', ' P', 'listening', 1, 1.0, [], {'raw': secret}]
            bad += ['true'] if key in PRODUCT_BOOLS else [True, False]
            for value in bad:
                with self.subTest(key=key, value=value):
                    safe = product_state({key: value, 'transcript': secret, 'pcm': secret,
                        'vin': secret, 'vehicleTimestamp': BASE}, 33)
                    self.assertIsNone(safe[key])
                    self.assertEqual(set(safe), set(PRODUCT_KEYS))
                    self.assertNotIn(secret, json.dumps(safe))
        for version in range(1, 33):
            row = dict(self.row(), version=version)
            self.assertEqual(product_state(row['state'], version), dict.fromkeys(PRODUCT_KEYS))
            report = parse(row)
            self.assertEqual(report['productEvents'], [])
            self.assertEqual(report['productSessions'], [])
        for value in (None, [], secret, True):
            self.assertEqual(product_state(value, 33), dict.fromkeys(PRODUCT_KEYS))

    def test_acceptance_and_unknown_cleanup_do_not_become_actuator_success(self):
        rows = [self.row(), self.row('PRODUCT_VOICE_COMMAND_ACCEPTED', 10)]
        rows[1]['state'].update(productVoiceStage='COMMAND_READY', frunkCommandRequested=True,
            frunkCommandSent=False, frunkCommandAcknowledged=False, frunkOpenObserved=False)
        report = parse(*rows)
        session = report['productSessions'][0]
        self.assertTrue(session['startObserved'])
        self.assertEqual(session['commandAcceptedCount'], 1)
        self.assertEqual(session['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
        self.assertIsNone(session['microphoneCleanupConfirmed'])
        self.assertFalse(session['cleanupFailureObserved'])
        cleanup = self.row('PRODUCT_VOICE_CLEANUP_FAILED', 20,
            productVoiceReason='PRODUCT_VOICE_CLEANUP_FAILED_RESTART_REQUIRED')
        cleanup['state'].update(productVoiceStage='FAILED', connectionParkFresh=False)
        stopped = self.row('PRODUCT_VOICE_STOPPED', 30)
        stopped['state'].update(productVoiceStage='STOPPED', productVoiceReason=None)
        session = parse(*rows, cleanup, stopped)['productSessions'][0]
        self.assertTrue(session['cleanupFailureObserved'])
        self.assertIsNone(session['microphoneCleanupConfirmed'])
        self.assertIsNone(session['frunkObserved']['frunkOpenObserved'])
        sent = self.row('TESLA_FRUNK_COMMAND_SENT', 40, frunkCommandSent=True)
        session = parse(*rows, cleanup, stopped, sent)['productSessions'][0]
        self.assertTrue(session['frunkObserved']['frunkCommandSent'])
        self.assertIsNone(session['frunkObserved']['frunkOpenObserved'])
        self.assertTrue(session['cleanupFailureObserved'])
        stale = self.row('PRODUCT_VOICE_COMMAND_ACCEPTED', 10,
            **dict.fromkeys(FRUNK_STATE_KEYS, True))
        session = parse(self.row(), stale)['productSessions'][0]
        self.assertEqual(session['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
        self.assertFalse(session['cleanupFailureObserved'])
        requested = self.row('TESLA_FRUNK_COMMAND_REQUESTED', 15, frunkCommandRequested=True)
        acknowledged = self.row('TESLA_FRUNK_COMMAND_ACKNOWLEDGED', 50, frunkCommandAcknowledged=True)
        opened = self.row('TESLA_FRUNK_OPEN_OBSERVED', 60, frunkOpenObserved=True)
        session = parse(self.row(), stale, requested, sent, acknowledged, opened)['productSessions'][0]
        for key in ('frunkCommandRequested', 'frunkCommandSent', 'frunkCommandAcknowledged', 'frunkOpenObserved'):
            self.assertTrue(session['frunkObserved'][key])
        session = parse(self.row(), self.row(offset=5), stale)['productSessions'][1]
        self.assertEqual(session['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))

    def test_sessions_split_at_observed_start_and_clock_process_trial_boundaries(self):
        accepted = self.row('PRODUCT_VOICE_COMMAND_ACCEPTED', 10)
        no_start = parse(accepted, self.row('PRODUCT_VOICE_STOPPED', 20))
        self.assertEqual(len(no_start['productSessions']), 2)
        self.assertTrue(all(not s['startObserved'] for s in no_start['productSessions']))
        report = parse(self.row(), accepted, self.row(offset=20),
            self.row('PRODUCT_VOICE_STOPPED', 30))
        self.assertEqual([s['commandAcceptedCount'] for s in report['productSessions']], [1, 0])
        for changed in (
            dict(accepted, processId=Q), dict(accepted, trialId=U),
            dict(accepted, elapsedMs=900), dict(accepted, wallMs=BASE+5000),
        ):
            report = parse(self.row(), changed)
            self.assertEqual(len(report['productSessions']), 2)
            self.assertEqual(report['productSessions'][0]['commandAcceptedCount'], 0)
        report = parse(dict(accepted, processId='invalid'))
        self.assertEqual(report['productEvents'], [])
        self.assertEqual(parse(dict(accepted, trialId='invalid'))['productEvents'], [])
        self.assertEqual(parse(self.row(), exclude=(T,))['productSessions'], [])
        closed = self.row('TESLA_KEY_LOCAL_CLOSED', 20)
        report = parse(self.row(), accepted, closed,
            self.row('TESLA_FRUNK_COMMAND_SENT', 30, frunkCommandSent=True))
        self.assertEqual(len(report['productSessions']), 2)
        self.assertIsNone(report['productSessions'][0]['frunkObserved']['frunkCommandSent'])
        self.assertFalse(report['productSessions'][1]['startObserved'])

    def test_key_cleanup_keeps_following_frunk_cleanup_in_started_session(self):
        # KeyProbe.cleanup의 실제 발행 순서는 KEY 정리 결과 다음 FRUNK 정리 결과다.
        for key_event in ('TESLA_KEY_LOCAL_CLOSED', 'TESLA_KEY_CLEANUP_FAILED'):
            for frunk_event, observed_key in (
                ('TESLA_FRUNK_LOCAL_CLOSED', 'frunkLocalClosed'),
                ('TESLA_FRUNK_CLEANUP_FAILED', 'frunkCleanupFailed'),
            ):
                with self.subTest(key_event=key_event, frunk_event=frunk_event):
                    rows = [self.row(), self.row('PRODUCT_VOICE_COMMAND_ACCEPTED', 10),
                        self.row('TESLA_FRUNK_COMMAND_SENT', 20, frunkCommandSent=True),
                        self.row(key_event, 30),
                        self.row(frunk_event, 40, **{observed_key: True})]
                    session, = parse(*rows)['productSessions']
                    self.assertTrue(session['startObserved'])
                    self.assertEqual((session['firstLine'], session['lastLine']), (1, 5))
                    self.assertEqual(session['eventCounts'],
                        {row['event']: 1 for row in rows})
                    self.assertEqual(session['commandAcceptedCount'], 1)
                    expected = dict.fromkeys(FRUNK_STATE_KEYS)
                    expected.update(frunkCommandSent=True, **{observed_key: True})
                    self.assertEqual(session['frunkObserved'], expected)
                    self.assertEqual(session['cleanupFailureObserved'],
                        key_event == 'TESLA_KEY_CLEANUP_FAILED' or
                        frunk_event == 'TESLA_FRUNK_CLEANUP_FAILED')
                    self.assertIsNone(session['microphoneCleanupConfirmed'])

    def test_frunk_cleanup_finishes_key_tail_before_unstarted_work(self):
        for key_event, frunk_event, observed_key in (
            ('TESLA_KEY_LOCAL_CLOSED', 'TESLA_FRUNK_LOCAL_CLOSED', 'frunkLocalClosed'),
            ('TESLA_KEY_CLEANUP_FAILED', 'TESLA_FRUNK_CLEANUP_FAILED', 'frunkCleanupFailed'),
        ):
            with self.subTest(key_event=key_event, frunk_event=frunk_event):
                first, second, third = parse(self.row(),
                    self.row('PRODUCT_VOICE_COMMAND_ACCEPTED', 10),
                    self.row(key_event, 20),
                    self.row(frunk_event, 30, **{observed_key: True}),
                    self.row('TESLA_FRUNK_COMMAND_SENT', 40, frunkCommandSent=True),
                    self.row('TESLA_FRUNK_LOCAL_CLOSED', 50, frunkLocalClosed=True)
                    )['productSessions']
                self.assertEqual((first['firstLine'], first['lastLine']), (1, 4))
                self.assertEqual(first['commandAcceptedCount'], 1)
                for session in (second, third):
                    self.assertFalse(session['startObserved'])
                    self.assertEqual(session['commandAcceptedCount'], 0)
                    self.assertFalse(session['cleanupFailureObserved'])
                    self.assertIsNone(session['microphoneCleanupConfirmed'])
                self.assertEqual(second['eventCounts'], {'TESLA_FRUNK_COMMAND_SENT': 1})
                self.assertIsNone(second['frunkObserved']['frunkLocalClosed'])
                self.assertEqual(third['eventCounts'], {'TESLA_FRUNK_LOCAL_CLOSED': 1})
                self.assertIsNone(third['frunkObserved']['frunkCommandSent'])

    def test_key_cleanup_without_frunk_event_does_not_infer_frunk_cleanup(self):
        for key_event in ('TESLA_KEY_LOCAL_CLOSED', 'TESLA_KEY_CLEANUP_FAILED'):
            with self.subTest(key_event=key_event):
                closed = self.row(key_event, 10, **dict.fromkeys(FRUNK_STATE_KEYS, True))
                session, = parse(self.row(), closed)['productSessions']
                self.assertEqual(session['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
                self.assertEqual(session['cleanupFailureObserved'],
                    key_event == 'TESLA_KEY_CLEANUP_FAILED')
                self.assertIsNone(session['microphoneCleanupConfirmed'])

    def test_key_cleanup_tail_stops_at_new_start_or_non_cleanup_event(self):
        for key_event in ('TESLA_KEY_LOCAL_CLOSED', 'TESLA_KEY_CLEANUP_FAILED'):
            for boundary_event in ('TESLA_CONNECTION_STARTED', 'TESLA_FRUNK_COMMAND_SENT',
                                   'PRODUCT_VOICE_STOPPED'):
                with self.subTest(key_event=key_event, boundary_event=boundary_event):
                    boundary = self.row(boundary_event, 30,
                        **dict.fromkeys(FRUNK_STATE_KEYS, True))
                    rows = [self.row(), self.row('PRODUCT_VOICE_COMMAND_ACCEPTED', 10),
                        self.row(key_event, 20), boundary,
                        self.row('TESLA_FRUNK_LOCAL_CLOSED', 40, frunkLocalClosed=True)]
                    sessions = parse(*rows)['productSessions']
                    started = boundary_event == 'TESLA_CONNECTION_STARTED'
                    self.assertEqual(len(sessions), 2 if started else 3)
                    first = sessions[0]
                    self.assertEqual(first['lastLine'], 3)
                    self.assertEqual(first['commandAcceptedCount'], 1)
                    self.assertEqual(first['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
                    self.assertNotIn('TESLA_FRUNK_LOCAL_CLOSED', first['eventCounts'])
                    self.assertEqual([s['startObserved'] for s in sessions],
                        [True, True] if started else [True, False, False])
                    self.assertTrue(all(s['commandAcceptedCount'] == 0 for s in sessions[1:]))
                    self.assertIs(sessions[-1]['frunkObserved']['frunkLocalClosed'], True)
                    self.assertIsNone(sessions[-1]['frunkObserved']['frunkCleanupFailed'])
                    if started:
                        self.assertEqual(sessions[-1]['eventCounts'],
                            {'TESLA_CONNECTION_STARTED': 1, 'TESLA_FRUNK_LOCAL_CLOSED': 1})
                    for session in sessions:
                        self.assertIsNone(session['microphoneCleanupConfirmed'])

    def test_key_frunk_cleanup_does_not_cross_clock_process_trial_boundaries(self):
        for key_event in ('TESLA_KEY_LOCAL_CLOSED', 'TESLA_KEY_CLEANUP_FAILED'):
            for frunk_event, observed_key in (
                ('TESLA_FRUNK_LOCAL_CLOSED', 'frunkLocalClosed'),
                ('TESLA_FRUNK_CLEANUP_FAILED', 'frunkCleanupFailed'),
            ):
                tail = self.row(frunk_event, 20, **{observed_key: True})
                for changes in (dict(processId=Q), dict(trialId=U),
                                dict(elapsedMs=900), dict(wallMs=BASE+5000)):
                    with self.subTest(key_event=key_event, frunk_event=frunk_event,
                                      changes=changes):
                        first, second = parse(self.row(), self.row(key_event, 10),
                            dict(tail, **changes))['productSessions']
                        self.assertTrue(first['startObserved'])
                        self.assertFalse(second['startObserved'])
                        self.assertEqual((first['lastLine'], second['firstLine']), (2, 3))
                        self.assertEqual(first['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
                        self.assertNotIn(frunk_event, first['eventCounts'])
                        self.assertEqual(second['eventCounts'], {frunk_event: 1})
                        expected = dict.fromkeys(FRUNK_STATE_KEYS)
                        expected[observed_key] = True
                        self.assertEqual(second['frunkObserved'], expected)
                        self.assertEqual(second['cleanupFailureObserved'],
                            frunk_event == 'TESLA_FRUNK_CLEANUP_FAILED')
                        self.assertIsNone(second['microphoneCleanupConfirmed'])

    def test_key_frunk_cleanup_without_start_keeps_independent_observations(self):
        for key_event in ('TESLA_KEY_LOCAL_CLOSED', 'TESLA_KEY_CLEANUP_FAILED'):
            for frunk_event, observed_key in (
                ('TESLA_FRUNK_LOCAL_CLOSED', 'frunkLocalClosed'),
                ('TESLA_FRUNK_CLEANUP_FAILED', 'frunkCleanupFailed'),
            ):
                with self.subTest(key_event=key_event, frunk_event=frunk_event):
                    first, second = parse(self.row(key_event),
                        self.row(frunk_event, 10, **{observed_key: True}))['productSessions']
                    self.assertFalse(first['startObserved'])
                    self.assertFalse(second['startObserved'])
                    self.assertEqual(first['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
                    expected = dict.fromkeys(FRUNK_STATE_KEYS)
                    expected[observed_key] = True
                    self.assertEqual(second['frunkObserved'], expected)
                    self.assertEqual(second['cleanupFailureObserved'],
                        frunk_event == 'TESLA_FRUNK_CLEANUP_FAILED')
                    self.assertIsNone(first['microphoneCleanupConfirmed'])
                    self.assertIsNone(second['microphoneCleanupConfirmed'])

    def test_frunk_cleanup_after_key_requires_boolean_observation(self):
        for frunk_event, observed_key in (
            ('TESLA_FRUNK_LOCAL_CLOSED', 'frunkLocalClosed'),
            ('TESLA_FRUNK_CLEANUP_FAILED', 'frunkCleanupFailed'),
        ):
            for value in (None, False, 'true', 1):
                with self.subTest(frunk_event=frunk_event, value=value):
                    session, = parse(self.row(), self.row('TESLA_KEY_LOCAL_CLOSED', 10),
                        self.row(frunk_event, 20, **{observed_key: value}))['productSessions']
                    expected = dict.fromkeys(FRUNK_STATE_KEYS)
                    expected[observed_key] = False if value is False else None
                    self.assertEqual(session['frunkObserved'], expected)
                    self.assertEqual(session['cleanupFailureObserved'],
                        frunk_event == 'TESLA_FRUNK_CLEANUP_FAILED')
                    self.assertIsNone(session['microphoneCleanupConfirmed'])

    def test_product_events_and_payloads_have_no_raw_recognition_channel(self):
        secret = 'PRIVATE-TRANSCRIPT-PCM-VIN-ID-VEHICLE-TIME'
        for event in PRODUCT_EVENTS:
            row = dict(self.row(event, productVoiceReason=secret), version=39)
            row['state'].update(transcript=secret, pcm=secret, vin=secret, vehicleTimestamp=BASE,
                frunkCommandSent='true', frunkOpenObserved=1)
            row['frunkVoiceRecognition'] = {'candidates': [{'text': secret}]}
            report = parse(row)
            self.assertEqual(len(report['productEvents']), 1)
            self.assertNotIn(secret, json.dumps(report))
            self.assertIsNone(report['productEvents'][0]['frunkState']['frunkCommandSent'])
            self.assertIsNone(report['productEvents'][0]['frunkState']['frunkOpenObserved'])
            self.assertEqual(report['frunkEvents'], [])
        self.assertEqual(parse(self.row('PRODUCT_VOICE_'+secret))['productEvents'], [])


class ConnectionWakeSchemaTests(unittest.TestCase):
    EVENTS = (
        'TESLA_CONNECTION_BODY_VERIFIED', 'TESLA_CONNECTION_WAKE_STARTED',
        'TESLA_CONNECTION_WAKE_ACKNOWLEDGED', 'TESLA_CONNECTION_WAKE_REJECTED',
        'TESLA_CONNECTION_WAKE_NOMINAL_ACK',
        'TESLA_CONNECTION_WAKE_UNCONFIRMED', 'TESLA_CONNECTION_WAKE_SKIPPED_AWAKE',
        'TESLA_CONNECTION_WAKE_SKIPPED_UNKNOWN',
    )

    def test_wake_events_and_stages_are_schema35_only_without_success_inference(self):
        for stage in ('CHECKING_VEHICLE', 'WAKING_VEHICLE'):
            for event in self.EVENTS:
                row = record(version=35, event=event, state={'connectionStage': stage})
                report = parse(row)
                self.assertEqual(report['issues'], [])
                self.assertEqual(report['productEvents'][0]['event'], event)
                self.assertEqual(report['productEvents'][0]['state']['connectionStage'], stage)
                self.assertIsNone(report['productEvents'][0]['state']['connectionParkFresh'])
                self.assertEqual(report['productSessions'][0]['frunkObserved'],
                    dict.fromkeys(FRUNK_STATE_KEYS))
                for version in range(1, 35):
                    self.assertEqual(parse(dict(row, version=version))['productEvents'], [])
                    self.assertIsNone(product_state(row['state'], version)['connectionStage'])

    def test_wake_terminal_rx_is_version_gated_and_not_physical_success(self):
        for disposition, aead in (('WAKE_ACKNOWLEDGED', 'VERIFIED'),
                                  ('WAKE_NOMINAL_ACK', 'NOT_ATTEMPTED'),
                                  ('WAKE_UNCONFIRMED', 'NOT_ATTEMPTED'),
                                  ('RESPONSE_REJECTED', 'VERIFIED')):
            row = dict(ConnectionObservationTests.row('PROTOCOL', rxProtocolCount=1,
                rxResponse={'disposition': disposition, 'step': 'WAKE_RESULT', 'aead': aead}),
                version=35)
            report = parse(row)
            classified = report['connectionOperations'][0]['classifications']
            self.assertEqual(classified['step'], {'WAKE_RESULT': 1})
            self.assertEqual(classified['authEvidence'], {'unobserved': 1})
            self.assertEqual(classified['disposition'], {disposition: 1})
            self.assertEqual(classified['aead'], {aead: 1})
            self.assertEqual(report['productSessions'][0]['frunkObserved'],
                dict.fromkeys(FRUNK_STATE_KEYS))
            old = parse(dict(row, version=34))['productEvents'][0]['connectionObservation']['rxResponse']
            self.assertIsNone(old['step'])
            self.assertEqual(old['disposition'], 'RESPONSE_REJECTED' if disposition == 'RESPONSE_REJECTED' else None)

    def test_wake_reasons_are_new_only_and_private_payload_has_no_new_channel(self):
        secret = 'PRIVATE-VIN-KEY-ROUTE-UUID-PACKET-PCM'
        for reason in ('CONNECTION_BODY_RECEIPT_STALE', 'CONNECTION_WAKE_REJECTED',
                       'CONNECTION_WAKE_UNCONFIRMED', 'WAKE_EXPIRED'):
            self.assertEqual(product_state({'connectionReason': reason}, 35)['connectionReason'], reason)
            self.assertIsNone(product_state({'connectionReason': reason}, 34)['connectionReason'])
        row = dict(ConnectionObservationTests.row('PROTOCOL', rxProtocolCount=1,
            rxResponse={'disposition': secret, 'step': secret, 'raw': secret,
                'route': secret, 'requestUuid': secret, 'hash': secret}), version=35)
        row.update(origin=secret, packet=secret)
        row['state'].update(connectionStage=secret, connectionReason=secret, vin=secret)
        report = parse(row)
        self.assertNotIn(secret, json.dumps(report))
        future = parse(dict(row, version=40))
        self.assertEqual(future['counts']['unsupportedRows'], 1)
        self.assertEqual(future['productEvents'], [])
        self.assertEqual(future['connectionOperations'], [])
        self.assertNotIn(secret, json.dumps(future))

    def test_domain2_sleep_status_is_schema36_enum_only(self):
        for raw, expected in ((0, 'UNKNOWN'), (1, 'AWAKE'), (2, 'ASLEEP'), (3, None), (-1, None),
                              ('2', None), (True, None)):
            row = dict(ConnectionObservationTests.row('PROTOCOL', rxProtocolCount=1,
                rxResponse={'disposition': 'DOMAIN_MISMATCH', 'domainMismatchReason': 'DOMAIN_DIFFERENT',
                            'domainMismatchVcsecSleepStatus': raw}), version=36)
            response = parse(row)['productEvents'][0]['connectionObservation']['rxResponse']
            self.assertEqual(response['domainMismatchVcsecSleepStatus'], expected)
            old = parse(dict(row, version=35))['productEvents'][0]['connectionObservation']['rxResponse']
            self.assertNotIn('domainMismatchVcsecSleepStatus', old)

    def test_session_retransmit_event_is_schema36_only(self):
        row = record(version=36, event='TESLA_CONNECTION_SESSION_RETRANSMIT',
                     state={'connectionStage': 'CHECKING_VEHICLE'})
        events = parse(row)['productEvents']
        self.assertEqual([point['event'] for point in events], ['TESLA_CONNECTION_SESSION_RETRANSMIT'])
        self.assertEqual(parse(dict(row, version=35))['productEvents'], [])

    def test_initial_drive_retry_cannot_backport_or_grant_park_or_voice(self):
        row = record(version=38, event='TESLA_CONNECTION_DRIVE_RETRY',
                     state={'connectionStage': 'AUTHENTICATING', 'connectionGear': 'UNKNOWN',
                            'connectionParkFresh': False, 'productVoiceStage': 'IDLE',
                            'frunkCommandSent': False})
        report = parse(row)
        self.assertEqual(report['productEvents'][0]['state']['connectionParkFresh'], False)
        self.assertEqual(report['productEvents'][0]['state']['productVoiceStage'], 'IDLE')
        self.assertEqual(report['connectionOperations'], [])
        for version in (33, 34, 35, 36, 37):
            with self.subTest(version=version):
                self.assertEqual(parse(dict(row, version=version))['productEvents'], [])


class ConnectionObservationTests(unittest.TestCase):
    @staticmethod
    def row(kind='STAGE', offset=0, **observation):
        current = dict(event=kind, stage='HANDSHAKING', operationStartedElapsedMs=1000,
            stageStartedElapsedMs=1000, observedElapsedMs=1000+offset)
        current.update(observation)
        return record(version=34, event='TESLA_CONNECTION_GATT_OBSERVATION',
            wallMs=BASE+offset, elapsedMs=1000+offset, connectionObservation=current,
            state={'connectionStage': 'AUTHENTICATING', 'productVoiceStage': 'IDLE'})

    @classmethod
    def six_notifications(cls):
        rows = [cls.row(stage='CONNECTING', rxNotificationCount=0, rxFrameCount=0, rxProtocolCount=0)]
        response = None
        frame_count = protocol_count = 0
        for count, complete in enumerate((0, 1, 1, 0, 1, 0), 1):
            offset = count * 100
            frame_count += complete
            stage = 'HANDSHAKING' if count <= 3 else 'READING_STATUS'
            stage_start = 1000 if count <= 3 else 1320
            rows.append(cls.row('RX', offset, stage=stage, stageStartedElapsedMs=stage_start,
                connectedObserved=True, subscriptionConfirmed=True, rxNotificationCount=count,
                rxFrameCount=frame_count, rxProtocolCount=protocol_count,
                rxNotificationFrameCount=complete, rxDecoderIncomplete=complete == 0,
                rxDecoderFailed=False, rxResponse=response))
            if complete:
                protocol_count += 1
                disposition = ('REQUEST_UUID_MISMATCH', 'SESSION_READY', 'RESPONSE_WAITING')[protocol_count-1]
                response = dict(disposition=disposition, step='SEND' if protocol_count == 2 else 'WAITING',
                    inputKind='ROUTABLE_MESSAGE', aead='NOT_ATTEMPTED', authEvidence=None,
                    requestUuidPresent=True, requestUuidMatches=protocol_count != 1)
                rows.append(cls.row('PROTOCOL', offset+10, stage=stage,
                    stageStartedElapsedMs=stage_start, rxNotificationCount=count,
                    rxFrameCount=frame_count, rxProtocolCount=protocol_count,
                    rxProtocolReceivedElapsedMs=1000+offset,
                    rxProtocolObservedElapsedMs=1010+offset, rxResponse=response))
            if count == 3:
                rows.append(cls.row('STAGE', offset+20, stage='READING_STATUS',
                    stageStartedElapsedMs=1320, rxNotificationCount=count,
                    rxFrameCount=frame_count, rxProtocolCount=protocol_count, rxResponse=response))
        rows += [
            cls.row('STAGE', 10000, stage='TIMED_OUT', stageStartedElapsedMs=11000,
                deadlineCause='PROTOCOL_STAGE', rxNotificationCount=6, rxFrameCount=3,
                rxProtocolCount=3, rxResponse=response),
            cls.row('LOCAL_CLOSED', 10010, stage='TIMED_OUT', stageStartedElapsedMs=11000,
                deadlineCause=None, rxNotificationCount=6, rxFrameCount=3,
                rxProtocolCount=3, rxResponse=response),
        ]
        return rows

    def test_six_notifications_do_not_become_six_protocol_results_or_auth_rejection(self):
        started = dict(ProductServiceTests.row(), version=34)
        rows = self.six_notifications()
        rows[-1]['state'].update(dict.fromkeys(FRUNK_STATE_KEYS, True))
        report = parse(started, *rows)
        operation = report['connectionOperations'][0]
        self.assertEqual(operation['counterTotals'], dict(notification=6, frame=3, protocol=3))
        self.assertEqual(operation['observedEvents'], dict(notification=6, frame=3, protocol=3))
        self.assertEqual(operation['decoder']['rxDecoderIncomplete'], {'true': 3, 'false': 3})
        self.assertEqual(operation['classifications']['disposition'],
            {'REQUEST_UUID_MISMATCH': 1, 'SESSION_READY': 1, 'RESPONSE_WAITING': 1})
        self.assertEqual(operation['classifications']['authEvidence'], {'unobserved': 3})
        self.assertEqual(operation['classifications']['aead'], {'NOT_ATTEMPTED': 3})
        self.assertEqual(operation['lastActiveStage'], 'READING_STATUS')
        self.assertIn('HANDSHAKING', [point['stage'] for point in operation['stages']])
        self.assertEqual(operation['deadlineCause'], 'PROTOCOL_STAGE')
        self.assertEqual(operation['deadlineObservation']['observedElapsedMs'], 11000)
        self.assertEqual(operation['lastObservation']['event'], 'LOCAL_CLOSED')
        self.assertIsNone(operation['lastObservation']['deadlineCause'])
        self.assertEqual(operation['protocolQueueDelayMs']['count'], 3)
        self.assertEqual(operation['protocolQueueDelayMs']['max'], 10)
        session = report['productSessions'][0]
        self.assertEqual(session['commandAcceptedCount'], 0)
        self.assertEqual(session['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
        self.assertIsNone(session['microphoneCleanupConfirmed'])

    def test_schema35_frunk_terminal_results_count_only_new_protocol_counters_not_copied_snapshots(self):
        acknowledged = dict(disposition='FRUNK_ACKNOWLEDGED', step='FRUNK_RESULT',
            inputKind='ENCRYPTED_RESPONSE', aead='VERIFIED')
        unconfirmed = dict(disposition='FRUNK_UNCONFIRMED', step='FRUNK_RESULT',
            inputKind='ROUTABLE_MESSAGE', aead='NOT_ATTEMPTED')
        events = (
            ('STAGE', 0, None),
            ('PROTOCOL', 1, acknowledged),
            ('STAGE', 1, acknowledged),
            ('RX', 1, acknowledged),
            ('PROTOCOL', 1, acknowledged),
            ('PROTOCOL', 2, unconfirmed),
            ('WRITE', 2, unconfirmed),
            ('PROTOCOL', 2, unconfirmed),
            ('PROTOCOL', 3, acknowledged),
            ('PROTOCOL', 1, unconfirmed),
            ('LOCAL_CLOSED', 3, acknowledged),
        )
        for version in (35, 34):
            with self.subTest(version=version):
                rows = [dict(self.row(kind, index * 10, stage='READING_STATUS',
                    rxNotificationCount=0, rxFrameCount=0, rxProtocolCount=count,
                    rxProtocolReceivedElapsedMs=1000+index*10,
                    rxProtocolObservedElapsedMs=1000+index*10, rxResponse=response),
                    version=version) for index, (kind, count, response) in enumerate(events)]
                report = parse(*rows)
                self.assertEqual(len(report['connectionOperations']), 1)
                operation = report['connectionOperations'][0]
                self.assertEqual(operation['counterTotals'], dict(notification=0, frame=0, protocol=3))
                # RX의 per-notification frame 값이 없으므로 snapshot의 0으로 관측을 채우지 않는다.
                self.assertEqual(operation['observedEvents'], dict(notification=0, frame=None, protocol=3))
                self.assertEqual(operation['classifications']['disposition'],
                    {'FRUNK_ACKNOWLEDGED': 2, 'FRUNK_UNCONFIRMED': 1}
                    if version == 35 else {'unobserved': 3})
                self.assertEqual(operation['classifications']['step'], {'FRUNK_RESULT': 3})
                self.assertEqual(operation['classifications']['aead'], {'VERIFIED': 2, 'NOT_ATTEMPTED': 1})
                self.assertEqual(operation['classifications']['authEvidence'], {'unobserved': 3})
                self.assertTrue(operation['counterRegressionObserved'])
                self.assertEqual(operation['protocolQueueDelayMs']['count'], 3)
                self.assertEqual([point['connectionRxObservation']['newProtocol']
                    for point in report['productEvents']],
                    [False, True, False, False, False, True, False, False, True, False, False])
                self.assertEqual(report['productSessions'][0]['frunkObserved'], dict.fromkeys(FRUNK_STATE_KEYS))
                self.assertIsNone(report['productSessions'][0]['microphoneCleanupConfirmed'])
        for version in range(1, 34):
            report = parse(dict(self.row('PROTOCOL', rxProtocolCount=1, rxResponse=acknowledged), version=version))
            self.assertEqual(report['connectionOperations'], [])

    def test_copied_snapshots_and_duplicate_or_older_protocol_counters_are_not_new_results(self):
        rows = self.six_notifications()
        repeated = dict(rxNotificationCount=6, rxFrameCount=3, rxProtocolCount=3,
            rxNotificationFrameCount=0, rxDecoderIncomplete=True,
            rxResponse={'disposition': 'SESSION_INFO_AUTH_REJECTED', 'authEvidence': 'HMAC_MISMATCH'})
        report = parse(*rows, self.row('PROTOCOL', 10020, **repeated),
            self.row('STAGE', 10030, **repeated), self.row('RX', 10040, **repeated),
            self.row('PROTOCOL', 10050, rxProtocolCount=1,
                rxResponse={'disposition': 'STATUS_VERIFIED', 'aead': 'VERIFIED'}))
        self.assertEqual(len(report['connectionOperations']), 1)
        operation = report['connectionOperations'][0]
        self.assertEqual(operation['observedEvents'], dict(notification=6, frame=3, protocol=3))
        self.assertNotIn('SESSION_INFO_AUTH_REJECTED', operation['classifications']['disposition'])
        self.assertNotIn('STATUS_VERIFIED', operation['classifications']['disposition'])
        self.assertTrue(operation['counterRegressionObserved'])

    def test_prior_snapshot_counter_blocks_same_or_older_protocol_success(self):
        for kind in ('STAGE', 'RX', 'WRITE', 'LOCAL_CLOSED'):
            for count in (1, 2):
                with self.subTest(kind=kind, count=count):
                    report = parse(
                        self.row(kind, rxProtocolCount=2),
                        self.row('PROTOCOL', 10, rxProtocolCount=count,
                            rxProtocolReceivedElapsedMs=1000, rxProtocolObservedElapsedMs=1010,
                            rxResponse={'disposition': 'STATUS_VERIFIED', 'aead': 'VERIFIED'}),
                    )
                    operation = report['connectionOperations'][0]
                    self.assertEqual(operation['counterTotals']['protocol'], 2)
                    self.assertEqual(operation['observedEvents']['protocol'], 0)
                    self.assertEqual(operation['classifications']['disposition'], {})
                    self.assertEqual(operation['classifications']['aead'], {})
                    self.assertEqual(operation['protocolQueueDelayMs']['count'], 0)
                    self.assertFalse(report['productEvents'][-1]['connectionRxObservation']['newProtocol'])

    def test_snapshot_counter_gaps_only_classify_strictly_new_protocol_results(self):
        report = parse(
            self.row('STAGE', rxProtocolCount=2),
            self.row('PROTOCOL', 10, rxProtocolCount=4, rxResponse=None),
            self.row('RX', 20, rxProtocolCount=5),
            self.row('PROTOCOL', 30, rxProtocolCount=5,
                rxResponse={'disposition': 'STATUS_VERIFIED', 'aead': 'VERIFIED'}),
            self.row('PROTOCOL', 40, rxProtocolCount=9,
                rxResponse={'disposition': 'REQUEST_UUID_MISMATCH'}),
            self.row('PROTOCOL', 50, rxProtocolCount=4,
                rxResponse={'disposition': 'SESSION_READY'}),
        )
        operation = report['connectionOperations'][0]
        self.assertEqual(operation['counterTotals']['protocol'], 9)
        self.assertEqual(operation['observedEvents']['protocol'], 2)
        self.assertEqual(operation['classifications']['disposition'],
            {'unobserved': 1, 'REQUEST_UUID_MISMATCH': 1})
        self.assertEqual(operation['classifications']['aead'], {'unobserved': 2})
        self.assertEqual([point['connectionRxObservation']['newProtocol']
                          for point in report['productEvents']], [False, True, False, False, True, False])
        self.assertTrue(operation['counterRegressionObserved'])

    def test_counter_gaps_and_missing_results_do_not_fabricate_protocol_classifications(self):
        rows = [self.row('STAGE', rxProtocolCount=5,
            rxResponse={'disposition': 'STATUS_VERIFIED', 'aead': 'VERIFIED'}),
            self.row('PROTOCOL', 10, rxProtocolCount=6, rxResponse=None),
            self.row('PROTOCOL', 20, rxProtocolCount=None,
                rxResponse={'disposition': 'SESSION_READY'}),
            self.row('PROTOCOL', 30, rxProtocolCount=7,
                rxResponse={'disposition': 'SESSION_INFO_AUTH_REJECTED', 'authEvidence': 'HMAC_MISMATCH'})]
        operation = parse(*rows)['connectionOperations'][0]
        self.assertEqual(operation['counterTotals']['protocol'], 7)
        self.assertEqual(operation['observedEvents']['protocol'], 2)
        self.assertIsNone(operation['counterTotals']['notification'])
        self.assertIsNone(operation['observedEvents']['frame'])
        self.assertEqual(operation['classifications']['disposition'],
            {'unobserved': 1, 'SESSION_INFO_AUTH_REJECTED': 1})
        self.assertEqual(operation['classifications']['authEvidence'],
            {'unobserved': 1, 'HMAC_MISMATCH': 1})

    def test_operations_never_merge_across_trial_process_clock_or_unknown_start(self):
        first = self.row('PROTOCOL', rxProtocolCount=1, rxResponse={'disposition': 'SESSION_READY'})
        second = self.row('PROTOCOL', 10, rxProtocolCount=1,
            rxResponse={'disposition': 'REQUEST_UUID_MISMATCH'})
        changed_operation = dict(second, connectionObservation=dict(second['connectionObservation'],
            operationStartedElapsedMs=1005, stageStartedElapsedMs=1005))
        for changed in (dict(second, processId=Q), dict(second, trialId=U),
                        dict(second, wallMs=BASE+5010), changed_operation):
            with self.subTest(changed=changed):
                operations = parse(first, changed)['connectionOperations']
                self.assertEqual(len(operations), 2)
                self.assertEqual([op['observedEvents']['protocol'] for op in operations], [1, 1])
                self.assertEqual(operations[0]['classifications']['disposition'], {'SESSION_READY': 1})
                self.assertEqual(operations[1]['classifications']['disposition'], {'REQUEST_UUID_MISMATCH': 1})
        earlier = self.row('PROTOCOL', -10, operationStartedElapsedMs=900,
            stageStartedElapsedMs=900, rxProtocolCount=1)
        self.assertEqual(len(parse(first, earlier)['connectionOperations']), 2)
        unknown = [self.row('PROTOCOL', offset, operationStartedElapsedMs=None, rxProtocolCount=1)
                   for offset in (0, 10)]
        operations = parse(*unknown)['connectionOperations']
        self.assertEqual(len(operations), 2)
        self.assertTrue(all(not op['scopeComplete'] for op in operations))
        self.assertEqual(parse(first, exclude=(T,))['connectionOperations'], [])
        self.assertEqual(parse(dict(first, processId='invalid'))['connectionOperations'], [])
        self.assertEqual(parse(dict(first, trialId='invalid'))['connectionOperations'], [])

    def test_legacy_and_non_gatt_snapshots_leave_connection_evidence_unobserved(self):
        for version in range(1, 34):
            report = parse(dict(self.row('PROTOCOL', rxProtocolCount=1,
                rxResponse={'disposition': 'STATUS_VERIFIED'}), version=version))
            self.assertEqual(report['connectionOperations'], [])
            self.assertTrue(all(point['connectionObservation'] is None for point in report['productEvents']))
        for event in ('PRODUCT_VOICE_STATE_CHANGED', 'TESLA_CONNECTION_STATE_CHANGED'):
            report = parse(dict(self.row('PROTOCOL', rxProtocolCount=1,
                rxResponse={'disposition': 'STATUS_VERIFIED'}), event=event))
            self.assertEqual(report['connectionOperations'], [])
            self.assertIsNone(report['productEvents'][0]['connectionObservation'])
        row = dict(ProductServiceTests.row('TESLA_CONNECTION_GATT_OBSERVATION'), version=34)
        report = parse(row)
        self.assertEqual(report['connectionOperations'], [])
        self.assertIsNone(report['productEvents'][0]['connectionObservation'])

    def test_unknown_private_and_wrong_type_evidence_cannot_become_auth_or_success(self):
        secret = 'PRIVATE-VIN-KEY-UUID-ADDRESS-PCM'
        row = self.row('PROTOCOL', rxProtocolCount=1, stage=secret, deadlineCause=secret,
            connectAccepted=1, subscriptionConfirmed='true', rxNotificationCount=True,
            rxFrameCount=-1, rxNotificationFrameCount=17, rxDecoderFailed='false',
            connectionStatus=2**31, connectAction=secret, address=secret,
            rxResponse={'disposition': secret, 'step': secret, 'aead': secret,
                'authEvidence': secret, 'inputKind': secret, 'fromPresent': False,
                'fromKind': 'DOMAIN', 'fromDomain': 2, 'requestUuidPresent': False,
                'requestUuidMatches': True, 'toPresent': False, 'toRouteMatches': True,
                'signedStatusPresent': False, 'signedMessageFault': 0, 'operationStatus': 0,
                'bodySummary': secret, 'requestCorrelation': secret, 'requestUuid': secret},
            write={'writeSequence': 0, 'writeBusyRetryCount': 3, 'writeType': True,
                'writeException': secret, 'writeDispatchQueued': 1, 'raw': secret})
        report = parse(row)
        self.assertNotIn(secret, json.dumps(report))
        operation = report['connectionOperations'][0]
        self.assertIsNone(operation['deadlineCause'])
        self.assertIsNone(operation['lastActiveStage'])
        self.assertEqual(operation['classifications']['disposition'], {'unobserved': 1})
        self.assertEqual(operation['classifications']['authEvidence'], {'unobserved': 1})
        safe = report['productEvents'][0]['connectionObservation']
        for key in ('stage', 'connectAccepted', 'subscriptionConfirmed', 'rxNotificationCount',
                    'rxFrameCount', 'rxNotificationFrameCount', 'rxDecoderFailed', 'connectionStatus'):
            self.assertIsNone(safe[key])
        for key in ('fromDomain', 'requestUuidMatches', 'toRouteMatches',
                    'signedMessageFault', 'operationStatus'):
            self.assertIsNone(safe['rxResponse'][key])
        self.assertTrue(all(value is None for value in safe['write'].values()))
        for payload in (None, secret, [], True):
            report = parse(dict(row, connectionObservation=payload))
            self.assertEqual(report['connectionOperations'], [])
            self.assertNotIn(secret, json.dumps(report))

    def test_invalid_timing_does_not_create_negative_latency_or_cross_operation_write(self):
        row = self.row('WRITE', 100, observedElapsedMs=1090, stageStartedElapsedMs=1100,
            connectIssuedElapsedMs=1020, connectReturnedElapsedMs=1010,
            firstRxElapsedMs=1040, lastRxElapsedMs=1030,
            rxProtocolReceivedElapsedMs=1080, rxProtocolObservedElapsedMs=1070,
            connectionCallbackElapsedMs=999, descriptorCallbackElapsedMs=1101,
            advertisementAgeAtConnectMs=-1,
            write={'writeSequence': 1, 'writeRequestedElapsedMs': 1050,
                'writeReturnedElapsedMs': 1040, 'writeCallbackElapsedMs': 1049,
                'writeDispatchElapsedMs': 999, 'writeRequestStatus': 0,
                'writeCallbackStatus': None, 'writeDispatchQueued': False})
        report = parse(row)
        safe = report['productEvents'][0]['connectionObservation']
        for key in ('stageStartedElapsedMs', 'connectReturnedElapsedMs', 'lastRxElapsedMs',
                    'rxProtocolObservedElapsedMs', 'connectionCallbackElapsedMs',
                    'descriptorCallbackElapsedMs', 'advertisementAgeAtConnectMs'):
            self.assertIsNone(safe[key])
        for key in ('writeReturnedElapsedMs', 'writeCallbackElapsedMs', 'writeDispatchElapsedMs'):
            self.assertIsNone(safe['write'][key])
        timing = report['productEvents'][0]['gattTimingMs']
        self.assertEqual(timing['operationElapsedMs'], 90)
        self.assertIsNone(timing['stageElapsedMs'])
        self.assertIsNone(timing['connectCallElapsedMs'])
        self.assertIsNone(timing['protocolQueueDelayMs'])
        self.assertIsNone(safe['write']['writeCallbackStatus'])
        self.assertEqual(safe['write']['writeRequestStatus'], 0)
        future = self.row('WRITE', observedElapsedMs=1001,
            write={'writeRequestedElapsedMs': 1000})
        safe = parse(future)['productEvents'][0]['connectionObservation']
        self.assertIsNone(safe['observedElapsedMs'])
        self.assertIsNone(safe['write']['writeRequestedElapsedMs'])

    def test_stage_cccd_and_write_success_do_not_supply_missing_protocol_authentication(self):
        rows = [
            self.row('WRITE', stage='READING_STATUS', subscriptionConfirmed=True,
                write={'writeSequence': 1, 'writeDispatchQueued': True}),
            self.row('WRITE', 10, stage='READING_STATUS', subscriptionConfirmed=True,
                write={'writeSequence': 1, 'writeDispatchQueued': False,
                    'writeRequestedElapsedMs': 1005, 'writeReturnedElapsedMs': 1010,
                    'writeRequestStatus': 0}),
            self.row('WRITE', 20, stage='READING_STATUS', subscriptionConfirmed=True,
                write={'writeSequence': 1, 'writeDispatchQueued': False,
                    'writeRequestedElapsedMs': 1005, 'writeReturnedElapsedMs': 1010,
                    'writeRequestStatus': 0, 'writeCallbackStatus': 0, 'writeCallbackElapsedMs': 1020}),
            self.row('STAGE', 30, stage='TIMED_OUT',
                rxResponse={'disposition': 'SESSION_READY', 'aead': 'VERIFIED'}),
        ]
        rows[-1]['state']['connectionReason'] = 'DEADLINE_EXPIRED'
        report = parse(*rows)
        operation = report['connectionOperations'][0]
        self.assertEqual(operation['classifications']['disposition'], {})
        self.assertEqual(operation['classifications']['aead'], {})
        self.assertIsNone(operation['counterTotals']['protocol'])
        self.assertIsNone(operation['deadlineCause'])
        self.assertIsNone(report['productEvents'][0]['connectionObservation']['write']['writeRequestStatus'])
        self.assertIsNone(report['productEvents'][1]['connectionObservation']['write']['writeCallbackStatus'])
        self.assertEqual(report['productEvents'][1]['gattTimingMs']['writeCallElapsedMs'], 5)
        self.assertEqual(report['productEvents'][2]['gattTimingMs']['writeCallbackSinceRequestMs'], 15)
        ready = self.row('PROTOCOL', 40, rxProtocolCount=1, rxResponse={'disposition': 'SESSION_READY'})
        report = parse(*rows, ready, dict(ready, elapsedMs=1050, wallMs=BASE+50))
        self.assertEqual(report['connectionOperations'][0]['classifications']['disposition'], {'SESSION_READY': 1})
        self.assertTrue(report['productEvents'][-2]['connectionRxObservation']['newProtocol'])
        self.assertFalse(report['productEvents'][-1]['connectionRxObservation']['newProtocol'])

    def test_decoder_failure_does_not_turn_missing_frame_count_into_zero_or_success(self):
        operation = parse(self.row('RX', rxNotificationCount=6,
            rxDecoderFailed=True, rxNotificationFrameCount=None))['connectionOperations'][0]
        self.assertEqual(operation['counterTotals'], dict(notification=6, frame=None, protocol=None))
        self.assertEqual(operation['observedEvents'], dict(notification=1, frame=None, protocol=0))
        self.assertEqual(operation['notificationFrameCountUnobserved'], 1)
        self.assertEqual(operation['decoder']['rxDecoderFailed'], {'true': 1})
        self.assertEqual(operation['decoder']['rxDecoderIncomplete'], {'unobserved': 1})
        self.assertEqual(operation['classifications']['disposition'], {})

    def test_invalid_operation_times_and_numeric_types_cannot_create_a_shared_scope(self):
        for value in (True, False, 1.5, 'PRIVATE-NUMERIC', [], {}, -1, 2**63):
            with self.subTest(value=value):
                row = self.row('PROTOCOL', operationStartedElapsedMs=value,
                    rxProtocolCount=value, rxFrameCount=value, rxNotificationCount=value,
                    write={'writeRequestedElapsedMs': 1000})
                report = parse(row, dict(row))
                self.assertEqual(len(report['connectionOperations']), 2)
                for operation in report['connectionOperations']:
                    self.assertFalse(operation['scopeComplete'])
                    self.assertEqual(operation['counterTotals'], dict(notification=None, frame=None, protocol=None))
                    self.assertEqual(operation['classifications']['disposition'], {})
                safe = report['productEvents'][0]['connectionObservation']
                self.assertIsNone(safe['observedElapsedMs'])
                self.assertIsNone(safe['stageStartedElapsedMs'])
                self.assertIsNone(safe['write']['writeRequestedElapsedMs'])
                self.assertNotIn('PRIVATE-NUMERIC', json.dumps(report))

    def test_cli_text_and_json_expose_evidence_without_promoting_legacy_absence(self):
        secret = 'PRIVATE-CONNECTION-INPUT'
        rows = self.six_notifications()
        rows[-1]['connectionObservation']['raw'] = secret
        for flags in ([], ['--json']):
            out, err = io.StringIO(), io.StringIO()
            stream = io.BytesIO(''.join(json.dumps(row)+'\n' for row in rows).encode())
            with patch('builtins.open', return_value=stream), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(main(['input.jsonl', *flags]), 0)
            self.assertEqual(err.getvalue(), '')
            self.assertNotIn(secret, out.getvalue())
            if flags:
                operations = json.loads(out.getvalue())['connectionOperations']
            else:
                objects = [json.loads(line[line.index('{"'):]) for line in out.getvalue().splitlines()
                           if '{"' in line]
                operations = [value for value in objects if 'counterTotals' in value and 'lastActiveStage' in value]
            self.assertEqual(len(operations), 1)
            self.assertEqual(operations[0]['counterTotals']['notification'], 6)
            self.assertEqual(operations[0]['lastActiveStage'], 'READING_STATUS')
            self.assertEqual(operations[0]['deadlineCause'], 'PROTOCOL_STAGE')
            legacy = dict(rows[-1], version=33)
            stream = io.BytesIO((json.dumps(legacy)+'\n').encode())
            out = io.StringIO()
            with patch('builtins.open', return_value=stream), contextlib.redirect_stdout(out):
                self.assertEqual(main(['input.jsonl', *flags]), 0)
            if flags:
                result = json.loads(out.getvalue())
                self.assertEqual(result['connectionOperations'], [])
                self.assertIsNone(result['productEvents'][0]['connectionObservation'])
            else:
                objects = [json.loads(line[line.index('{"'):]) for line in out.getvalue().splitlines()
                           if '{"' in line]
                self.assertFalse(any('counterTotals' in value for value in objects))
            self.assertNotIn(secret, out.getvalue())


class ConnectionDriveSchemaTests(unittest.TestCase):
    @staticmethod
    def response(**changes):
        response = dict(expectedDomain=3, fromPresent=True, fromKind='DOMAIN', fromDomain=3,
            toPresent=True, toKind='ROUTING_ADDRESS', requestUuidPresent=True,
            requestUuidMatches=True, toRouteMatches=True, inputKind='ENCRYPTED_RESPONSE',
            aead='VERIFIED', disposition='STATUS_VERIFIED', step='COMPLETE',
            driveShiftState='P', driveTimestampStatus='VALID')
        response.update(changes)
        return response

    @staticmethod
    def row(kind='PROTOCOL', offset=0, **observation):
        row = dict(ConnectionObservationTests.row(kind, offset, **observation), version=37)
        row['state'].update(connectionGear='UNKNOWN', connectionParkFresh=False)
        return row

    def test_verified_drive_classifications_do_not_infer_gear_or_freshness(self):
        for shift in ('ABSENT', 'EMPTY', 'INVALID', 'P', 'R', 'N', 'D', 'SNA', 'UNRECOGNIZED'):
            for timestamp in ('ABSENT', 'INVALID', 'NON_POSITIVE', 'VALID'):
                with self.subTest(shift=shift, timestamp=timestamp):
                    report = parse(self.row(rxProtocolCount=1, rxResponse=self.response(
                        driveShiftState=shift, driveTimestampStatus=timestamp)))
                    point = report['productEvents'][0]
                    self.assertEqual(point['state']['connectionGear'], 'UNKNOWN')
                    self.assertFalse(point['state']['connectionParkFresh'])
                    classified = report['connectionOperations'][0]['classifications']
                    self.assertEqual(classified['driveShiftState'], {shift: 1})
                    self.assertEqual(classified['driveTimestampStatus'], {timestamp: 1})
                    self.assertEqual(report['productSessions'][0]['frunkObserved'],
                        dict.fromkeys(FRUNK_STATE_KEYS))

    def test_every_authentication_and_correlation_requirement_is_mandatory(self):
        wrong_values = {
            'expectedDomain': (2, 3.0, True, '3', [], {}),
            'fromPresent': (False, 1, 'true', [], {}),
            'fromKind': ('ROUTING_ADDRESS', 'UNSET', True, [], {}),
            'fromDomain': (2, 3.0, True, '3', [], {}),
            'toPresent': (False, 1, 'true', [], {}),
            'toKind': ('DOMAIN', 'UNSET', True, [], {}),
            'requestUuidPresent': (False, 1, 'true', [], {}),
            'requestUuidMatches': (False, 1, 'true', [], {}),
            'toRouteMatches': (False, 1, 'true', [], {}),
            'inputKind': ('ROUTABLE_MESSAGE', 'WRAPPED_PLAINTEXT', True, [], {}),
            'aead': ('FAILED', 'NOT_ATTEMPTED', True, [], {}),
            'disposition': ('SESSION_READY', 'STATUS_INCOMPLETE', 'RESPONSE_REJECTED', True, [], {}),
            'step': ('WAITING', 'SEND', 'FAILED', True, [], {}),
        }
        for key, values in wrong_values.items():
            for value in (*values, None):
                with self.subTest(key=key, value=value):
                    response = self.response(**{key: value})
                    if value is None:
                        del response[key]
                    report = parse(self.row(rxProtocolCount=1, rxResponse=response))
                    safe = report['productEvents'][0]['connectionObservation']['rxResponse']
                    classified = report['connectionOperations'][0]['classifications']
                    for diagnostic in ('driveShiftState', 'driveTimestampStatus'):
                        self.assertIsNone(safe[diagnostic])
                        self.assertEqual(classified[diagnostic], {'unobserved': 1})

    def test_invalid_private_and_wrong_type_diagnostics_are_independently_unobserved(self):
        secret = 'PRIVATE-VIN-KEY-ROUTE-UUID-PACKET-TIMESTAMP'
        for key, other, expected in (
                ('driveShiftState', 'driveTimestampStatus', 'VALID'),
                ('driveTimestampStatus', 'driveShiftState', 'P')):
            for value in (secret, 'UNKNOWN', '', 'p', True, False, 3, 1.5, [], {}, None):
                with self.subTest(key=key, value=value):
                    response = self.response(**{key: value}, driveTimestamp=secret,
                        seconds=secret, nanos=secret, packet=secret, vin=secret,
                        route=secret, requestUuid=secret)
                    report = parse(self.row(rxProtocolCount=1, rxResponse=response))
                    self.assertNotIn(secret, json.dumps(report))
                    safe = report['productEvents'][0]['connectionObservation']['rxResponse']
                    classified = report['connectionOperations'][0]['classifications']
                    self.assertIsNone(safe[key])
                    self.assertEqual(classified[key], {'unobserved': 1})
                    self.assertEqual(classified[other], {expected: 1})

    def test_schema36_and_older_cannot_inject_drive_diagnostics(self):
        for version in range(1, 37):
            with self.subTest(version=version):
                report = parse(dict(self.row(rxProtocolCount=1,
                    rxResponse=self.response()), version=version))
                if version < 34:
                    self.assertEqual(report['connectionOperations'], [])
                    self.assertTrue(all(point['connectionObservation'] is None
                        for point in report['productEvents']))
                    continue
                safe = report['productEvents'][0]['connectionObservation']['rxResponse']
                classified = report['connectionOperations'][0]['classifications']
                for key in ('driveShiftState', 'driveTimestampStatus'):
                    self.assertNotIn(key, safe)
                    self.assertEqual(classified[key], {'unobserved': 1})

    def test_copied_and_regressed_snapshots_never_recount_drive_results(self):
        response = self.response(driveShiftState='EMPTY', driveTimestampStatus='ABSENT')
        report = parse(
            self.row(rxProtocolCount=1, rxResponse=response),
            self.row(offset=1, rxProtocolCount=1, rxResponse=response),
            self.row('RX', 2, rxProtocolCount=1, rxResponse=response),
            self.row('STAGE', 3, rxProtocolCount=1, rxResponse=response),
            self.row('LOCAL_CLOSED', 4, rxProtocolCount=1, rxResponse=response),
            self.row(offset=5, rxProtocolCount=0, rxResponse=self.response()),
            self.row(offset=6, rxProtocolCount=2, rxResponse=self.response(aead='FAILED')),
            self.row(offset=7, rxProtocolCount=3, rxResponse=None),
            self.row(offset=8, rxProtocolCount=4, rxResponse={}),
        )
        operation = report['connectionOperations'][0]
        self.assertEqual(operation['observedEvents']['protocol'], 4)
        self.assertEqual(operation['counterTotals']['protocol'], 4)
        self.assertTrue(operation['counterRegressionObserved'])
        self.assertEqual(operation['classifications']['driveShiftState'],
            {'EMPTY': 1, 'unobserved': 3})
        self.assertEqual(operation['classifications']['driveTimestampStatus'],
            {'ABSENT': 1, 'unobserved': 3})
        self.assertEqual([point['connectionRxObservation']['newProtocol']
            for point in report['productEvents']], [True, False, False, False, False,
                                                   False, True, True, True])

    def test_snapshot_counter_high_watermark_blocks_past_drive_classifications(self):
        report = parse(
            self.row('STAGE', rxProtocolCount=5, rxResponse=self.response()),
            self.row(offset=1, rxProtocolCount=5, rxResponse=self.response()),
            self.row(offset=2, rxProtocolCount=4, rxResponse=self.response()),
            self.row(offset=3, rxProtocolCount=6, rxResponse=self.response(
                driveShiftState='SNA', driveTimestampStatus='NON_POSITIVE')),
        )
        operation = report['connectionOperations'][0]
        self.assertEqual(operation['observedEvents']['protocol'], 1)
        self.assertEqual(operation['classifications']['driveShiftState'], {'SNA': 1})
        self.assertEqual(operation['classifications']['driveTimestampStatus'], {'NON_POSITIVE': 1})

    def test_drive_diagnostics_do_not_cross_operation_or_scope_boundaries(self):
        first = self.row(rxProtocolCount=1, rxResponse=self.response())
        second = self.row(offset=10, rxProtocolCount=1, rxResponse=self.response(
            driveShiftState='UNRECOGNIZED', driveTimestampStatus='INVALID'))
        new_operation = dict(second, connectionObservation=dict(second['connectionObservation'],
            operationStartedElapsedMs=1005, stageStartedElapsedMs=1005))
        for changed in (new_operation, dict(second, processId=Q), dict(second, trialId=U),
                        dict(second, wallMs=BASE+5010)):
            with self.subTest(changed=changed):
                operations = parse(first, changed)['connectionOperations']
                self.assertEqual(len(operations), 2)
                self.assertEqual([op['observedEvents']['protocol'] for op in operations], [1, 1])
                self.assertEqual([op['classifications']['driveShiftState'] for op in operations],
                    [{'P': 1}, {'UNRECOGNIZED': 1}])
                self.assertEqual([op['classifications']['driveTimestampStatus'] for op in operations],
                    [{'VALID': 1}, {'INVALID': 1}])
        unknown = [self.row(offset=offset, operationStartedElapsedMs=None,
            rxProtocolCount=1, rxResponse=self.response()) for offset in (0, 10)]
        operations = parse(*unknown)['connectionOperations']
        self.assertEqual(len(operations), 2)
        self.assertTrue(all(not op['scopeComplete'] for op in operations))
        self.assertTrue(all(op['classifications']['driveShiftState'] == {'P': 1}
            for op in operations))
        missing = self.row(offset=10, operationStartedElapsedMs=1005,
            rxProtocolCount=1, rxResponse={})
        classified = parse(first, missing)['connectionOperations'][1]['classifications']
        self.assertEqual(classified['driveShiftState'], {'unobserved': 1})
        self.assertEqual(classified['driveTimestampStatus'], {'unobserved': 1})

    def test_cli_json_and_human_report_expose_only_sanitized_drive_diagnostics(self):
        secret = 'PRIVATE-DRIVE-INPUT-PACKET-TIMESTAMP'
        rows = [
            self.row(rxProtocolCount=1, rxResponse=self.response(
                driveShiftState='EMPTY', driveTimestampStatus='ABSENT', packet=secret)),
            self.row(offset=10, rxProtocolCount=2, rxResponse=self.response(
                driveShiftState=secret, driveTimestampStatus=secret, driveTimestamp=secret)),
        ]
        for flags in ([], ['--json']):
            with self.subTest(flags=flags):
                out, err = io.StringIO(), io.StringIO()
                stream = io.BytesIO(''.join(json.dumps(row)+'\n' for row in rows).encode())
                with patch('builtins.open', return_value=stream), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    self.assertEqual(main(['input.jsonl', *flags]), 0)
                self.assertEqual(err.getvalue(), '')
                self.assertNotIn(secret, out.getvalue())
                if flags:
                    operations = json.loads(out.getvalue())['connectionOperations']
                else:
                    objects = [json.loads(line[line.index('{"'):]) for line in out.getvalue().splitlines()
                               if '{"' in line]
                    operations = [value for value in objects
                                  if 'counterTotals' in value and 'lastActiveStage' in value]
                self.assertEqual(len(operations), 1)
                classified = operations[0]['classifications']
                self.assertEqual(classified['driveShiftState'], {'EMPTY': 1, 'unobserved': 1})
                self.assertEqual(classified['driveTimestampStatus'], {'ABSENT': 1, 'unobserved': 1})
                for key in ('driveShiftState', 'driveTimestampStatus'):
                    self.assertIsNone(operations[0]['lastObservation']['rxResponse'][key])

    def test_schema37_preserves_existing_approach_recovery_boundaries(self):
        for version in (36, 37, 38, 39):
            for second_start, allowed in ((3699, False), (3700, True), (39700, True), (39701, False)):
                with self.subTest(version=version, second_start=second_start):
                    report = parse(*(dict(row, version=version)
                        for row in recovery_rows(second_start=second_start)))
                    trial = report['trials'][0]
                    self.assertEqual(trial['approachRecoveryReadCount'], int(allowed))
                    self.assertEqual(trial['approachCompleteReadCount'], int(allowed))
                    self.assertEqual('REPEATED_APPROACH_READ' in
                        [issue['code'] for issue in report['issues']], not allowed)


class NativeEvidenceTests(unittest.TestCase):
    def test_signed_and_uint32_ids_share_only_the_same_contract_tag(self):
        self.assertEqual(uwb_session_tag(-123456789), uwb_session_tag(2**32-123456789))
        self.assertEqual(uwb_session_tag(-123456789),
            hashlib.sha256(b'hey-tesla/uwb-session/v1:\xf8\xa4\x32\xeb').hexdigest())
        self.assertNotEqual(uwb_session_tag(-123456789), uwb_session_tag(-123456788))
        self.assertRegex(uwb_session_tag(0), r'^[0-9a-f]{64}$')
        for value in (True, False, 1.0, '123', None, -(2**31)-1, 2**32, [], {}):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, '^INVALID_UWB_SESSION_ID$'):
                uwb_session_tag(value)

    def test_schema_history_and_null_do_not_infer_native_observations(self):
        raw = native_uwb()['state']
        for version in range(1, 31):
            self.assertEqual(uwb_native_state(raw, 1000, version), dict.fromkeys(UWB_NATIVE_KEYS))
            row = dict(native_uwb(), version=version)
            report = parse(row)
            self.assertEqual(report['uwbNativeSessions'], [])
        report = parse(dict(uwb('TESLA_UWB_COMPLETE'), version=31))
        self.assertEqual(report['uwbNativeSessions'], [])
        self.assertIsNone(report['uwbEvents'][0]['nativeObservation'])
        self.assertTrue(all(report['uwbEvents'][0]['state'][key] is None for key in UWB_NATIVE_KEYS))
        self.assertNotIn('uwbSystemMatches', report)

    def test_native_counts_require_matching_events_and_exclude_repeated_snapshots(self):
        rows = [native_uwb('STARTING'), native_uwb('OPENED', 1),
                native_uwb('PEER_STARTED', 2), native_uwb('PEER_STOPPED', 3)]
        rows += [dict(native_uwb('PEER_STOPPED', 4), event='TESLA_UWB_COMPLETE'),
                 native_uwb('PEER_STOPPED', 5), native_uwb('CLOSED', 6, uwbNativeCloseRequested=True)]
        rows += [dict(native_uwb('CLOSED', 7, uwbNativeCloseRequested=True), event='TESLA_UWB_LOCAL_CLOSED')]
        report = parse(*rows)
        session = report['uwbNativeSessions'][0]
        self.assertEqual(session['eventCounts'],
            dict(STARTING=1, OPENED=1, PEER_STARTED=1, PEER_STOPPED=1, CLOSED=1))
        self.assertEqual(len(session['events']), 5)
        for index in (4, 5, 7):
            self.assertIsNone(report['uwbEvents'][index]['nativeObservation'])
        self.assertEqual(session['events'][3]['uwbNativePeerIndex'], 4)
        self.assertFalse(session['events'][3]['uwbNativeCloseRequested'])
        self.assertTrue(session['events'][4]['uwbNativeCloseRequested'])
        other = parse(native_uwb(), native_uwb(offset=1, session_id=42))
        self.assertEqual(len(other['uwbNativeSessions']), 2)
    def test_native_reused_tag_is_independent_between_gatt_operations(self):
        rows = []
        for operation in (100, 200):
            for event, since in (('STARTING', 0), ('OPENED', 1), ('CLOSED', 2)):
                rows.append(native_uwb(event, len(rows),
                    uwbGattOperationStartedElapsedMs=operation,
                    uwbNativeElapsedSinceStartMs=since))
        report = parse(*rows)
        sessions = report['uwbNativeSessions']
        self.assertEqual(len(sessions), 2)
        self.assertEqual([session['operationStartedElapsedMs'] for session in sessions], [100, 200])
        for session in sessions:
            self.assertEqual(session['eventCounts'], {'STARTING': 1, 'OPENED': 1, 'CLOSED': 1})
            self.assertTrue(session['startingObserved'])
            self.assertTrue(session['closedObserved'])
        self.assertTrue(all(point['nativeObservation'] is not None for point in report['uwbEvents']))

    def test_native_closed_then_starting_reopens_seen_scope_without_counting_snapshots(self):
        def observed(event, offset, since):
            return native_uwb(event, offset, uwbGattOperationStartedElapsedMs=100,
                              uwbNativeElapsedSinceStartMs=since)
        rows = [
            observed('STARTING', 0, 0), observed('STARTING', 1, 0),
            observed('OPENED', 2, 1), observed('CLOSED', 3, 2),
            dict(observed('CLOSED', 4, 2), event='TESLA_UWB_COMPLETE'),
            observed('CLOSED', 5, 2), observed('STARTING', 6, 0),
            dict(observed('STARTING', 7, 0), event='TESLA_UWB_CLEANING_UP'),
            observed('STARTING', 8, 0), observed('OPENED', 9, 1),
            observed('CLOSED', 10, 2),
            dict(observed('CLOSED', 11, 2), event='TESLA_UWB_LOCAL_CLOSED'),
        ]
        report = parse(*rows)
        sessions = report['uwbNativeSessions']
        self.assertEqual(len(sessions), 2)
        self.assertEqual([session['lifecycleIndex'] for session in sessions], [0, 1])
        for session in sessions:
            self.assertEqual(session['operationStartedElapsedMs'], 100)
            self.assertEqual(session['eventCounts'], {'STARTING': 1, 'OPENED': 1, 'CLOSED': 1})
            self.assertTrue(session['startingObserved'])
            self.assertTrue(session['closedObserved'])
        for index in (1, 4, 5, 7, 8, 11):
            self.assertIsNone(report['uwbEvents'][index]['nativeObservation'])
        self.assertIsNotNone(report['uwbEvents'][6]['nativeObservation'])

    def test_native_explicit_restart_does_not_invent_missing_closed_callback(self):
        rows = [
            native_uwb('STARTING', uwbNativeElapsedSinceStartMs=0),
            native_uwb('OPENED', 1, uwbNativeElapsedSinceStartMs=1),
            native_uwb('STARTING', 2, uwbNativeElapsedSinceStartMs=0),
            native_uwb('OPENED', 3, uwbNativeElapsedSinceStartMs=1),
        ]
        sessions = parse(*rows)['uwbNativeSessions']
        self.assertEqual(len(sessions), 2)
        for session in sessions:
            self.assertIsNone(session['operationStartedElapsedMs'])
            self.assertTrue(session['startingObserved'])
            self.assertFalse(session['closedObserved'])
            self.assertEqual(session['eventCounts'], {'STARTING': 1, 'OPENED': 1})

    def test_native_missing_starting_keeps_boundaries_unobserved_instead_of_inferring_from_time(self):
        rows = [
            native_uwb('OPENED', uwbGattOperationStartedElapsedMs=100, uwbNativeElapsedSinceStartMs=5),
            native_uwb('CLOSED', 1, uwbGattOperationStartedElapsedMs=100, uwbNativeElapsedSinceStartMs=10),
            native_uwb('PEER_STARTED', 2, uwbGattOperationStartedElapsedMs=100, uwbNativeElapsedSinceStartMs=0),
            native_uwb('PEER_STOPPED', 3, uwbGattOperationStartedElapsedMs=200, uwbNativeElapsedSinceStartMs=0),
        ]
        sessions = parse(*rows)['uwbNativeSessions']
        self.assertEqual(len(sessions), 3)
        self.assertEqual([session['operationStartedElapsedMs'] for session in sessions], [100, 100, 200])
        self.assertTrue(all(not session['startingObserved'] for session in sessions))
        self.assertEqual([session['closedObserved'] for session in sessions], [True, False, False])
        self.assertTrue(all('STARTING' not in session['eventCounts'] for session in sessions))
        missing_operation = parse(
            native_uwb('PEER_STARTED', uwbNativeElapsedSinceStartMs=10),
            native_uwb('PEER_STOPPED', 1, uwbNativeElapsedSinceStartMs=0))
        unknown, = missing_operation['uwbNativeSessions']
        self.assertIsNone(unknown['operationStartedElapsedMs'])
        self.assertFalse(unknown['startingObserved'])
        self.assertFalse(unknown['closedObserved'])
        self.assertEqual(unknown['eventCounts'], {'PEER_STARTED': 1, 'PEER_STOPPED': 1})


    def test_native_fields_reject_bad_types_ranges_and_sensitive_strings(self):
        secret = 'SYNTHETIC-PRIVATE-SESSION-VIN-MAC-STS'
        raw = native_uwb()['state']
        invalid = {
            'uwbNativeSessionTag': [secret, 'A'*64, 'a'*63],
            'uwbNativeConfigId': [0, 6, '1', 1.0, True],
            'uwbNativeChannel': [0, 8, 10, '9', False],
            'uwbNativePreambleIndex': [8, 13, '10', True],
            'uwbNativeUpdateRate': [0, 4, '1', True],
            'uwbNativePeerCount': [0, -1, 2**31, '5', True],
            'uwbNativeEvent': [secret, 'closed', 'CLOSED ', 4, True],
            'uwbNativePeerIndex': [-1, 5, '4', True],
            'uwbNativeElapsedSinceStartMs': [-1, 1001, '123', True],
            'uwbNativeCloseRequested': [0, 1, 'false', secret],
        }
        for key, values in invalid.items():
            for value in values + [None, [], {'raw': secret}]:
                with self.subTest(key=key, value=value):
                    safe = uwb_native_state(dict(raw, **{key: value}), 1000, 31)
                    self.assertIsNone(safe[key])
                    self.assertNotIn(secret, json.dumps(safe))
        malformed = parse(native_uwb(uwbNativePeerIndex=True))
        self.assertIsNone(malformed['uwbEvents'][0]['nativeObservation'])
        self.assertEqual(malformed['uwbNativeSessions'][0]['eventCounts'], {})

    def test_dump_unique_match_ignores_opened_map_and_does_not_match_time(self):
        report = parse(native_uwb())
        result = match_uwb_system(uwb_dump(uwb_metric(), uwb_metric(42)), report)
        self.assertEqual(len(result['metrics']), 2)
        self.assertEqual(result['matches'][0]['status'], 'UNIQUE_TAG_MATCH')
        metric = result['matches'][0]['metrics'][0]
        self.assertEqual(metric['rangingCount'], 61)
        self.assertEqual(metric['validRangingCount'], 0)
        self.assertEqual(metric['initTime'], '10-29 16:32:10.123')
        self.assertEqual(result['timeBasis'], 'MONTH_DAY_WITHOUT_YEAR_OR_ZONE')
        self.assertEqual(result['interpretation'], 'TAG_CORRELATION_ONLY')
        unmatched = match_uwb_system(uwb_dump(uwb_metric(42)), report)
        self.assertEqual(unmatched['matches'][0]['status'], 'UNMATCHED')
        self.assertEqual(unmatched['matches'][0]['metrics'], [])
        self.assertEqual(unmatched['metrics'][0]['initTime'], metric['initTime'])

    def test_dump_reused_signed_unsigned_tag_is_ambiguous_even_for_identical_rows(self):
        report = parse(native_uwb())
        for rows in ((uwb_metric(), uwb_metric()), (uwb_metric(), uwb_metric(2**32-123456789))):
            result = match_uwb_system(uwb_dump(*rows), report)
            self.assertEqual(result['matches'][0]['status'], 'AMBIGUOUS_TAG_MATCH')
            self.assertEqual(len(result['matches'][0]['metrics']), 2)

    def test_dump_only_allows_metric_fields_and_masks_packages(self):
        report = parse(native_uwb())
        for package in ('android', 'com.teslamotors.tesla', 'com.google.android.gms', 'PRIVATE-PACKAGE'):
            result = match_uwb_system(uwb_dump(uwb_metric(packageName=package,
                privateKey='PRIVATE-KEY', uid='PRIVATE-UID', mac='PRIVATE-MAC')), report)
            metric = result['metrics'][0]
            self.assertEqual(metric['packageName'], package if package != 'PRIVATE-PACKAGE' else 'OTHER')
            self.assertEqual(set(metric), {'sessionTag', 'initTime', 'packageName', 'activeDurationMs',
                'rangingCount', 'validRangingCount', 'parallelSessionCount', 'channel', 'rangingIntervalMs'})
            self.assertNotIn('PRIVATE-', json.dumps(result))
        self.assertFalse(match_uwb_system(io.StringIO(uwb_metric()), report)['sectionObserved'])
        nonmetric = match_uwb_system(uwb_dump('sessionId=-123456789, rangingCount=61\n'), report)
        self.assertEqual(nonmetric['metrics'], [])
        self.assertEqual(nonmetric['matches'][0]['status'], 'UNMATCHED')

    def test_dump_rejects_invalid_ids_timestamps_and_numeric_types_without_raw_errors(self):
        report = parse(native_uwb())
        secret = 'SYNTHETIC-PRIVATE-DUMP'
        for raw in (secret, 'true', '1.0', '-2147483649', '4294967296', '9'*100, '1, sessionId=2'):
            result = match_uwb_system(uwb_dump(uwb_metric(sessionId=raw)), report)
            self.assertEqual(result['metrics'], [])
            self.assertEqual(result['matches'][0]['status'], 'UNMATCHED')
            self.assertNotIn(secret, json.dumps(result))
        for timestamp in (secret, '10-29 16:32:10.123 KST', '2026-10-29 16:32:10.123',
                          '02-30 16:32:10.123', '10-29 24:00:00.000'):
            result = match_uwb_system(uwb_dump(uwb_metric(initTime=timestamp)), report)
            self.assertIsNone(result['metrics'][0]['initTime'])
            self.assertNotIn(secret, json.dumps(result))
        for key in ('activeDurationMs', 'rangingCount', 'validRangingCount',
                    'parallelSessionCount', 'channel', 'rangingIntervalMs'):
            for value in ('true', '1.0', '-1', '2147483648', secret, '{}'):
                result = match_uwb_system(uwb_dump(uwb_metric(**{key: value})), report)
                self.assertIsNone(result['metrics'][0][key])
                self.assertNotIn(secret, json.dumps(result))
        invalid_bytes = io.BytesIO(b'-- mRangingSessionList --\n\xffPRIVATE\n' +
            ('initTime=' + 'PRIVATE' * MAX_LINE + '\n').encode())
        result = match_uwb_system(invalid_bytes, report)
        self.assertEqual([issue['code'] for issue in result['issues']], ['INVALID_UTF8', 'LINE_TOO_LONG'])
        self.assertNotIn('PRIVATE', json.dumps(result))

    def test_dump_cli_json_and_text_never_expose_ids_private_content_or_paths(self):
        secret = 'SYNTHETIC-PRIVATE-DUMP'
        log = (json.dumps(native_uwb()) + '\n').encode()
        dump = uwb_dump(uwb_metric(packageName=secret, uid=secret, private=secret)).getvalue().encode()
        for flags in ([], ['--json']):
            streams = [io.BytesIO(log), io.BytesIO(dump)]
            out, err = io.StringIO(), io.StringIO()
            with patch('builtins.open', side_effect=streams), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(main(['input.jsonl', '--uwb-dump', 'dump.txt', *flags]), 0)
            self.assertEqual(err.getvalue(), '')
            self.assertNotIn(secret, out.getvalue())
            self.assertNotIn('-123456789', out.getvalue())
            if flags:
                result = json.loads(out.getvalue())
                self.assertEqual(result['uwbSystemMatches']['matches'][0]['status'], 'UNIQUE_TAG_MATCH')
            else:
                objects = [json.loads(line[line.index('{'):])
                           for line in out.getvalue().splitlines() if '{' in line]
                system = next(value for value in objects if 'interpretation' in value)
                self.assertEqual(system['matches'][0]['status'], 'UNIQUE_TAG_MATCH')
                self.assertEqual(system['interpretation'], 'TAG_CORRELATION_ONLY')
        out, err = io.StringIO(), io.StringIO()
        with patch('builtins.open', side_effect=[io.BytesIO(log), OSError(secret)]), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(main(['input.jsonl', '--uwb-dump', secret]), 2)
        self.assertEqual(err.getvalue(), 'INPUT_READ_ERROR\n')
        self.assertNotIn(secret, out.getvalue() + err.getvalue())


class AnalysisTests(unittest.TestCase):
    def test_supported_schema_boundary_without_inventing_uwb_events(self):
        report = parse(*(record(version=version) for version in range(1, 40)))
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['counts']['analyzedRows'], 39)
        self.assertEqual(report['counts']['unsupportedRows'], 0)
        self.assertEqual(set(report['versions']), {str(version) for version in range(1, 40)})
        self.assertEqual(report['uwbEvents'], [])
        for version in range(1, 23):
            row = uwb()
            row['version'] = version
            self.assertEqual(parse(row)['uwbEvents'], [])

    def test_v30_deadline_cause_is_version_gated_and_never_inferred_from_timeout(self):
        for version in range(1, 31):
            row = dict(uwb_gatt('TESLA_UWB_COMPLETE', uwbStage='TIMED_OUT',
                uwbReason='DEADLINE_EXPIRED', uwbDeadlineCause='INITIAL_NEGOTIATION'),
                version=version)
            report = parse(row)
            if version < 23:
                self.assertEqual(report['uwbEvents'], [])
            else:
                expected = 'INITIAL_NEGOTIATION' if version == 30 else None
                self.assertEqual(report['uwbEvents'][0]['state']['uwbDeadlineCause'], expected)
                if version >= 25:
                    self.assertEqual(report['uwbRxOperations'][0]['deadlineCause'], expected)
                else:
                    self.assertEqual(report['uwbRxOperations'], [])
        for version in range(23, 31):
            for reason in ('DEADLINE_EXPIRED', 'RANGING_TIMEOUT', 'GATT_WRITE_FAILED'):
                report = parse(dict(uwb_gatt('TESLA_UWB_COMPLETE',
                    uwbStage='TIMED_OUT', uwbReason=reason), version=version))
                self.assertIsNone(report['uwbEvents'][0]['state']['uwbDeadlineCause'])
                if report['uwbRxOperations']:
                    self.assertIsNone(report['uwbRxOperations'][0]['deadlineCause'])

    def test_v30_deadline_cause_has_exact_enum_allowlist_without_reason_or_raw_leaks(self):
        causes = ('OPERATION', 'INITIAL_NEGOTIATION', 'PROTOCOL_STAGE', 'WRITE')
        secret = 'PRIVATE-RAW-KEY-STS-VIN-MAC'
        for cause in (*causes, None, 'UNKNOWN', 'operation', 'WRITE ', 'DEADLINE_EXPIRED',
                      secret, True, 0, [], {'cause': secret}):
            report = parse(dict(uwb_gatt('TESLA_UWB_COMPLETE', uwbDeadlineCause=cause,
                uwbReason='DEADLINE_EXPIRED', rawDeadline=secret), version=30))
            expected = cause if type(cause) is str and cause in causes else None
            point, = report['uwbEvents']
            self.assertEqual(point['state']['uwbDeadlineCause'], expected)
            self.assertEqual(report['uwbRxOperations'][0]['deadlineCause'], expected)
            self.assertEqual(point['state']['uwbReason'], 'DEADLINE_EXPIRED')
            self.assertFalse(point['newDistanceReceived'])
            self.assertNotIn(secret, json.dumps(report, allow_nan=False))
            self.assertNotIn('rawDeadline', json.dumps(report, allow_nan=False))
        for cause in causes:
            point, = parse(dict(uwb_gatt(uwbReason=cause), version=30))['uwbEvents']
            self.assertIsNone(point['state']['uwbReason'])
            self.assertIsNone(point['state']['uwbDeadlineCause'])

    def test_v30_summary_preserves_first_observed_cause_without_crossing_operation(self):
        rows = [
            dict(uwb_gatt(), version=30),
            dict(uwb_gatt('TESLA_UWB_COMPLETE', 1, uwbReason='DEADLINE_EXPIRED',
                uwbDeadlineCause='WRITE'), version=30),
            dict(uwb_gatt('TESLA_UWB_COMPLETE', 2, uwbReason='GATT_WRITE_FAILED',
                uwbDeadlineCause='OPERATION'), version=30),
            dict(uwb_gatt('TESLA_UWB_STARTED', 3,
                uwbGattOperationStartedElapsedMs=1003), version=30),
        ]
        report = parse(*rows)
        self.assertEqual([op['deadlineCause'] for op in report['uwbRxOperations']], ['WRITE', None])
        self.assertEqual([point['state']['uwbReason'] for point in report['uwbEvents']],
            [None, 'DEADLINE_EXPIRED', 'GATT_WRITE_FAILED', None])
        self.assertTrue(all(not point['newDistanceReceived'] for point in report['uwbEvents']))
        self.assertTrue(all(op['observedEvents']['protocol'] == 0 for op in report['uwbRxOperations']))

    def test_v30_deadline_cause_is_available_in_cli_json_and_sanitized_text(self):
        rows = [dict(uwb_gatt('TESLA_UWB_COMPLETE', uwbReason='DEADLINE_EXPIRED',
                    uwbDeadlineCause='INITIAL_NEGOTIATION'), version=30),
                dict(uwb_gatt('TESLA_UWB_COMPLETE', 1, uwbReason='DEADLINE_EXPIRED',
                    uwbDeadlineCause='PRIVATE-RAW-KEY'), version=29)]
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode('utf-8')
        for args in (['synthetic-input.jsonl'], ['synthetic-input.jsonl', '--json']):
            out = io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                self.assertEqual(main(args), 0)
            text = out.getvalue()
            self.assertNotIn('PRIVATE-RAW-KEY', text)
            if '--json' in args:
                report = json.loads(text)
                self.assertEqual([point['state']['uwbDeadlineCause'] for point in report['uwbEvents']],
                    ['INITIAL_NEGOTIATION', None])
                self.assertEqual(report['uwbRxOperations'][0]['deadlineCause'], 'INITIAL_NEGOTIATION')
            else:
                points = [json.loads(line[line.index('{'):]) for line in text.splitlines()
                    if 'GATT 현재 관측 ' in line]
                self.assertEqual([point['uwbDeadlineCause'] for point in points],
                    ['INITIAL_NEGOTIATION', None])

    def test_v29_ignored_rx_is_version_gated_and_rejects_near_miss_enums(self):
        state = {'uwbRxStep': 'IGNORED', 'uwbRxDisposition': 'RESPONSE_BEFORE_START'}
        for version in range(1, 31):
            with self.subTest(version=version):
                safe = uwb_rx_state(state, 1000, version)
                for key, value in state.items():
                    self.assertEqual(safe[key], value if version >= 29 else None)
        for key in state:
            for value in ('ignored', 'IGNORED ', 'RESPONSE_BEFORE_START ',
                          'response_before_start', 'PRIVATE-RAW-KEY', True, 0, [], {}):
                safe = uwb_rx_state({key: value}, 1000, 29)
                self.assertIsNone(safe[key])
        # Each new literal remains invalid in the other field.
        safe = uwb_rx_state(dict(uwbRxStep='RESPONSE_BEFORE_START',
            uwbRxDisposition='IGNORED'), 1000, 29)
        self.assertIsNone(safe['uwbRxStep'])
        self.assertIsNone(safe['uwbRxDisposition'])

    def test_v29_ignored_rx_counts_once_without_refreshing_past_zero_distance(self):
        protocol = dict(uwb_rx(uwbGattEvent='PROTOCOL', uwbGattRxNotificationCount=1,
            uwbRxFrameCount=1, uwbRxProtocolCount=1,
            uwbRxStep='IGNORED', uwbRxDisposition='RESPONSE_BEFORE_START',
            uwbRxProtocolReceivedElapsedMs=99, uwbRxProtocolObservedElapsedMs=900,
            uwbStage='AUTHENTICATING', uwbBackendOpened=False, uwbValidDistanceCount=0,
            uwbStartedPeerCount=0, uwbLastDistanceMeters=0.0,
            uwbLastReceivedElapsedMs=90), version=29)
        rows = [protocol,
            dict(protocol, elapsedMs=1001, wallMs=BASE+1),
            dict(protocol, event='TESLA_UWB_COMPLETE', elapsedMs=1002, wallMs=BASE+2)]
        distance = dict(uwb(offset=3, uwbLastDistanceMeters=0.0,
            uwbLastReceivedElapsedMs=1003), version=29)
        report = parse(*rows, distance)
        points = report['uwbEvents']
        self.assertEqual([point['rxObservation']['newProtocol'] for point in points],
            [True, False, False, False])
        self.assertEqual([point['newDistanceReceived'] for point in points],
            [False, False, False, True])
        for point in points[:3]:
            state = point['state']
            self.assertEqual(state['uwbLastDistanceMeters'], 0.0)
            self.assertEqual(state['uwbLastReceivedElapsedMs'], 90)
            self.assertEqual(state['uwbValidDistanceCount'], 0)
            self.assertIs(state['uwbBackendOpened'], False)
            self.assertIsNone(state['uwbRxAuthEvidence'])
            self.assertIsNone(state['uwbRxFiraKind'])
        operation, = report['uwbRxOperations']
        self.assertEqual(operation['counterTotals'], {'notification': 1, 'frame': 1, 'protocol': 1})
        self.assertEqual(operation['observedEvents'], {'notification': 0, 'frame': None, 'protocol': 1})
        self.assertEqual(operation['classifications']['uwbRxStep'], {'IGNORED': 1})
        self.assertEqual(operation['classifications']['uwbRxDisposition'], {'RESPONSE_BEFORE_START': 1})
        self.assertEqual(operation['classifications']['uwbRxAuthEvidence'], {'unobserved': 1})
        self.assertEqual(operation['classifications']['uwbRxFiraKind'], {'unobserved': 1})
        self.assertEqual(points[-1]['state']['uwbLastDistanceMeters'], 0.0)
        self.assertEqual(points[-1]['state']['uwbLastReceivedElapsedMs'], 1003)

    def test_v29_ignored_approach_does_not_create_authenticated_status_or_recovery(self):
        for version in range(9, 31):
            row = dict(approach(responseDisposition='RESPONSE_BEFORE_START',
                rxFrameCount=1, rxProtocolCount=1, stage='HANDSHAKING'), version=version)
            trial = parse(row)['trials'][0]
            summary = trial['approachReadAttempts'][0]['lastSummary']
            self.assertEqual(summary['responseDisposition'],
                'RESPONSE_BEFORE_START' if version >= 29 else None)
            self.assertFalse(summary['bodyVerified'])
            self.assertFalse(summary['driveVerified'])
            self.assertIsNone(summary['bodyReceivedElapsedMs'])
            self.assertIsNone(summary['driveReceivedElapsedMs'])
            self.assertEqual(trial['approachCompleteReadCount'], 0)
            self.assertEqual(trial['approachRecoveryReadCount'], 0)
        for value in ('RESPONSE_BEFORE_START ', 'response_before_start', 'IGNORED',
                      'PRIVATE-RAW-KEY', True, 0, [], {}):
            row = dict(approach(responseDisposition=value), version=29)
            summary = parse(row)['trials'][0]['approachReadAttempts'][0]['lastSummary']
            self.assertIsNone(summary['responseDisposition'])
        report = parse(*(dict(row, version=29) for row in recovery_rows()))
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['trials'][0]['approachRecoveryReadCount'], 1)

    def test_v29_ignored_sidecar_is_not_handshake_result_or_phone_freshness(self):
        for version in (28, 29, 30):
            row = dict(navigation('SIDECAR_RX', disposition='RESPONSE_BEFORE_START',
                vehicleFixPresent=False), version=version)
            report = parse(row)
            observation = report['navigationEvents'][0]['observation']
            self.assertEqual(observation['disposition'],
                'RESPONSE_BEFORE_START' if version >= 29 else None)
            self.assertIsNone(observation['authEvidence'])
            self.assertIs(observation['vehicleFixPresent'], False)
            operation, = report['navigationOperations']
            self.assertEqual(operation['kindCounts'], {'SIDECAR_RX': 1})
            self.assertEqual(operation['phoneInputKindCounts'], {})
            self.assertEqual(operation['presentationStatusCounts'], {})
            self.assertIsNone(operation['lastResult'])
            self.assertIsNone(operation['lastTerminal'])
            self.assertEqual(report['uwbRxOperations'], [])
        for value in ('RESPONSE_BEFORE_START ', 'response_before_start', 'IGNORED',
                      'PRIVATE-RAW-KEY', True, 0, [], {}):
            row = dict(navigation('SIDECAR_RX', disposition=value), version=29)
            self.assertIsNone(parse(row)['navigationEvents'][0]['observation']['disposition'])

    def test_v32_navigation_invalid_time_remains_distinct_in_reports(self):
        rows = [dict(row, version=32) for row in (
            navigation('DISPLAY_STATUS_CHANGED', status='VEHICLE_LOCATION_TIME_INVALID'),
            navigation('SOURCE_STOPPED', 1, reason='VEHICLE_FIX_TIME_INVALID', collecting=False),
            navigation('RESULT', 2, reason='LOCATION_STATE_TIME_INVALID', vehicleFixPresent=False))]
        report = parse(*rows)
        self.assertEqual(report['issues'], [])
        operation, = report['navigationOperations']
        self.assertEqual(operation['presentationStatusCounts'], {'VEHICLE_LOCATION_TIME_INVALID': 1})
        self.assertEqual(operation['phoneInputKindCounts'], {'SOURCE_STOPPED': 1})
        self.assertEqual(report['navigationEvents'][1]['observation']['reason'], 'VEHICLE_FIX_TIME_INVALID')
        self.assertIs(report['navigationEvents'][1]['observation']['collecting'], False)
        self.assertEqual(operation['lastResult']['observation']['reason'], 'LOCATION_STATE_TIME_INVALID')
        self.assertIs(operation['lastResult']['observation']['vehicleFixPresent'], False)
        self.assertEqual(report['uwbEvents'], [])
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode()
        for flags in ([], ['--json']):
            out = io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                self.assertEqual(main(['synthetic-input.jsonl', *flags]), 0)
            if flags:
                self.assertEqual(json.loads(out.getvalue()), report)
            else:
                for value in ('VEHICLE_LOCATION_TIME_INVALID', 'VEHICLE_FIX_TIME_INVALID',
                              'LOCATION_STATE_TIME_INVALID'):
                    self.assertIn(value, out.getvalue())

    def test_pre_v32_navigation_does_not_accept_new_time_classifications(self):
        for version in range(28, 32):
            for reason in ('VEHICLE_FIX_TIME_INVALID', 'LOCATION_STATE_TIME_INVALID'):
                with self.subTest(version=version, reason=reason):
                    row = dict(navigation('DISPLAY_STATUS_CHANGED',
                        status='VEHICLE_LOCATION_TIME_INVALID', reason=reason), version=version)
                    report = parse(row)
                    observation, = [point['observation'] for point in report['navigationEvents']]
                    self.assertIsNone(observation['status'])
                    self.assertIsNone(observation['reason'])
                    self.assertEqual(report['navigationOperations'][0]['presentationStatusCounts'],
                        {'unobserved': 1})

    def test_navigation_expiry_is_not_reclassified_as_invalid_time(self):
        for version in range(28, 33):
            with self.subTest(version=version):
                rows = [dict(row, version=version) for row in (
                    navigation('DISPLAY_STATUS_CHANGED', status='VEHICLE_LOCATION_STALE'),
                    navigation('SOURCE_STOPPED', 1, reason='VEHICLE_FIX_EXPIRED', collecting=False),
                    navigation('RESULT', 2, reason='LOCATION_FIX_TIME_FUTURE', vehicleFixPresent=False))]
                report = parse(*rows)
                operation, = report['navigationOperations']
                self.assertEqual(operation['presentationStatusCounts'], {'VEHICLE_LOCATION_STALE': 1})
                self.assertEqual(report['navigationEvents'][1]['observation']['reason'], 'VEHICLE_FIX_EXPIRED')
                self.assertEqual(operation['lastResult']['observation']['reason'], 'LOCATION_FIX_TIME_FUTURE')
                for value in ('VEHICLE_LOCATION_TIME_INVALID', 'VEHICLE_FIX_TIME_INVALID',
                              'LOCATION_STATE_TIME_INVALID'):
                    self.assertNotIn(value, json.dumps(report))

    def test_v28_navigation_exact_allowlist_and_context_without_raw_values(self):
        secret = 'SYNTHETIC-PRIVATE-GPS-HEADING-VIN-ADDRESS-UUID-TOKEN-EXCEPTION'
        row = navigation('SIDECAR_RX', reason='LOCATION_PROTOCOL_INVALID',
            status='WAITING_FOR_VEHICLE_LOCATION', disposition='ACK_WAITING',
            authEvidence='ACKNOWLEDGED', candidate=0, attempt=0,
            **{key: index % 2 == 0 for index, key in enumerate(NAVIGATION_BOOL_KEYS)})
        expected = dict(row['navigationObservation'])
        for key in ('latitude', 'longitude', 'gpsTime', 'heading', 'vin', 'address',
                    'uuid', 'key', 'token', 'exception', 'rawPayload', secret):
            row['navigationObservation'][key] = secret
        row['state'] = {'navigationObservation': {'exception': secret}, 'latitude': secret}
        row['exception'] = secret
        report = parse(row)
        self.assertEqual(report['issues'], [])
        point, = report['navigationEvents']
        self.assertEqual(point, dict(line=1, processId=P, segment=1, trialId=T,
            recordWallMs=BASE, recordElapsedMs=1000, clockAlignmentWarning=False,
            event='VEHICLE_NAVIGATION_OBSERVATION', observation=expected))
        self.assertEqual(set(point['observation']), set(NAVIGATION_KEYS))
        self.assertNotIn(secret, json.dumps(report, allow_nan=False))
        operation, = report['navigationOperations']
        self.assertTrue(operation['scopeComplete'])
        self.assertEqual(operation['kindCounts'], {'SIDECAR_RX': 1})
        self.assertEqual(report['uwbEvents'], [])

    def test_v28_navigation_requires_exact_version_event_and_typed_object(self):
        rows = [dict(navigation(), version=version) for version in range(1, 28)]
        rows += [dict(navigation(offset=index), event=event)
                 for index, event in enumerate(('BLE_EVIDENCE', 'TESLA_UWB_AUTHENTICATED',
                     'VEHICLE_NAVIGATION_OBSERVATION_EXTRA', None, [], {}))]
        rows += [dict(navigation(), navigationObservation=value)
                 for value in (None, False, 1, 'PRIVATE-RAW', [], {})]
        rows += [dict(navigation(), processId='PRIVATE-RAW')]
        report = parse(*rows)
        self.assertEqual(report['navigationEvents'], [])
        self.assertEqual(report['navigationOperations'], [])
        self.assertNotIn('PRIVATE-RAW', json.dumps(report))
        # A typed sidecar payload on primary UWB RX must not be reclassified.
        primary = uwb_rx_observation(uwbGattEvent='PROTOCOL', uwbRxProtocolCount=1,
            uwbRxDisposition='FIRA_REQUEST', uwbRxStep='UWB_INPUT')
        injected = dict(primary, version=28,
            navigationObservation=navigation()['navigationObservation'])
        baseline = parse(dict(primary, version=28))
        report = parse(injected)
        self.assertEqual(report['uwbEvents'], baseline['uwbEvents'])
        self.assertEqual(report['uwbRxOperations'], baseline['uwbRxOperations'])
        self.assertEqual(report['navigationEvents'], [])

    def test_v28_navigation_rejects_invalid_enums_and_boolean_integer_coercion(self):
        for value in (True, False, -1, 1.0, '1', 2**63, [], {}):
            with self.subTest(number=value):
                safe = parse(navigation(candidate=value, attempt=value))['navigationEvents'][0]['observation']
                self.assertIsNone(safe['candidate'])
                self.assertIsNone(safe['attempt'])
        for value in (0, 1, 2**63-1):
            safe = parse(navigation(candidate=value, attempt=value))['navigationEvents'][0]['observation']
            self.assertEqual((safe['candidate'], safe['attempt']), (value, value))
        for value in (None, 'SIDECAR_STARTED ', 'sidecar_started', 'UNKNOWN',
                      'PRIVATE-RAW-ENUM', True, 1, [], {}):
            self.assertEqual(parse(navigation(kind=value))['navigationEvents'], [])
        for key in ('reason', 'status', 'disposition', 'authEvidence'):
            for value in ('PRIVATE-RAW-ENUM', '', 'unknown', True, 1, [], {}):
                with self.subTest(key=key, value=value):
                    report = parse(navigation(**{key: value}))
                    self.assertIsNone(report['navigationEvents'][0]['observation'][key])
                    self.assertNotIn('PRIVATE-RAW-ENUM', json.dumps(report))
            self.assertEqual(parse(navigation(**{key: 'UNKNOWN'}))['navigationEvents'][0]['observation'][key],
                'UNKNOWN')
        for value in (0, 1, 'true', [], {}, None):
            safe = parse(navigation(**dict.fromkeys(NAVIGATION_BOOL_KEYS, value)))['navigationEvents'][0]['observation']
            self.assertTrue(all(safe[key] is None for key in NAVIGATION_BOOL_KEYS))
        for value in (True, False):
            safe = parse(navigation(**dict.fromkeys(NAVIGATION_BOOL_KEYS, value)))['navigationEvents'][0]['observation']
            self.assertTrue(all(safe[key] is value for key in NAVIGATION_BOOL_KEYS))

    def test_v28_navigation_exact_protocol_literals_and_skipped_before_start(self):
        for disposition in ('ROUTE_MISMATCH', 'STATUS_INCOMPLETE'):
            row = navigation('SIDECAR_RX', disposition=disposition,
                authEvidence='REQUEST_REJECTED', reason='LOCATION_REQUEST_REJECTED')
            safe = parse(row)['navigationEvents'][0]['observation']
            self.assertEqual(safe['disposition'], disposition)
            self.assertEqual(safe['authEvidence'], 'REQUEST_REJECTED')
            self.assertEqual(safe['reason'], 'LOCATION_REQUEST_REJECTED')
        report = parse(
            navigation('SIDECAR_SKIPPED', reason='OPERATION_ENDED_BEFORE_START'),
            navigation('TERMINAL', 1, reason='OWNER_ENDED'))
        operation, = report['navigationOperations']
        self.assertEqual(operation['kindCounts'], {'SIDECAR_SKIPPED': 1, 'TERMINAL': 1})
        self.assertIsNone(operation['lastResult'])
        self.assertEqual(report['navigationEvents'][0]['observation']['reason'],
            'OPERATION_ENDED_BEFORE_START')
        self.assertEqual(operation['lastTerminal']['observation']['reason'], 'OWNER_ENDED')

    def test_v28_navigation_attempt_result_phone_input_and_presentation_stay_separate(self):
        rows = [
            approach('TESLA_APPROACH_READ_STARTED', stage='PAUSING_SCAN'),
            navigation('SIDECAR_STARTED', 1),
            navigation('HANDSHAKE_TX_COMPLETE', 2),
            navigation('SIDECAR_RX', 3, disposition='ACK_WAITING', authEvidence='ACKNOWLEDGED',
                requestUuidMatches=True, toRouteMatches=True),
            navigation('HANDSHAKE_ACCEPTED', 4),
            navigation('QUERY_TX_COMPLETE', 5),
            navigation('PHONE_LOCATION_FIRST_VALID', 6, collecting=True),
            navigation('HEADING_FIRST_VALID', 7),
            navigation('DISPLAY_STATUS_CHANGED', 8, status='WAITING_FOR_VEHICLE_LOCATION'),
            navigation('RESULT', 9, reason='LOCATION_COORDINATES_MISSING', vehicleFixPresent=False),
            navigation('TERMINAL', 10, reason='ATTEMPT_ENDED'),
            navigation('SIDECAR_STARTED', 11, attempt=2),
            navigation('RESULT', 12, attempt=2, vehicleFixPresent=True),
            navigation('DISPLAY_STATUS_CHANGED', 13, attempt=2, status='DISTANCE_ONLY'),
            navigation('SOURCE_STOPPED', 14, attempt=2, cleanupSucceeded=False),
            navigation('TERMINAL', 15, attempt=2, reason='CLEANUP_FAILED'),
        ]
        report = parse(*rows)
        first, second = report['navigationOperations']
        read, = report['trials'][0]['approachReadAttempts']
        self.assertEqual((first['processId'], first['segment'], first['candidate'], first['attempt']),
            (read['processId'], read['segment'], read['candidate'], read['attempt']))
        self.assertEqual(first['trialId'], report['trials'][0]['trialId'])
        self.assertEqual(first['kindCounts'], {kind: 1 for kind in (
            'SIDECAR_STARTED', 'HANDSHAKE_TX_COMPLETE', 'SIDECAR_RX', 'HANDSHAKE_ACCEPTED',
            'QUERY_TX_COMPLETE', 'PHONE_LOCATION_FIRST_VALID', 'HEADING_FIRST_VALID',
            'DISPLAY_STATUS_CHANGED', 'RESULT', 'TERMINAL')})
        self.assertEqual(first['phoneInputKindCounts'],
            {'PHONE_LOCATION_FIRST_VALID': 1, 'HEADING_FIRST_VALID': 1})
        self.assertEqual(first['presentationStatusCounts'], {'WAITING_FOR_VEHICLE_LOCATION': 1})
        self.assertFalse(first['lastResult']['observation']['vehicleFixPresent'])
        self.assertEqual(first['lastResult']['recordElapsedMs'], 1009)
        self.assertEqual(first['lastTerminal']['observation']['reason'], 'ATTEMPT_ENDED')
        self.assertEqual(second['attempt'], 2)
        self.assertNotIn('HANDSHAKE_ACCEPTED', second['kindCounts'])
        self.assertTrue(second['lastResult']['observation']['vehicleFixPresent'])
        self.assertIsNone(second['lastResult']['observation']['collecting'])
        self.assertEqual(second['phoneInputKindCounts'], {'SOURCE_STOPPED': 1})
        self.assertEqual(second['presentationStatusCounts'], {'DISTANCE_ONLY': 1})
        self.assertEqual(second['lastTerminal']['observation']['reason'], 'CLEANUP_FAILED')
        self.assertEqual(report['uwbEvents'], [])

    def test_v28_navigation_scope_respects_trial_process_clock_and_unknown_identity(self):
        rows = [navigation('RESULT', vehicleFixPresent=True),
            navigation(offset=1, candidate=2),
            navigation(offset=2, attempt=2),
            dict(navigation(offset=3), trialId=U),
            dict(navigation(offset=4), processId=Q),
            dict(navigation(offset=5), processId=Q, elapsedMs=10),
            dict(navigation(offset=6, candidate=None), processId=Q, elapsedMs=11),
            dict(navigation(offset=7, candidate=None), processId=Q, elapsedMs=12),
            dict(navigation(offset=8, attempt=None), processId=Q, elapsedMs=13),
            dict(navigation(offset=9), processId=Q, trialId=None, elapsedMs=14)]
        report = parse(*rows)
        operations = report['navigationOperations']
        self.assertEqual(len(operations), len(rows))
        self.assertEqual([op['scopeComplete'] for op in operations],
            [True, True, True, True, True, True, False, False, False, True])
        self.assertEqual([op['segment'] for op in operations], [1, 1, 1, 1, 2, 3, 3, 3, 3, 3])
        self.assertTrue(all(op['lastResult'] is None for op in operations[1:]))
        self.assertIsNone(operations[-1]['trialId'])
        excluded = parse(*rows, exclude=(T,))
        self.assertEqual(len(excluded['navigationEvents']), 2)
        self.assertEqual([point['trialId'] for point in excluded['navigationEvents']], [U, None])

    def test_v28_navigation_cli_json_and_text_keep_privacy_and_no_success_promotion(self):
        secret = 'SYNTHETIC-PRIVATE-NAVIGATION-EXCEPTION'
        rows = [navigation('HANDSHAKE_TX_COMPLETE'),
            navigation('HANDSHAKE_ACCEPTED', 1, authEvidence='HMAC_MISMATCH'),
            navigation('QUERY_TX_COMPLETE', 2),
            navigation('PHONE_INPUT_UNAVAILABLE', 3, reason='HEADING_UNRELIABLE'),
            navigation('DISPLAY_STATUS_CHANGED', 4, status='WAITING_FOR_PHONE_LOCATION'),
            navigation('RESULT', 5, reason='LOCATION_TIMEOUT', vehicleFixPresent=False,
                rawCoordinates=secret, heading=secret, gpsTime=secret, exception=secret),
            navigation('TERMINAL', 6, reason='ATTEMPT_ENDED')]
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode()
        for json_output in (False, True):
            out = io.StringIO()
            args = ['synthetic-input.jsonl'] + (['--json'] if json_output else [])
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                self.assertEqual(main(args), 0)
            self.assertNotIn(secret, out.getvalue())
            if json_output:
                report = json.loads(out.getvalue())
                self.assertEqual(len(report['navigationEvents']), 7)
                self.assertEqual(report['uwbEvents'], [])
                self.assertEqual(report['navigationOperations'][0]['lastResult']['observation']['reason'],
                    'LOCATION_TIMEOUT')
            else:
                text = out.getvalue()
                self.assertIn('LOCATION_TIMEOUT', text)
                self.assertIn('HMAC_MISMATCH', text)

    def test_v28_navigation_absence_does_not_mean_not_attempted_or_permission_denied(self):
        for version in (1, 27, 28):
            row = dict(record(version=version), navigationObservation=navigation()['navigationObservation'])
            report = parse(row)
            self.assertEqual(report['navigationEvents'], [])
            self.assertEqual(report['navigationOperations'], [])

    def test_navigation_schema_updates_keep_existing_approach_recovery_interpretation(self):
        for version in (28, 31, 32):
            report = parse(*(dict(row, version=version) for row in recovery_rows()))
            self.assertEqual(report['issues'], [])
            self.assertEqual(report['trials'][0]['approachRecoveryReadCount'], 1)

    def test_v24_manual_uwb_keeps_null_trial_and_record_context_before_trial_filter(self):
        row = uwb()
        report = parse(row)
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['trials'], [])
        self.assertEqual(report['counts']['unassignedRows'], 1)
        point = report['uwbEvents'][0]
        self.assertIsNone(point['trialId'])
        self.assertEqual(point['processId'], P)
        self.assertEqual(point['recordWallMs'], BASE)
        self.assertEqual(point['recordElapsedMs'], 1000)
        self.assertTrue(point['newDistanceReceived'])
        assigned = dict(uwb(offset=10), trialId=T)
        excluded = parse(row, assigned, exclude=(T,))
        self.assertEqual(len(excluded['uwbEvents']), 1)
        self.assertIsNone(excluded['uwbEvents'][0]['trialId'])
        self.assertEqual(excluded['counts']['excludedRows'], 1)
        self.assertEqual(excluded['excludedTrialsFound'], [T])

    def test_v23_missing_raw_observations_remain_unknown_and_do_not_carry_from_v24(self):
        keys = ('uwbLastAzimuthRaw', 'uwbLastAzimuthConfidence',
            'uwbLastElevationRaw', 'uwbLastElevationConfidence',
            'uwbAzimuthSupported', 'uwbElevationSupported')
        current = uwb()
        legacy = dict(uwb(offset=1), version=23)
        for key in keys:
            del legacy['state'][key]
        legacy_with_unknown_fields = dict(uwb(offset=2), version=23)
        report = parse(current, legacy, legacy_with_unknown_fields)
        self.assertEqual(report['issues'], [])
        self.assertEqual(len(report['uwbEvents']), 3)
        for point in report['uwbEvents'][1:]:
            self.assertTrue(point['newDistanceReceived'])
            self.assertEqual(point['state']['uwbLastDistanceMeters'], 1.25)
            self.assertEqual(point['state']['uwbLastPeerIndex'], 0)
            self.assertEqual(point['state']['uwbLastFrameworkTimestampMs'], 9876543210123)
            self.assertEqual(point['state']['uwbLastReceivedElapsedMs'], 990)
            self.assertTrue(all(point['state'][key] is None for key in keys))

    def test_uwb_export_uses_fixed_events_and_strips_private_state_and_strings(self):
        row = uwb(uwbStage='RANGING-private-VIN', uwbClosedReason='private-address',
            uwbLastDistanceMeters='private-distance', uwbLastConfidence='private-confidence',
            uwbLastFrameworkTimestampMs='private-clock', uwbLastReceivedElapsedMs='private-time',
            uwbOpenFailedReason='private-open-failure', uwbReason='PRIVATE_REASON_WITH_VIN',
            uwbLastAzimuthRaw='private-azimuth', uwbLastAzimuthConfidence='private-confidence',
            uwbLastElevationRaw='private-elevation', uwbLastElevationConfidence='private-confidence',
            uwbAzimuthSupported='private-capability', uwbElevationSupported='private-capability',
            uwbKey='private-key', uwbVin='private-VIN', uwbAddress='private-address',
            uwbSts='private-sts', gps='private-gps', uwbAngleUnit='private-unit',
            uwbDirection='private-direction', arbitrary='private-payload')
        unknown = dict(uwb(offset=10), event='TESLA_UWB_private-event')
        report = parse(row, unknown)
        self.assertEqual(len(report['uwbEvents']), 1)
        safe = report['uwbEvents'][0]['state']
        for key in ('uwbStage', 'uwbClosedReason', 'uwbLastDistanceMeters', 'uwbLastConfidence',
                    'uwbLastFrameworkTimestampMs', 'uwbLastReceivedElapsedMs',
                    'uwbOpenFailedReason', 'uwbReason', 'uwbLastAzimuthRaw', 'uwbLastAzimuthConfidence',
                    'uwbLastElevationRaw', 'uwbLastElevationConfidence',
                    'uwbAzimuthSupported', 'uwbElevationSupported'):
            self.assertIsNone(safe[key])
        self.assertFalse(report['uwbEvents'][0]['newDistanceReceived'])
        self.assertNotIn('private-', json.dumps(report, allow_nan=False))
        self.assertNotIn('PRIVATE_REASON_WITH_VIN', json.dumps(report, allow_nan=False))

    def test_uwb_distance_requires_finite_nonnegative_numbers_not_booleans(self):
        for meters in (0, 1, 1.25, 1e308):
            with self.subTest(meters=meters):
                point = parse(uwb(uwbLastDistanceMeters=meters))['uwbEvents'][0]
                self.assertEqual(point['state']['uwbLastDistanceMeters'], meters)
                self.assertTrue(point['newDistanceReceived'])
        for meters in (None, -1, -0.1, True, False, '1.25', float('inf'),
                       float('-inf'), float('nan'), [], {}):
            with self.subTest(meters=meters):
                report = parse(uwb(uwbLastDistanceMeters=meters))
                self.assertIsNone(report['uwbEvents'][0]['state']['uwbLastDistanceMeters'])
                self.assertFalse(report['uwbEvents'][0]['newDistanceReceived'])
                json.dumps(report, allow_nan=False)

    def test_uwb_raw_angles_keep_finite_signed_doubles_without_conversion(self):
        for raw in (-1.7976931348623157e308, -540.0, -0.0, 0.0, 5e-324,
                    3.141592653589793, 1.7976931348623157e308):
            with self.subTest(raw=raw):
                row = uwb(uwbLastAzimuthRaw=raw, uwbLastElevationRaw=raw)
                point = parse(row)['uwbEvents'][0]
                self.assertEqual(json.dumps(point['state']['uwbLastElevationRaw']), json.dumps(raw))
                self.assertEqual(json.dumps(point['state']['uwbLastAzimuthRaw']), json.dumps(raw))
                self.assertTrue(point['newDistanceReceived'])
        for raw in (None, True, False, 0, 1, -1, 2**63, '1.25', float('inf'),
                    float('-inf'), float('nan'), ['private-raw'], {'private-raw': 1}):
            with self.subTest(raw=raw):
                report = parse(uwb(uwbLastAzimuthRaw=raw, uwbLastElevationRaw=raw))
                point = report['uwbEvents'][0]
                self.assertIsNone(point['state']['uwbLastAzimuthRaw'])
                self.assertIsNone(point['state']['uwbLastElevationRaw'])
                self.assertEqual(point['state']['uwbLastAzimuthConfidence'], 0)
                self.assertEqual(point['state']['uwbLastElevationConfidence'], 2)
                self.assertTrue(point['newDistanceReceived'])
                self.assertNotIn('private-raw', json.dumps(report, allow_nan=False))

    def test_uwb_angle_confidence_is_axis_independent_and_never_reuses_distance_confidence(self):
        for confidence in (0, 1, 2, None, -1, 3, 2**63, True, False, 1.0, '2',
                           float('nan'), float('inf'), [], {'private-confidence': 2}):
            with self.subTest(confidence=confidence):
                expected = confidence if type(confidence) is int and 0 <= confidence <= 2 else None
                for axis, other in (('Azimuth', 'Elevation'), ('Elevation', 'Azimuth')):
                    point = parse(uwb(**{
                        'uwbLastConfidence': 1,
                        f'uwbLast{axis}Raw': None,
                        f'uwbLast{axis}Confidence': confidence,
                        f'uwbLast{other}Raw': -0.5,
                        f'uwbLast{other}Confidence': None,
                    }))['uwbEvents'][0]
                    self.assertEqual(point['state'][f'uwbLast{axis}Confidence'], expected)
                    self.assertIsNone(point['state'][f'uwbLast{axis}Raw'])
                    self.assertEqual(point['state'][f'uwbLast{other}Raw'], -0.5)
                    self.assertIsNone(point['state'][f'uwbLast{other}Confidence'])
                    self.assertEqual(point['state']['uwbLastConfidence'], 1)
                    json.dumps(point, allow_nan=False)

    def test_uwb_capabilities_only_accept_booleans_without_inventing_angles(self):
        for supported in (True, False, None, 0, 1, 1.0, 'true', 'false',
                          float('nan'), float('inf'), [], {'private-capability': True}):
            with self.subTest(supported=supported):
                point = parse(uwb(uwbAzimuthSupported=supported, uwbElevationSupported=supported,
                    uwbLastAzimuthRaw=None, uwbLastElevationRaw=None,
                    uwbLastAzimuthConfidence=None, uwbLastElevationConfidence=None))['uwbEvents'][0]
                expected = supported if type(supported) is bool else None
                self.assertIs(point['state']['uwbAzimuthSupported'], expected)
                self.assertIs(point['state']['uwbElevationSupported'], expected)
                self.assertIsNone(point['state']['uwbLastAzimuthRaw'])
                self.assertIsNone(point['state']['uwbLastElevationRaw'])
                self.assertIsNone(point['state']['uwbLastAzimuthConfidence'])
                self.assertIsNone(point['state']['uwbLastElevationConfidence'])
                json.dumps(point, allow_nan=False)

    def test_uwb_next_distance_does_not_fill_missing_angles_from_previous_peer_or_sample(self):
        first = uwb()
        second = uwb(offset=1, uwbLastPeerIndex=1, uwbLastDistanceMeters=2.5,
            uwbLastFrameworkTimestampMs=999, uwbLastReceivedElapsedMs=1001,
            uwbLastAzimuthRaw=None, uwbLastAzimuthConfidence=None,
            uwbLastElevationRaw=None, uwbLastElevationConfidence=None)
        third = uwb(offset=2, uwbLastPeerIndex=1, uwbLastDistanceMeters=3.0,
            uwbLastFrameworkTimestampMs=1000, uwbLastReceivedElapsedMs=1002,
            uwbLastAzimuthRaw=None, uwbLastAzimuthConfidence=0,
            uwbLastElevationRaw=-0.75, uwbLastElevationConfidence=None)
        report = parse(first, second, third)
        self.assertEqual(report['issues'], [])
        missing = report['uwbEvents'][1]['state']
        self.assertEqual(missing['uwbLastPeerIndex'], 1)
        self.assertEqual(missing['uwbLastDistanceMeters'], 2.5)
        for key in ('uwbLastAzimuthRaw', 'uwbLastAzimuthConfidence',
                    'uwbLastElevationRaw', 'uwbLastElevationConfidence'):
            self.assertIsNone(missing[key])
        partial = report['uwbEvents'][2]['state']
        self.assertEqual(partial['uwbLastPeerIndex'], 1)
        self.assertEqual(partial['uwbLastDistanceMeters'], 3.0)
        self.assertIsNone(partial['uwbLastAzimuthRaw'])
        self.assertEqual(partial['uwbLastAzimuthConfidence'], 0)
        self.assertEqual(partial['uwbLastElevationRaw'], -0.75)
        self.assertIsNone(partial['uwbLastElevationConfidence'])
        self.assertTrue(all(point['newDistanceReceived'] for point in report['uwbEvents']))

    def test_uwb_integer_enums_counters_and_closed_sdk_reason_are_typed(self):
        for confidence in (0, 1, 2, None, -1, 3, True, 1.0, '2'):
            point = parse(uwb(uwbLastConfidence=confidence))['uwbEvents'][0]
            expected = confidence if type(confidence) is int and 0 <= confidence <= 2 else None
            self.assertEqual(point['state']['uwbLastConfidence'], expected)
        for stage in ('IDLE', 'PREPARING', 'AUTHENTICATING', 'WAITING_FOR_VEHICLE',
                      'STARTING', 'RANGING', 'CLEANING_UP', 'COMPLETE', 'CANCELED',
                      'FAILED', 'TIMED_OUT', 'BLOCKED', 'CLEANUP_FAILED'):
            self.assertEqual(parse(uwb(uwbStage=stage))['uwbEvents'][0]['state']['uwbStage'], stage)
        for value in (-1, True, 1.0, '2', 2**63):
            changes = {key: value for key in ('uwbPeerCount', 'uwbStartedPeerCount',
                'uwbValidDistanceCount', 'uwbInvalidResultCount', 'uwbLastPeerIndex')}
            safe = parse(uwb(**changes))['uwbEvents'][0]['state']
            self.assertTrue(all(safe[key] is None for key in changes))
        self.assertIsNone(parse(uwb(uwbBackendOpened=1))['uwbEvents'][0]['state']['uwbBackendOpened'])
        for key in ('uwbClosedReason', 'uwbOpenFailedReason'):
            for reason in (0, 5, 999, -99, None, True, 1.0, 'private-reason'):
                safe = parse(uwb(**{key: reason}))['uwbEvents'][0]['state']
                self.assertEqual(safe[key], reason if type(reason) is int else None)
        for reason in ('NO_DISTANCE_RECEIVED', 'UI_PAUSED', 'RANGING_PERMISSION_DENIED',
                       'NATIVE_OPEN_FAILED', 'GATT_CONNECT_FAILED', 'USER_CANCELED'):
            safe = parse(uwb(uwbReason=reason))['uwbEvents'][0]['state']
            self.assertEqual(safe['uwbReason'], reason)
        safe = parse(uwb(uwbClosedReason=5, uwbOpenFailedReason=2))['uwbEvents'][0]['state']
        self.assertEqual(safe['uwbClosedReason'], 5)
        self.assertEqual(safe['uwbOpenFailedReason'], 2)

    def test_uwb_preserves_unknown_framework_clock_without_wall_or_elapsed_projection(self):
        for timestamp in (0, -1, 9876543210123, BASE, 2**63-1):
            point = parse(uwb(uwbLastFrameworkTimestampMs=timestamp))['uwbEvents'][0]
            self.assertEqual(point['state']['uwbLastFrameworkTimestampMs'], timestamp)
            self.assertEqual(point['state']['uwbLastReceivedElapsedMs'], 990)
            self.assertEqual(point['recordWallMs'], BASE)
            self.assertEqual(point['recordElapsedMs'], 1000)
            self.assertNotIn('receivedProjectedWallMs', point)
        for received in (0, 1000, 1001, -1, True, '990', 990.0, None):
            point = parse(uwb(uwbLastReceivedElapsedMs=received))['uwbEvents'][0]
            valid = type(received) is int and 0 <= received <= 1000
            self.assertEqual(point['state']['uwbLastReceivedElapsedMs'], received if valid else None)
            self.assertEqual(point['newDistanceReceived'], valid)

    def test_uwb_session_open_and_peer_start_are_not_distance_measurements(self):
        rows = [record(version=24, trialId=None, event=event,
            wallMs=BASE+index, elapsedMs=1000+index,
            state=dict(uwbStage='STARTING', uwbBackendOpened=True, uwbPeerCount=2,
                uwbStartedPeerCount=1, uwbValidDistanceCount=0, uwbInvalidResultCount=0))
            for index, event in enumerate(('TESLA_UWB_STARTED', 'TESLA_UWB_BACKEND_OPENED',
                'TESLA_UWB_PEER_STARTED'))]
        report = parse(*rows)
        self.assertEqual(report['issues'], [])
        self.assertEqual(len(report['uwbEvents']), 3)
        for point in report['uwbEvents']:
            self.assertFalse(point['newDistanceReceived'])
            self.assertIsNone(point['state']['uwbLastDistanceMeters'])

    def test_initial_device_info_completion_cannot_become_distance_or_protocol_receipt(self):
        event = 'TESLA_UWB_INITIAL_DEVICE_INFO_SENT'
        for version in range(1, 28):
            self.assertEqual(parse(dict(uwb(event), version=version))['uwbEvents'], [])
        receipt = dict(uwb_rx_observation(uwbGattEvent='PROTOCOL', uwbRxProtocolCount=1,
            uwbRxStep='WAITING', uwbRxInputKind='ENCRYPTED_RESPONSE', uwbRxAead='VERIFIED',
            uwbRxDisposition='FIRA_UNRECOGNIZED'), version=28)
        completion = dict(uwb(event, offset=1), version=28)
        completion['state'].update(receipt['state'])
        report = parse(receipt, completion)
        self.assertEqual(report['issues'], [])
        self.assertEqual([point['event'] for point in report['uwbEvents']],
            ['TESLA_UWB_GATT_OBSERVATION', event])
        self.assertFalse(report['uwbEvents'][1]['newDistanceReceived'])
        self.assertFalse(report['uwbEvents'][1]['rxObservation']['newProtocol'])
        self.assertEqual(report['uwbRxOperations'][0]['observedEvents']['protocol'], 1)

    def test_uwb_repeated_past_snapshots_only_mark_distance_event_as_new(self):
        events = ('TESLA_UWB_DISTANCE_RECEIVED', 'TESLA_UWB_PEER_STARTED',
            'TESLA_UWB_BACKEND_OPENED', 'TESLA_UWB_CLEANING_UP', 'TESLA_UWB_COMPLETE',
            'TESLA_UWB_LOCAL_CLOSED', 'TESLA_UWB_CAPABILITIES_RECEIVED',
            'TESLA_UWB_PEER_STOPPED', 'TESLA_UWB_PEERS_ENDED', 'TESLA_UWB_ENDPOINT_CLOSING',
            'TESLA_UWB_BACKEND_CLOSED', 'TESLA_UWB_GATT_OBSERVATION',
            'TESLA_UWB_CAPABILITIES_UNSPECIFIED')
        report = parse(*(dict(uwb(event, index, uwbClosedReason=999), version=25)
            for index, event in enumerate(events)))
        self.assertEqual([point['newDistanceReceived'] for point in report['uwbEvents']],
            [True] + [False] * (len(events) - 1))
        self.assertTrue(all(point['state']['uwbLastDistanceMeters'] == 1.25
            for point in report['uwbEvents']))

    def test_v22_to_v25_keep_ble_counts_when_recognition_coexists(self):
        payload = {'candidates': [{'text': '취소', 'confidence': 0.9}],
            'policy': {'accepted': False, 'reason': 'FRUNK_VOICE_CANCELED'}}
        for version in (22, 23, 24, 25):
            report = parse(record(version=version, event='BLE_FIELD_TRIAL_FINISHED',
                bleTrial={'attempt': 1, 'status': 'COMPLETE', 'notificationCount': 0},
                bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1, 'receivedElapsedMs': 990}),
                record(version=version, trialId=None, event='FRUNK_VOICE_RECOGNITION_RESULT',
                    frunkVoiceRecognition=payload))
            self.assertEqual(report['issues'], [])
            self.assertEqual(report['trials'][0]['completeAttemptCount'], 1)
            self.assertEqual(report['trials'][0]['candidateTypedCount'], 1)
            self.assertEqual(report['uwbEvents'], [])

    def test_uwb_json_cli_exports_sanitized_typed_state_and_fixed_event_only(self):
        rows = [uwb(uwbLastDistanceMeters=float('inf'), uwbOpenFailedReason=999,
            uwbLastAzimuthRaw=float('nan'), uwbLastAzimuthConfidence='private-confidence',
            uwbLastElevationRaw='private-elevation', uwbLastElevationConfidence=3,
            uwbAzimuthSupported=1, uwbElevationSupported='private-capability',
            uwbReason='NO_DISTANCE_RECEIVED', uwbAddress='private-address'),
            dict(uwb(offset=1), event='TESLA_UWB_PRIVATE_VIN'),
            uwb(offset=2), uwb('TESLA_UWB_PEER_STOPPED', offset=3)]
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode('utf-8')
        out = io.StringIO()
        with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
            code = main(['synthetic-input.jsonl', '--json'])
        self.assertEqual(code, 0)
        report = json.loads(out.getvalue())
        self.assertEqual(len(report['uwbEvents']), 3)
        point = report['uwbEvents'][0]
        self.assertFalse(point['newDistanceReceived'])
        self.assertIsNone(point['state']['uwbLastDistanceMeters'])
        self.assertEqual(point['state']['uwbOpenFailedReason'], 999)
        self.assertEqual(point['state']['uwbReason'], 'NO_DISTANCE_RECEIVED')
        for key in ('uwbLastAzimuthRaw', 'uwbLastAzimuthConfidence',
                    'uwbLastElevationRaw', 'uwbLastElevationConfidence',
                    'uwbAzimuthSupported', 'uwbElevationSupported'):
            self.assertIsNone(point['state'][key])
        self.assertEqual([point['newDistanceReceived'] for point in report['uwbEvents']],
            [False, True, False])
        self.assertNotIn('private-', out.getvalue())
        self.assertNotIn('private-address', out.getvalue())
        self.assertNotIn('PRIVATE_VIN', out.getvalue())


    def test_v25_gatt_keeps_raw_status_admission_and_completion_as_independent_observations(self):
        for status in (133, 8, 0, -9876, -(2**31), 2**31-1):
            for accepted in (False, True, None):
                with self.subTest(status=status, accepted=accepted):
                    row = uwb_gatt(uwbGattConnectionStatus=status, uwbGattConnectionState=0,
                        uwbGattConnectAccepted=accepted, uwbGattDescriptorRequestStatus=201,
                        uwbGattDescriptorCallbackStatus=0)
                    point = parse(row)['uwbEvents'][0]
                    self.assertEqual(point['state']['uwbGattConnectionStatus'], status)
                    self.assertEqual(point['state']['uwbGattConnectionState'], 0)
                    self.assertEqual(point['state']['uwbGattDescriptorRequestStatus'], 201)
                    self.assertEqual(point['state']['uwbGattDescriptorCallbackStatus'], 0)
                    self.assertIs(point['state']['uwbGattConnectAccepted'], accepted)
                    self.assertIs(point['state']['uwbGattConnectedObserved'], False)
                    self.assertEqual(point['state']['uwbGattRxNotificationCount'], 0)
                    self.assertFalse(point['newDistanceReceived'])
                    self.assertEqual(point['gattTimingMs']['operationElapsedMs'], 800)
                    self.assertEqual(point['gattTimingMs']['stageElapsedMs'], 700)
                    self.assertEqual(point['gattTimingMs']['snapshotAgeMs'], 100)
                    self.assertEqual(point['gattTimingMs']['connectCallElapsedMs'], 10)
                    self.assertIsNone(point['gattTimingMs']['connectionCallbackSinceStartMs'])

    def test_v25_gatt_strict_booleans_and_int32_native_codes_preserve_unknown_values(self):
        for key in GATT_BOOLS:
            for value in (False, True, None, 0, 1, 0.0, 'false', [], {'private-value': True}):
                safe = parse(uwb_gatt(**{key: value}))['uwbEvents'][0]['state']
                self.assertIs(safe[key], value if type(value) is bool else None)
        for key in GATT_INTS:
            for value in (133, 8, 0, -1, 201, 999999, -(2**31), 2**31-1,
                          -(2**31)-1, 2**31, 2**63, True, False, 0.0, '133', None):
                safe = parse(uwb_gatt(**{key: value}))['uwbEvents'][0]['state']
                expected = value if type(value) is int and -(2**31) <= value < 2**31 else None
                self.assertEqual(safe[key], expected)

    def test_v25_gatt_times_reject_negative_future_and_wrong_types_without_filling_gaps(self):
        for key in GATT_TIMES:
            for value in (0, 1000, -1, 1001, 2**63-1, 2**63, True, False,
                          100.0, '100', None, ['private-time'], {'private-time': 1}):
                point = parse(uwb_gatt(**{key: value}))['uwbEvents'][0]
                expected = value if type(value) is int and 0 <= value <= 1000 else None
                self.assertEqual(point['state'][key], expected)
                self.assertNotIn('private-time', json.dumps(point, allow_nan=False))
        for count in (0, 1001, 2**63-1, -1, 2**63, True, False, 0.0, '0', None):
            safe = parse(uwb_gatt(uwbGattRxNotificationCount=count))['uwbEvents'][0]['state']
            expected = count if type(count) is int and 0 <= count < 2**63 else None
            self.assertEqual(safe['uwbGattRxNotificationCount'], expected)
        point = parse(uwb_gatt(uwbGattConnectReturnedElapsedMs=199,
            uwbGattStageStartedElapsedMs=950, uwbGattOperationStartedElapsedMs=None))['uwbEvents'][0]
        self.assertIsNone(point['gattTimingMs']['connectCallElapsedMs'])
        self.assertIsNone(point['gattTimingMs']['stageElapsedMs'])
        self.assertIsNone(point['gattTimingMs']['operationElapsedMs'])

    def test_v25_gatt_enum_allowlists_and_legacy_absence_never_invent_measurements(self):
        cases = (
            ('uwbGattStage', ('IDLE', 'PREPARING_KEY', 'CONNECTING', 'DISCOVERING', 'NEGOTIATING_MTU',
                'SUBSCRIBING', 'WAITING_FOR_CARD', 'HANDSHAKING', 'READING_STATUS', 'CLEANING_UP',
                'COMPLETE', 'BLOCKED', 'CANCELED', 'TIMED_OUT', 'FAILED', 'CLEANUP_FAILED')),
            ('uwbGattEvent', ('STAGE', 'CONNECT_RESULT', 'CONNECTION_CALLBACK', 'DESCRIPTOR_CALLBACK',
                'RX', 'WRITE', 'RETRY_SCHEDULED', 'RETRY_STARTED', 'RETRY_CANCELED',
                'LOCAL_CLOSED', 'CLEANUP_FAILED')),
            ('uwbGattConnectAction', ('CREATE', 'RECONNECT')),
        )
        for key, values in cases:
            for value in (*values, None, False, 0, 'private-value', ['CREATE']):
                report = parse(uwb_gatt(**{key: value}))
                expected = value if type(value) is str and value in values else None
                self.assertEqual(report['uwbEvents'][0]['state'][key], expected)
                self.assertFalse(report['uwbEvents'][0]['newDistanceReceived'])
                self.assertNotIn('private-value', json.dumps(report, allow_nan=False))
        for version in range(1, 25):
            row = dict(uwb_gatt('TESLA_UWB_CAPABILITIES_RECEIVED'), version=version)
            report = parse(row)
            if version < 23:
                self.assertEqual(report['uwbEvents'], [])
            else:
                safe = report['uwbEvents'][0]['state']
                self.assertTrue(all(safe[key] is None for key in GATT_KEYS))
            row['event'] = 'TESLA_UWB_GATT_OBSERVATION'
            self.assertEqual(parse(row)['uwbEvents'], [])
        first = uwb_gatt(uwbGattConnectedObserved=True, uwbGattConnectionStatus=0,
            uwbGattConnectionCallbackElapsedMs=500, uwbGattFirstRxElapsedMs=600)
        missing = dict(uwb(offset=1), version=25)
        for key in GATT_KEYS:
            del missing['state'][key]
        report = parse(first, missing)
        self.assertEqual(report['uwbEvents'][0]['gattTimingMs']['connectionCallbackSinceStartMs'], 400)
        self.assertEqual(report['uwbEvents'][0]['gattTimingMs']['firstRxSinceStartMs'], 500)
        self.assertTrue(all(report['uwbEvents'][1]['state'][key] is None for key in GATT_KEYS))
        self.assertTrue(all(value is None for value in report['uwbEvents'][1]['gattTimingMs'].values()))

    def test_v25_gatt_cli_json_and_text_retain_false_null_zero_status_and_relative_timing(self):
        rows = [uwb_gatt(offset=index, uwbGattConnectionStatus=status, uwbGattConnectAccepted=accepted)
            for index, (status, accepted) in enumerate(((133, False), (8, True), (0, None)))]
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode('utf-8')
        for args in (['synthetic-input.jsonl'], ['synthetic-input.jsonl', '--json']):
            out = io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                code = main(args)
            self.assertEqual(code, 0)
            if '--json' in args:
                points = json.loads(out.getvalue())['uwbEvents']
                observations = [point['state'] for point in points]
                self.assertTrue(all(not point['newDistanceReceived'] for point in points))
                timings = [point['gattTimingMs'] for point in points]
            else:
                objects = [json.loads(line[line.index('{'):])
                    for line in out.getvalue().splitlines() if '{' in line]
                observations = [value for value in objects if 'uwbGattStage' in value]
                timings = [value for value in objects if 'operationElapsedMs' in value]
            self.assertEqual([state['uwbGattConnectionStatus'] for state in observations], [133, 8, 0])
            self.assertEqual([state['uwbGattConnectAccepted'] for state in observations], [False, True, None])
            self.assertTrue(all(state['uwbGattConnectedObserved'] is False for state in observations))
            self.assertEqual([timing['snapshotAgeMs'] for timing in timings], [100, 101, 102])
            self.assertEqual([timing['connectCallElapsedMs'] for timing in timings], [10, 10, 10])

    def test_v26_rx_strict_types_ranges_and_private_keys(self):
        secret = 'PRIVATE-RAW-KEY-STS-VIN-MAC-UUID'
        groups = (
            (RX_BOOLS, (False, True, None, 0, 1, 0.0, 'false', secret, [], {})),
            (RX_INTS, (0, -1, -(2**31), 2**31-1, -(2**31)-1, 2**31,
                True, False, '0', 0.0, float('nan'), float('inf'), secret)),
            (RX_COUNTS, (0, 2**63-1, -1, 2**63, True, False, '0', 0.0, float('nan'), float('inf'), secret)),
            (RX_TIMES, (0, 1000, -1, 1001, True, False, '0', 0.0, float('nan'), float('inf'), secret)),
            (('uwbRxNotificationFrameCount',), (0, 16, -1, 17, True, False, '0', 0.0, float('nan'), float('inf'), secret)),
        )
        for keys, values in groups:
            for key in keys:
                for value in values:
                    row = uwb_rx(**{key: value, 'rawPayload': secret, 'key': secret,
                        'sts': secret, 'vin': secret, 'mac': secret, 'uuid': secret})
                    report = parse(row)
                    safe = report['uwbEvents'][0]['state']
                    if key in RX_BOOLS:
                        expected = value if type(value) is bool else None
                    else:
                        lower = -(2**31) if key in RX_INTS else 0
                        upper = (2**31-1 if key in RX_INTS else 2**63-1 if key in RX_COUNTS
                                 else 1000 if key in RX_TIMES else 16)
                        expected = value if type(value) is int and lower <= value <= upper else None
                    self.assertEqual(safe[key], expected, (key, value))
                    self.assertNotIn(secret, json.dumps(report, allow_nan=False))
        for key, values in RX_ENUMS.items():
            for value in (*values, None, 0, False, secret, [], {}):
                safe = parse(uwb_rx(**{key: value}))['uwbEvents'][0]['state']
                self.assertEqual(safe[key], value if type(value) is str and value in values else None)

    def test_v26_only_protocol_and_rx_metadata_do_not_backfill_legacy_or_next_operation(self):
        for version in range(23, 26):
            row = dict(uwb_rx('TESLA_UWB_CAPABILITIES_RECEIVED',
                uwbGattEvent='PROTOCOL', **{key: 0 for key in RX_KEYS}), version=version)
            report = parse(row)
            safe = report['uwbEvents'][0]['state']
            self.assertTrue(all(safe[key] is None for key in RX_KEYS))
            self.assertIsNone(safe['uwbGattEvent'])
            self.assertFalse(report['uwbEvents'][0]['rxObservation']['newProtocol'])
            gatt_report = parse(dict(row, event='TESLA_UWB_GATT_OBSERVATION'))
            if version < 25:
                self.assertEqual(gatt_report['uwbEvents'], [])
            else:
                self.assertIsNone(gatt_report['uwbEvents'][0]['state']['uwbGattEvent'])
                self.assertTrue(all(gatt_report['uwbEvents'][0]['state'][key] is None for key in RX_KEYS))
        row = uwb_rx(uwbGattEvent='PROTOCOL', uwbRxProtocolCount=1,
            uwbRxStep='UWB_INPUT', uwbRxInputKind='RAW_FIRA', uwbRxAead='NOT_ATTEMPTED',
            uwbRxFiraKind='REQUEST', uwbRxDisposition='FIRA_REQUEST')
        missing = uwb_rx(offset=1, uwbGattEvent='STAGE', uwbGattOperationStartedElapsedMs=1001,
            uwbGattRxNotificationCount=0, uwbRxFrameCount=0, uwbRxProtocolCount=0)
        report = parse(row, missing)
        points = report['uwbEvents']
        self.assertEqual(points[0]['state']['uwbGattEvent'], 'PROTOCOL')
        self.assertTrue(points[0]['rxObservation']['newProtocol'])
        self.assertIsNone(points[1]['state']['uwbRxFiraKind'])
        self.assertFalse(points[1]['rxObservation']['newProtocol'])
        self.assertTrue(all(points[1]['state'][key] is None for key in RX_KEYS if key not in RX_COUNTS))
        self.assertEqual(report['uwbRxOperations'][1]['counterTotals'],
            {'notification': 0, 'frame': 0, 'protocol': 0})
        self.assertEqual(report['uwbRxOperations'][1]['observedEvents'],
            {'notification': 0, 'frame': None, 'protocol': 0})
        self.assertEqual(report['uwbRxOperations'][1]['counterResetIndex'], 0)
        self.assertNotEqual(points[0]['rxObservation']['operationIndex'],
            points[1]['rxObservation']['operationIndex'])
        self.assertTrue(all(not point['newDistanceReceived'] for point in points))

    def test_rx_notification_frame_protocol_denominators_and_last_snapshot_deduplication(self):
        rows = [
            uwb_rx(uwbGattEvent='RX', uwbGattRxNotificationCount=1, uwbRxFrameCount=0,
                uwbRxProtocolCount=0, uwbRxNotificationFrameCount=0,
                uwbRxDecoderIncomplete=True, uwbRxDecoderFailed=False),
            uwb_rx(offset=1, uwbGattEvent='RX', uwbGattRxNotificationCount=2,
                uwbRxFrameCount=2, uwbRxProtocolCount=0, uwbRxNotificationFrameCount=2,
                uwbRxDecoderIncomplete=False, uwbRxDecoderFailed=False),
        ]
        protocol = uwb_rx(offset=2, uwbGattEvent='PROTOCOL', uwbGattRxNotificationCount=2,
            uwbRxFrameCount=2, uwbRxProtocolCount=1, uwbRxNotificationFrameCount=2,
            uwbRxProtocolReceivedElapsedMs=700, uwbRxProtocolObservedElapsedMs=950,
            uwbRxStep='UWB_INPUT', uwbRxInputKind='RAW_FIRA', uwbRxAead='NOT_ATTEMPTED',
            uwbRxFiraKind='REQUEST', uwbRxDisposition='FIRA_REQUEST')
        rows += [protocol, dict(protocol, wallMs=BASE+3, elapsedMs=1003),
            dict(protocol, event='TESLA_UWB_AUTHENTICATED', wallMs=BASE+4, elapsedMs=1004),
            dict(protocol, event='TESLA_UWB_DISTANCE_RECEIVED', wallMs=BASE+5, elapsedMs=1005),
            dict(protocol, event='TESLA_UWB_COMPLETE', wallMs=BASE+6, elapsedMs=1006)]
        second = uwb_rx(offset=7, uwbGattEvent='PROTOCOL', uwbGattRxNotificationCount=2,
            uwbRxFrameCount=2, uwbRxProtocolCount=2, uwbRxStep='WAITING',
            uwbRxInputKind='ENCRYPTED_RESPONSE', uwbRxAead='VERIFIED',
            uwbRxFiraKind='UNRECOGNIZED', uwbRxDisposition='FIRA_UNRECOGNIZED',
            uwbRxProtocolReceivedElapsedMs=900, uwbRxProtocolObservedElapsedMs=899)
        rows.append(second)
        report = parse(*rows)
        op, = report['uwbRxOperations']
        self.assertEqual(op['counterTotals'], {'notification': 2, 'frame': 2, 'protocol': 2})
        self.assertEqual(op['observedEvents'], {'notification': 2, 'frame': 2, 'protocol': 2})
        self.assertEqual(op['classifications']['uwbRxFiraKind'], {'REQUEST': 1, 'UNRECOGNIZED': 1})
        self.assertEqual(op['classifications']['uwbRxAead'], {'NOT_ATTEMPTED': 1, 'VERIFIED': 1})
        self.assertEqual(op['decoder']['uwbRxDecoderIncomplete'], {'true': 1, 'false': 1})
        self.assertEqual([p['rxObservation']['newProtocol'] for p in report['uwbEvents']],
            [False, False, True, False, False, False, False, True])
        self.assertEqual([p['newDistanceReceived'] for p in report['uwbEvents']],
            [False, False, False, False, False, True, False, False])
        self.assertEqual(report['uwbEvents'][2]['gattTimingMs']['protocolQueueDelayMs'], 250)
        self.assertIsNone(report['uwbEvents'][-1]['gattTimingMs']['protocolQueueDelayMs'])

    def test_rx_snapshot_only_totals_operation_and_counter_reset_are_separate_scopes(self):
        snapshot = uwb_rx('TESLA_UWB_COMPLETE', uwbGattEvent='PROTOCOL',
            uwbGattRxNotificationCount=12, uwbRxFrameCount=4, uwbRxProtocolCount=3,
            uwbRxDisposition='FIRA_REQUEST', uwbRxFiraKind='REQUEST')
        reset = uwb_rx(offset=1, uwbGattEvent='RX', uwbGattRxNotificationCount=1,
            uwbRxFrameCount=0, uwbRxProtocolCount=0, uwbRxNotificationFrameCount=0)
        next_op = uwb_rx(offset=2, uwbGattOperationStartedElapsedMs=1002,
            uwbGattRxNotificationCount=0, uwbRxFrameCount=0, uwbRxProtocolCount=0)
        next_process = dict(next_op, processId=Q, wallMs=BASE+3, elapsedMs=1003)
        report = parse(snapshot, reset, next_op, next_process)
        ops = report['uwbRxOperations']
        self.assertEqual(len(ops), 4)
        self.assertEqual(ops[0]['counterTotals'], {'notification': 12, 'frame': 4, 'protocol': 3})
        self.assertEqual(ops[0]['observedEvents'], {'notification': 0, 'frame': None, 'protocol': 0})
        self.assertEqual(ops[0]['classifications']['uwbRxFiraKind'], {})
        self.assertEqual(ops[1]['counterResetIndex'], 1)
        self.assertTrue(ops[1]['counterResetObserved'])
        self.assertEqual(ops[1]['observedEvents'], {'notification': 1, 'frame': 0, 'protocol': 0})
        self.assertEqual(ops[2]['counterResetIndex'], 0)
        self.assertEqual(ops[3]['processId'], Q)

    def test_rx_missing_decoder_count_stays_unobserved_instead_of_zero(self):
        rows = [
            uwb_rx(uwbGattEvent='RX', uwbGattRxNotificationCount=1,
                uwbRxFrameCount=0, uwbRxProtocolCount=0),
            uwb_rx(offset=1, uwbGattEvent='RX', uwbGattRxNotificationCount=2,
                uwbRxFrameCount=0, uwbRxProtocolCount=0, uwbRxNotificationFrameCount=0,
                uwbRxDecoderIncomplete=False, uwbRxDecoderFailed=True),
        ]
        report = parse(*rows)
        operation, = report['uwbRxOperations']
        self.assertEqual(operation['observedEvents'], {'notification': 2, 'frame': 0, 'protocol': 0})
        self.assertEqual(operation['notificationFrameCountUnobserved'], 1)
        self.assertEqual(operation['decoder']['uwbRxDecoderFailed'], {'unobserved': 1, 'true': 1})
        self.assertIsNone(report['uwbEvents'][0]['rxObservation']['newFrameCount'])
        self.assertEqual(report['uwbEvents'][1]['rxObservation']['newFrameCount'], 0)

    def test_v26_cli_rx_json_and_text_expose_data_not_success(self):
        row = uwb_rx(uwbGattEvent='PROTOCOL', uwbGattRxNotificationCount=5,
            uwbRxFrameCount=2, uwbRxProtocolCount=1, uwbRxStep='WAITING',
            uwbRxInputKind='ROUTABLE_MESSAGE', uwbRxAuthEvidence='HMAC_MISMATCH',
            uwbRxDisposition='DOMAIN_MISMATCH', uwbRxDomainMismatchReason='DOMAIN_DIFFERENT',
            uwbRxExpectedDomain=0, uwbRxFromDomain=-1, uwbRxFromPresent=False,
            uwbRxSignedStatusPresent=True, uwbRxSignedMessageFault=0, uwbRxOperationStatus=0,
            uwbRxProtocolReceivedElapsedMs=800, uwbRxProtocolObservedElapsedMs=900)
        payload = (json.dumps(row)+'\n').encode()
        for args in (['synthetic-input.jsonl'], ['synthetic-input.jsonl', '--json']):
            out = io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                code = main(args)
            self.assertEqual(code, 0)
            if '--json' in args:
                report = json.loads(out.getvalue())
                op, = report['uwbRxOperations']
                self.assertFalse(report['uwbEvents'][0]['newDistanceReceived'])
            else:
                objects = [json.loads(line[line.index('{'):])
                    for line in out.getvalue().splitlines() if '{' in line]
                op, = [value for value in objects if 'counterTotals' in value]
                metadata, = [value for value in objects if 'uwbRxStep' in value]
                self.assertEqual(metadata['uwbRxSignedMessageFault'], 0)
                self.assertIs(metadata['uwbRxFromPresent'], False)
            self.assertEqual(op['counterTotals'], {'notification': 5, 'frame': 2, 'protocol': 1})
            self.assertEqual(op['observedEvents']['notification'], 0)
            self.assertEqual(op['classifications']['uwbRxAuthEvidence'], {'HMAC_MISMATCH': 1})
            self.assertEqual(op['classifications']['uwbRxDomainMismatchReason'], {'DOMAIN_DIFFERENT': 1})

    def test_v27_body_numeric_shapes_empty_and_nonminimal_encoding_are_preserved(self):
        cases = (
            (0, 'COMPLETE', ''),
            (2, 'COMPLETE', '1/0/1'),
            (11, 'COMPLETE', '1/0/10'),
            (9, 'COMPLETE', '2/1/8'),
            (2, 'COMPLETE', '3/2/0'),
            (4096, 'COMPLETE', '536870911/2/4089'),
            (5, 'COMPLETE', '4/5/4'),
            (24, 'COMPLETE', '1/0/1,2/1/8,3/2/0,4/5/4'),
            (18, 'TRUNCATED', ','.join(['1/0/1'] * 8)),
            (3, 'MALFORMED', '1/0/1'),
            (1, 'UNSUPPORTED', ''),
            # shape에는 원 tag/length varint의 비최소 encoding 길이가 없다.
            (4, 'COMPLETE', '1/2/0'),
        )
        for byte_count, status, fields in cases:
            with self.subTest(byte_count=byte_count, status=status, fields=fields):
                row = uwb_rx_observation(uwbRxBodyBytes=byte_count,
                    uwbRxBodyStatus=status, uwbRxBodyFields=fields)
                safe = parse(row)['uwbEvents'][0]['state']
                self.assertEqual({key: safe[key] for key in RX_BODY_KEYS},
                    dict(zip(RX_BODY_KEYS, (byte_count, status, fields))))

    def test_v27_body_partial_or_invalid_group_is_atomically_null_without_private_text(self):
        secret = 'SYNTHETIC-PRIVATE-BODY-EXCEPTION-UUID-STS'
        valid = dict(uwbRxBodyBytes=2, uwbRxBodyStatus='COMPLETE', uwbRxBodyFields='1/0/1')
        mutations = []
        for key in RX_BODY_KEYS:
            mutations += [{key: None}, {key: secret}, {key: True}, {key: []}, {key: {}}]
        mutations += [{'uwbRxBodyBytes': value} for value in (-1, 4097, 2.0, '2')]
        mutations += [{'uwbRxBodyStatus': value} for value in ('complete', '', 0)]
        mutations += [{'uwbRxBodyFields': value} for value in (
            '0/0/1', '536870912/0/1', '01/0/1', '1/00/1', '1/0/01', '+1/0/1',
            '-1/0/1', ' 1/0/1', '1/0/1 ', '１/0/1', '1/0/١', '1/0/1\n',
            '1/0/0', '1/0/11', '1/1/7', '1/1/9', '1/2/4097', '1/5/3', '1/5/5',
            '1/3/0', '1/4/0', '1/6/0', '1/7/0', '1/0/1,', ',1/0/1',
            '1/0/1,,2/0/1', '1//1', '1/0/1/2', ','.join(['1/0/1'] * 9),
            '1/2/0' * 136, secret)]
        mutations += [
            {'uwbRxBodyBytes': 0},
            {'uwbRxBodyFields': ''},
            {'uwbRxBodyBytes': 1, 'uwbRxBodyFields': '1/2/0'},
            {'uwbRxBodyBytes': 9, 'uwbRxBodyFields': '536870911/1/8'},
            {'uwbRxBodyStatus': 'TRUNCATED'},
            {'uwbRxBodyStatus': 'MALFORMED'},
            {'uwbRxBodyStatus': 'UNSUPPORTED'},
            {'uwbRxBodyBytes': 0, 'uwbRxBodyStatus': 'MALFORMED', 'uwbRxBodyFields': ''},
            {'uwbRxBodyBytes': 16, 'uwbRxBodyStatus': 'TRUNCATED',
             'uwbRxBodyFields': ','.join(['1/0/1'] * 8)},
            {'uwbRxBodyBytes': 17, 'uwbRxBodyStatus': 'MALFORMED',
             'uwbRxBodyFields': ','.join(['1/0/1'] * 8)},
            {'uwbRxBodyBytes': 17, 'uwbRxBodyStatus': 'UNSUPPORTED',
             'uwbRxBodyFields': ','.join(['1/0/1'] * 8)},
        ]
        for changes in mutations:
            with self.subTest(changes=changes):
                row = uwb_rx_observation(**dict(valid, **changes),
                    uwbRxRequestCorrelation='CURRENT', rawPayload=secret, exception=secret)
                report = parse(row)
                state = report['uwbEvents'][0]['state']
                self.assertTrue(all(state[key] is None for key in RX_BODY_KEYS))
                self.assertEqual(state['uwbRxRequestCorrelation'], 'CURRENT')
                self.assertNotIn(secret, json.dumps(report, allow_nan=False))
        for missing in RX_BODY_KEYS:
            value = {key: item for key, item in valid.items() if key != missing}
            state = uwb_rx_state(value, 1000, 27)
            self.assertTrue(all(state[key] is None for key in RX_BODY_KEYS))

    def test_v27_correlation_is_independent_strict_and_legacy_never_backfills(self):
        valid = dict(uwbRxBodyBytes=0, uwbRxBodyStatus='COMPLETE', uwbRxBodyFields='',
            uwbRxRequestCorrelation='PREVIOUS')
        for version in range(1, 27):
            safe = uwb_rx_state(valid, 1000, version)
            self.assertTrue(all(safe[key] is None for key in RX_OBSERVATION_KEYS), version)
        for value in ('CURRENT', 'PREVIOUS', 'UNMATCHED', None, 'current', '', 'PRIVATE-UUID',
                      0, True, [], {}):
            state = parse(uwb_rx_observation(**dict(valid,
                uwbRxRequestCorrelation=value)))['uwbEvents'][0]['state']
            self.assertEqual(state['uwbRxRequestCorrelation'],
                value if type(value) is str and value in ('CURRENT', 'PREVIOUS', 'UNMATCHED') else None)
            self.assertEqual(state['uwbRxBodyBytes'], 0)
            self.assertEqual(state['uwbRxBodyFields'], '')

    def test_v28_preserves_v27_primary_protocol_body_and_correlation_summary(self):
        for byte_count, status, fields, correlation in (
            (0, 'COMPLETE', '', 'CURRENT'),
            (2, 'COMPLETE', '1/0/1', 'PREVIOUS'),
            (3, 'MALFORMED', '1/0/1', 'UNMATCHED'),
            (18, 'TRUNCATED', ','.join(['1/0/1'] * 8), 'CURRENT'),
        ):
            with self.subTest(status=status, fields=fields, correlation=correlation):
                protocol = uwb_rx_observation(uwbGattEvent='PROTOCOL',
                    uwbGattRxNotificationCount=2, uwbRxFrameCount=1, uwbRxProtocolCount=1,
                    uwbRxBodyBytes=byte_count, uwbRxBodyStatus=status,
                    uwbRxBodyFields=fields, uwbRxRequestCorrelation=correlation)
                old = parse(protocol)
                new = parse(dict(protocol, version=28))
                expected = dict(zip(RX_OBSERVATION_KEYS, (byte_count, status, fields, correlation)))
                self.assertEqual({key: new['uwbEvents'][0]['state'][key]
                    for key in RX_OBSERVATION_KEYS}, expected)
                self.assertEqual(new['uwbEvents'], old['uwbEvents'])
                self.assertEqual(new['uwbRxOperations'], old['uwbRxOperations'])
                self.assertEqual(new['navigationEvents'], [])
                self.assertEqual(new['issues'], [])

    def test_v27_shape_and_correlation_count_only_distinct_new_protocol_events(self):
        protocol = uwb_rx_observation(uwbGattEvent='PROTOCOL', uwbGattRxNotificationCount=2,
            uwbRxFrameCount=1, uwbRxProtocolCount=1, uwbRxStep='WAITING',
            uwbRxFiraKind='UNRECOGNIZED', uwbRxBodyBytes=0,
            uwbRxBodyStatus='COMPLETE', uwbRxBodyFields='', uwbRxRequestCorrelation='CURRENT')
        duplicate = dict(protocol, wallMs=BASE+1, elapsedMs=1001)
        rx = dict(protocol, wallMs=BASE+2, elapsedMs=1002,
            state=dict(protocol['state'], uwbGattEvent='RX', uwbGattRxNotificationCount=3,
                uwbRxNotificationFrameCount=0))
        # terminal은 마지막 RX의 누적 counter를 보존하며 이전 PROTOCOL snapshot으로 되돌리지 않는다.
        terminal = dict(rx, event='TESLA_UWB_COMPLETE', wallMs=BASE+3, elapsedMs=1003)
        previous = uwb_rx_observation(offset=4, uwbGattEvent='PROTOCOL',
            uwbGattRxNotificationCount=3, uwbRxFrameCount=2, uwbRxProtocolCount=2,
            uwbRxRequestCorrelation='PREVIOUS')
        missing = uwb_rx_observation(offset=5, uwbGattEvent='PROTOCOL',
            uwbGattRxNotificationCount=3, uwbRxFrameCount=3, uwbRxProtocolCount=3)
        report = parse(protocol, duplicate, rx, terminal, previous, missing)
        operation, = report['uwbRxOperations']
        self.assertFalse(operation['counterResetObserved'])
        self.assertEqual(operation['counterResetIndex'], 0)
        self.assertEqual(operation['counterTotals'], {'notification': 3, 'frame': 3, 'protocol': 3})
        self.assertEqual(operation['observedEvents'], {'notification': 1, 'frame': 0, 'protocol': 3})
        self.assertEqual(operation['classifications']['uwbRxBodyStatus'],
            {'COMPLETE': 1, 'unobserved': 2})
        self.assertEqual(operation['classifications']['uwbRxRequestCorrelation'],
            {'CURRENT': 1, 'PREVIOUS': 1, 'unobserved': 1})
        self.assertEqual([point['rxObservation']['newProtocol'] for point in report['uwbEvents']],
            [True, False, False, False, True, True])
        self.assertTrue(all(not point['newDistanceReceived'] for point in report['uwbEvents']))
        self.assertTrue(all(report['uwbEvents'][-1]['state'][key] is None
            for key in RX_OBSERVATION_KEYS))
        snapshot_only = parse(terminal)['uwbRxOperations'][0]
        self.assertEqual(snapshot_only['classifications']['uwbRxBodyStatus'], {})
        self.assertEqual(snapshot_only['classifications']['uwbRxRequestCorrelation'], {})

    def test_v27_observation_counter_resets_and_new_operations_do_not_share_counts(self):
        def point(offset, started, count, status):
            return uwb_rx_observation(offset=offset, uwbGattEvent='PROTOCOL',
                uwbGattOperationStartedElapsedMs=started, uwbGattRxNotificationCount=count,
                uwbRxFrameCount=count, uwbRxProtocolCount=count, uwbRxBodyBytes=3,
                uwbRxBodyStatus=status, uwbRxBodyFields='1/0/1',
                uwbRxRequestCorrelation='UNMATCHED')
        report = parse(point(0, 100, 2, 'COMPLETE'), point(1, 100, 1, 'MALFORMED'),
            point(2, 1002, 1, 'UNSUPPORTED'))
        operations = report['uwbRxOperations']
        self.assertEqual(len(operations), 3)
        self.assertEqual([op['counterResetIndex'] for op in operations], [0, 1, 0])
        self.assertEqual([op['classifications']['uwbRxBodyStatus'] for op in operations],
            [{'COMPLETE': 1}, {'MALFORMED': 1}, {'UNSUPPORTED': 1}])

    def test_v27_cli_outputs_only_sanitized_numeric_body_fields(self):
        rows = [
            uwb_rx_observation(uwbGattEvent='PROTOCOL', uwbRxProtocolCount=1,
                uwbRxBodyBytes=0, uwbRxBodyStatus='COMPLETE', uwbRxBodyFields='',
                uwbRxRequestCorrelation='CURRENT'),
            uwb_rx_observation(offset=1, uwbGattEvent='PROTOCOL', uwbRxProtocolCount=2,
                uwbRxBodyBytes=20, uwbRxBodyStatus='MALFORMED',
                uwbRxBodyFields='SYNTHETIC-PRIVATE-EXCEPTION', uwbRxRequestCorrelation='PREVIOUS'),
        ]
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode()
        for args in (['synthetic-input.jsonl'], ['synthetic-input.jsonl', '--json']):
            out = io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                self.assertEqual(main(args), 0)
            self.assertNotIn('SYNTHETIC-PRIVATE-EXCEPTION', out.getvalue())
            if '--json' in args:
                report = json.loads(out.getvalue())
                metadata = [point['state'] for point in report['uwbEvents']]
                operation, = report['uwbRxOperations']
            else:
                objects = [json.loads(line[line.index('{'):])
                    for line in out.getvalue().splitlines() if '{' in line]
                metadata = [value for value in objects if 'uwbRxBodyFields' in value]
                operation, = [value for value in objects if 'counterTotals' in value]
            self.assertEqual(metadata[0]['uwbRxBodyFields'], '')
            self.assertTrue(all(metadata[1][key] is None for key in RX_BODY_KEYS))
            self.assertEqual(operation['classifications']['uwbRxBodyStatus'],
                {'COMPLETE': 1, 'unobserved': 1})

    def test_v27_keeps_existing_approach_recovery_interpretation(self):
        report = parse(*(dict(row, version=27) for row in recovery_rows()))
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['trials'][0]['approachRecoveryReadCount'], 1)

    def test_v22_recognition_keeps_trial_confidence_and_block_reason_without_claiming_open(self):
        for trial in (T, None):
            for accepted, reason, score in (
                (True, None, 0.9),
                (False, 'FRUNK_VOICE_CONFIDENCE_BLOCKED', 0.0),
                (False, 'FRUNK_VOICE_CANCELED', None),
            ):
                payload = {'candidates': [{'text': '취소' if reason == 'FRUNK_VOICE_CANCELED' else
                    '헤이 테슬라 프렁크 열어줘', 'confidence': score, 'masked': False, 'truncated': False}],
                    'candidatesTruncated': False, 'policy': {'accepted': accepted, 'reason': reason}}
                report = parse(record(version=22, trialId=trial, event='FRUNK_VOICE_RECOGNITION_RESULT',
                    frunkVoiceRecognition=payload))
                point = report['frunkEvents'][0]
                self.assertEqual(point['trialId'], trial)
                self.assertEqual(point['frunkVoiceRecognition'], payload)
                self.assertIsNone(point['state']['frunkCommandSent'])
                self.assertIsNone(point['state']['frunkOpenObserved'])
                self.assertEqual(report['issues'], [])

    def test_v22_recognition_masks_direct_log_payload_and_bounds_alternatives(self):
        payload = {'candidates': [
            {'text': '헤이 테슬라 가상사용자 프렁크 열어줘', 'confidence': 0.9, 'masked': False},
            {'text': '테슬라@example.invalid 프렁크123', 'confidence': float('nan')},
            {'text': '010-0000-0000', 'confidence': True},
            {'text': 'SYNTHETIC-EXCESS-ID', 'confidence': 1},
        ], 'policy': {'accepted': False, 'reason': 'SYNTHETIC-PRIVATE-REASON'}}
        report = parse(record(version=22, event='FRUNK_VOICE_RECOGNITION_RESULT',
            frunkVoiceRecognition=payload))
        safe = report['frunkEvents'][0]['frunkVoiceRecognition']
        self.assertEqual(safe['candidates'], [
            {'text': '헤이 테슬라 [마스킹] 프렁크 열어줘', 'confidence': 0.9, 'masked': True, 'truncated': False},
            {'text': '[마스킹] [마스킹]', 'confidence': None, 'masked': True, 'truncated': False},
            {'text': '[마스킹]', 'confidence': None, 'masked': True, 'truncated': False},
        ])
        self.assertTrue(safe['candidatesTruncated'])
        self.assertEqual(safe['policy'], {'accepted': False, 'reason': None})
        encoded = json.dumps(report, ensure_ascii=False, allow_nan=False)
        for secret in ('가상사용자', '테슬라@example.invalid', '프렁크123', '010-0000-0000',
                       'SYNTHETIC-EXCESS-ID', 'SYNTHETIC-PRIVATE-REASON'):
            self.assertNotIn(secret, encoded)

    def test_v22_recognition_checks_entire_tokens_and_preserves_mask_flags(self):
        for token in ('김프렁크', '헤이테슬라프렁크열어줘개인', 'token=프렁크',
                      '프렁크\u200b열어줘', '열어줘.', '[프렁크]', '프렁크,가상사용자'):
            payload = {'candidates': [{'text': token, 'confidence': 0.5}],
                'policy': {'accepted': False, 'reason': 'FRUNK_VOICE_GRAMMAR_BLOCKED'}}
            safe = parse(record(version=22, event='FRUNK_VOICE_RECOGNITION_RESULT',
                frunkVoiceRecognition=payload))['frunkEvents'][0]['frunkVoiceRecognition']
            self.assertEqual(safe['candidates'][0]['text'], '[마스킹]')
            self.assertTrue(safe['candidates'][0]['masked'])
        payload = {'candidates': [{'text': '[마스킹]', 'confidence': None, 'masked': True, 'truncated': True}],
            'candidatesTruncated': True, 'policy': {'accepted': False, 'reason': 'FRUNK_VOICE_GRAMMAR_BLOCKED'}}
        safe = parse(record(version=22, event='FRUNK_VOICE_RECOGNITION_RESULT',
            frunkVoiceRecognition=payload))['frunkEvents'][0]['frunkVoiceRecognition']
        self.assertEqual(safe, payload)

    def test_v22_recognition_bounds_input_and_mask_expansion_without_prefix_leak(self):
        for text, expected, truncated in (
            ('안 ' * 80, ('안 ' * 80).rstrip(), False),
            ('프렁크' + '가' * 157, '[마스킹]', False),
            ('프렁크' + '가' * 158, '[마스킹]', True),
            ('가 ' * 40, '[마스킹]', True),
        ):
            payload = {'candidates': [{'text': text, 'confidence': 0.9}], 'policy': {'accepted': False}}
            safe = parse(record(version=22, event='FRUNK_VOICE_RECOGNITION_RESULT',
                frunkVoiceRecognition=payload))['frunkEvents'][0]['frunkVoiceRecognition']['candidates'][0]
            self.assertEqual(safe['text'], expected)
            self.assertIs(safe['truncated'], truncated)

    def test_v22_recognition_confidence_unknown_is_not_zero_or_true(self):
        for score in (None, -1, 1.1, float('inf'), float('-inf'), float('nan'), True, 'private-score'):
            payload = {'candidates': [{'text': '취소', 'confidence': score}],
                'policy': {'accepted': 'private-accepted', 'reason': 'private-reason'}}
            safe = parse(record(version=22, event='FRUNK_VOICE_RECOGNITION_RESULT',
                frunkVoiceRecognition=payload))['frunkEvents'][0]['frunkVoiceRecognition']
            self.assertIsNone(safe['candidates'][0]['confidence'])
            self.assertEqual(safe['policy'], {'accepted': None, 'reason': None})

    def test_legacy_schemas_and_unrelated_events_never_export_recognition_attachment(self):
        payload = {'candidates': [{'text': '헤이 테슬라 프렁크 열어줘', 'confidence': 0.99}],
            'policy': {'accepted': True, 'reason': None}}
        rows = [record(version=version, event='FRUNK_VOICE_RECOGNITION_RESULT',
            frunkVoiceRecognition=payload) for version in range(1, 22)]
        for event in ('FRUNK_VOICE_STARTED', 'FRUNK_VOICE_CANCELED', 'FRUNK_VOICE_EXPIRED',
                      'SPEECH_TRIAL_FINAL_RECEIVED_DIRECT_MIC', 'SPEECH_SUPPORT_METADATA_RECEIVED'):
            rows.append(record(version=22, event=event, frunkVoiceRecognition=payload))
        rows.append(record(version=21, event='TESLA_FRUNK_COMMAND_SENT', frunkVoiceRecognition=payload))
        report = parse(*rows)
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['counts']['unsupportedRows'], 0)
        self.assertEqual(set(report['versions']), {str(version) for version in range(1, 23)})
        self.assertTrue(all(point['frunkVoiceRecognition'] is None for point in report['frunkEvents']))
        self.assertNotIn('헤이 테슬라 프렁크 열어줘', json.dumps(report, ensure_ascii=False))

    def test_recognition_cli_renders_masked_korean_and_excludes_trial_without_leaking(self):
        row = record(version=22, event='FRUNK_VOICE_RECOGNITION_RESULT',
            frunkVoiceRecognition={'candidates': [{'text': '헤이 테슬라 가상사용자 프렁크 열어줘',
                'confidence': 0.5}], 'policy': {'accepted': False, 'reason': 'FRUNK_VOICE_GRAMMAR_BLOCKED'}})
        payload = (json.dumps(row) + '\n').encode('utf-8')
        for flags in ([], ['--json']):
            out = io.StringIO()
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out):
                code = main(['synthetic-input.jsonl'] + flags)
            self.assertEqual(code, 0)
            rendered = json.loads(out.getvalue()) if flags else out.getvalue()
            encoded = json.dumps(rendered, ensure_ascii=False)
            self.assertIn('헤이 테슬라 [마스킹] 프렁크 열어줘', encoded)
            self.assertIn('FRUNK_VOICE_GRAMMAR_BLOCKED', encoded)
            self.assertNotIn('가상사용자', encoded)
        self.assertEqual(parse(row, exclude=(T,))['frunkEvents'], [])

    def test_frunk_events_work_without_ble_trial_and_do_not_infer_open_from_send_or_ack(self):
        for observed in (False, None, 1, 'private-status'):
            row = record(version=21, trialId=None, event='TESLA_FRUNK_COMMAND_SENT',
                state={'frunkCommandRequested': True, 'frunkCommandSent': True,
                    'frunkCommandAcknowledged': False, 'frunkOpenObserved': observed,
                    'privateVin': 'private-VIN-key'})
            report = parse(row)
            point = report['frunkEvents'][0]
            self.assertIs(point['state']['frunkOpenObserved'], False if observed is False else None)
            self.assertIs(point['state']['frunkCommandAcknowledged'], False)
            self.assertNotIn('private-', json.dumps(report))
            self.assertEqual(report['trials'], [])
        for version, event in ((20, 'TESLA_FRUNK_COMMAND_SENT'), (21, 'TESLA_FRUNK_PRIVATE_KEY')):
            self.assertEqual(parse(record(version=version, event=event))['frunkEvents'], [])

    def test_frunk_observations_respect_exclusion_and_process_boundaries(self):
        first = record(version=21, event='TESLA_FRUNK_OPEN_OBSERVED',
            state={'frunkOpenObserved': True})
        second = record(version=21, processId=Q, trialId=None,
            event='TESLA_FRUNK_COMMAND_REQUESTED', state={'frunkCommandRequested': True})
        report = parse(first, second, exclude=(T,))
        self.assertEqual([p['processId'] for p in report['frunkEvents']], [Q])
        self.assertIsNone(report['frunkEvents'][0]['state']['frunkOpenObserved'])

    def test_v20_sleep_status_requires_verified_body_with_received_time_and_preserves_unknown(self):
        for version in (19, 20):
            for verified in (False, True):
                for received in (None, 999, 1001):
                    for status in (0, 1, 2, 7, -1, True, 'private-status'):
                        with self.subTest(version=version, verified=verified, received=received, status=status):
                            row = dict(approach(bodyVerified=verified, bodyReceivedElapsedMs=received,
                                bodyVehicleSleepStatus=status), version=version)
                            summary = parse(row)['trials'][0]['approachReadAttempts'][0]['lastSummary']
                            expected = status if (version == 20 and verified and received == 999 and
                                type(status) is int and status >= 0) else None
                            self.assertEqual(summary['bodyVehicleSleepStatus'], expected)
                            self.assertNotIn('private-status', json.dumps(summary))
                            self.assertFalse(summary['driveVerified'])

    def test_v20_busy_retry_observation_rejects_invalid_and_legacy_values_without_claiming_success(self):
        for version in (19, 20):
            for count in (0, 1, 2, -1, 3, True, 1.0, 'private-key-value', None):
                with self.subTest(version=version, count=count):
                    row = dict(approach('TESLA_APPROACH_WRITE_RESULT',
                        writeBusyRetryCount=count, writeRequestStatus=201), version=version)
                    trial = parse(row)['trials'][0]
                    summary = trial['approachReadAttempts'][0]['lastSummary']
                    expected = count if version == 20 and type(count) is int and 0 <= count <= 2 else None
                    self.assertEqual(summary['writeBusyRetryCount'], expected)
                    self.assertEqual(summary['writeRequestStatus'], 201)
                    self.assertFalse(summary['bodyVerified'])
                    self.assertEqual(trial['approachCompleteReadCount'], 0)
                    self.assertNotIn('private-key-value', json.dumps(trial))

    def test_v19_connect_admission_is_nullable_boolean_not_connection_success(self):
        for action in ('CREATE', 'RECONNECT'):
            for accepted in (False, True, None, 0, 'true'):
                with self.subTest(action=action, accepted=accepted):
                    row = dict(approach('TESLA_APPROACH_GATT_CONNECT_RESULT',
                        gattAutoConnect=False, gattConnectAction=action,
                        gattConnectAccepted=accepted), version=19)
                    trial = parse(row)['trials'][0]
                    summary = trial['approachReadAttempts'][0]['lastSummary']
                    self.assertIs(summary['gattAutoConnect'], False)
                    self.assertEqual(summary['gattConnectAction'], action)
                    self.assertIs(summary['gattConnectAccepted'],
                        accepted if type(accepted) is bool else None)
                    self.assertIsNone(summary['gattConnectionStatus'])
                    self.assertEqual(trial['approachCompleteReadCount'], 0)
        secret = 'VIN-MAC-KEY-private-action'
        row = dict(approach(gattConnectAction=secret, gattAutoConnect=1,
            gattConnectAccepted='false'), version=19)
        trial = parse(row)['trials'][0]
        self.assertNotIn(secret, json.dumps(trial))
        for key in ('gattConnectAction', 'gattAutoConnect', 'gattConnectAccepted'):
            self.assertIsNone(trial['approachReadAttempts'][0]['lastSummary'][key])

    def test_legacy_connect_admission_remains_unobserved(self):
        for version in range(9, 19):
            with self.subTest(version=version):
                row = dict(approach(gattAutoConnect=True, gattConnectAction='CREATE',
                    gattConnectAccepted=True), version=version)
                summary = parse(row)['trials'][0]['approachReadAttempts'][0]['lastSummary']
                for key in ('gattAutoConnect', 'gattConnectAction', 'gattConnectAccepted'):
                    self.assertIsNone(summary[key])

    def test_v19_recovery_and_independent_version_rounds_preserve_policy_boundaries(self):
        for first_version, second_version in ((18, 18), (19, 19), (20, 20), (21, 21), (22, 22), (23, 23), (24, 24), (25, 25), (26, 26), (27, 27), (28, 28), (29, 29), (30, 30), (29, 30), (30, 29), (23, 24), (24, 23), (22, 23), (23, 22), (21, 22), (22, 21), (20, 21), (21, 20), (18, 20), (20, 19), (18, 19), (19, 18), (17, 17)):
            for second_start in (3699, 3700, 39700, 39701):
                with self.subTest(versions=(first_version, second_version), start=second_start):
                    rows = recovery_rows(second_start=second_start)
                    for index, row in enumerate(rows):
                        row['version'] = first_version if index < 5 else second_version
                    report = parse(*rows)
                    trial = report['trials'][0]
                    permitted = first_version >= 18 and second_start in (3700, 39700)
                    self.assertEqual(trial['approachRecoveryReadCount'], int(permitted))
                    self.assertEqual(trial['approachCompleteReadCount'], int(permitted))
                    self.assertEqual('REPEATED_APPROACH_READ' in [i['code'] for i in report['issues']], not permitted)
        for mutation in ('mixed_round', 'mixed_retry', 'unclean', 'third', 'security'):
            rows = recovery_rows()
            for row in rows:
                row['version'] = 19
            if mutation == 'mixed_round':
                rows[1]['version'] = 18
            elif mutation == 'mixed_retry':
                rows[3]['version'] = 18
            elif mutation == 'unclean':
                rows[4]['teslaApproachRead']['localClosed'] = False
            elif mutation == 'third':
                rows.append(dict(approach('TESLA_APPROACH_READ_STARTED', 4500, attempt=3), version=19))
            else:
                rows[1]['teslaApproachRead']['writeException'] = 'SECURITY_EXCEPTION'
            with self.subTest(mutation=mutation):
                report = parse(*rows)
                trial = report['trials'][0]
                self.assertEqual(trial['approachRecoveryReadCount'], 0)
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertIn('REPEATED_APPROACH_READ', [i['code'] for i in report['issues']])

    def test_body_ack_is_pending_and_raw_library_failure_does_not_become_native_status(self):
        row = approach('TESLA_APPROACH_RX_RESPONSE', responseDisposition='ACK_WAITING',
            responseSignedStatusPresent=True, responseSignedMessageFault=0, responseOperationStatus=0,
            libraryWriteFailureCode=-4, rawFaultText='private-response-payload')
        row['version'] = 16
        trial = parse(row)['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['responseSignedMessageFault'], 0)
        self.assertEqual(summary['responseOperationStatus'], 0)
        self.assertEqual(summary['libraryWriteFailureCode'], -4)
        self.assertIsNone(summary['writeCallbackStatus'])
        self.assertIsNone(summary['gattConnectionStatus'])
        self.assertFalse(summary['bodyVerified'])
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertNotIn('private-response-payload', json.dumps(trial))
        row['teslaApproachRead'].update(responseSignedStatusPresent=False,
            responseSignedMessageFault=0, responseOperationStatus=0)
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIs(summary['responseSignedStatusPresent'], False)
        self.assertIsNone(summary['responseSignedMessageFault'])
        self.assertIsNone(summary['responseOperationStatus'])
        row['version'] = 15
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['responseSignedStatusPresent'])
        self.assertIsNone(summary['libraryWriteFailureCode'])

    def test_signed_status_and_library_failure_reject_private_types_but_keep_unknown_int32(self):
        row = approach(responseSignedStatusPresent=True, responseSignedMessageFault=-1,
            responseOperationStatus=12345, libraryWriteFailureCode=257)
        row['version'] = 16
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['responseSignedMessageFault'], -1)
        self.assertEqual(summary['responseOperationStatus'], 12345)
        self.assertEqual(summary['libraryWriteFailureCode'], 257)
        for value in (True, 'private-code', 2**31, -(2**31)-1):
            row['teslaApproachRead'].update(responseSignedMessageFault=value,
                responseOperationStatus=value, libraryWriteFailureCode=value)
            report = parse(row)
            summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
            for key in ('responseSignedMessageFault', 'responseOperationStatus', 'libraryWriteFailureCode'):
                self.assertIsNone(summary[key])
            self.assertNotIn('private-code', json.dumps(report))

    def test_v17_native_sdk_rejection_preserves_evidence_without_inventing_completion(self):
        row = approach('TESLA_APPROACH_WRITE_RESULT', stage='CLEANING_UP',
            outcome='UNKNOWN', reason='GATT_WRITE_REJECTED', gattTransport='NATIVE',
            writeRequestStatus=201, writeRequestedElapsedMs=950, writeReturnedElapsedMs=951,
            writeCallbackStatus=None, gattConnectionStatus=0, gattDescriptorCallbackStatus=0,
            localClosed=True, leaseRetained=False, libraryWriteFailureCode=-3,
            libraryWriteCompletedElapsedMs=990, rawSdkMessage='private-device-address')
        row['version'] = 17
        report = parse(row)
        self.assertEqual(report['issues'], [])
        trial = report['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['gattTransport'], 'NATIVE')
        self.assertEqual(summary['writeRequestStatus'], 201)
        self.assertIsNone(summary['writeCallbackStatus'])
        self.assertIsNone(summary['libraryWriteFailureCode'])
        self.assertIsNone(summary['libraryWriteCompletedElapsedMs'])
        self.assertFalse(summary['bodyVerified'])
        self.assertFalse(summary['driveVerified'])
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertNotIn('private-device-address', json.dumps(report))

    def test_domain_mismatch_correlation_is_not_authenticated_or_completed_read(self):
        row = approach('TESLA_APPROACH_RX_RESPONSE', responseDisposition='DOMAIN_MISMATCH',
            responseExpectedDomain=3, responseFromPresent=True, responseFromKind='DOMAIN', responseFromDomain=2,
            responseToPresent=True, responseRequestUuidPresent=True,
            responseRequestUuidMatches=False, responseToRouteMatches=False, rawUuid='private-request-uuid')
        row['version'] = 15
        trial = parse(row)['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIs(summary['responseRequestUuidMatches'], False)
        self.assertIs(summary['responseToRouteMatches'], False)
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertNotIn('private-request-uuid', json.dumps(trial))
        row['teslaApproachRead'].update(responseRequestUuidPresent=False, responseRequestUuidMatches=True,
            responseToPresent=False, responseToRouteMatches=True)
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['responseRequestUuidMatches'])
        self.assertIsNone(summary['responseToRouteMatches'])
        row['version'] = 14
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['responseRequestUuidPresent'])

    def test_candidate_diagnostics_keep_future_expiry_but_do_not_invent_a_visit(self):
        row = record(version=15, event='BLE_CANDIDATE_DIAGNOSTICS', candidateDiagnostics={
            'candidate': 1, 'observedElapsedMs': 1000, 'candidatePresent': True,
            'readInFlight': False, 'waitingForDeparture': True, 'scanRunning': True,
            'acceptedAdvertisementCount': 5, 'candidateAdvertisementCount': 5, 'suppressedReadCount': 4,
            'lastAcceptedReceivedElapsedMs': 900, 'lastAcceptedDeliveredElapsedMs': 950,
            'expiresElapsedMs': 180900, 'lastExpiredElapsedMs': None, 'lastExpiryDelayMs': None})
        trial = parse(row)['trials'][0]
        snapshot = trial['candidateDiagnostics'][0]['summary']
        self.assertEqual(snapshot['expiresElapsedMs'], 180900)
        self.assertIs(snapshot['readInFlight'], False)
        self.assertEqual(snapshot['suppressedReadCount'], 4)
        self.assertEqual(trial['candidateTypedCount'], 0)
        self.assertEqual(trial['approachStartedReadCount'], 0)
        row.update(version=14, event='BLE_FIELD_RUNNING')
        trial = parse(row)['trials'][0]
        self.assertFalse(trial['candidateDiagnosticsSupported'])
        self.assertEqual(trial['candidateDiagnostics'], [])

    def test_candidate_unknown_counts_and_invalid_times_do_not_expose_private_data(self):
        row = record(version=15, event='BLE_CANDIDATE_DIAGNOSTICS', candidateDiagnostics={
            'candidate': False, 'observedElapsedMs': 1001, 'candidatePresent': 1,
            'acceptedAdvertisementCount': 'private-advertisement-name', 'candidateAdvertisementCount': -1,
            'suppressedReadCount': True, 'lastAcceptedReceivedElapsedMs': 2000,
            'lastAcceptedDeliveredElapsedMs': -1, 'expiresElapsedMs': -1,
            'rawAddress': 'private-peer-address'})
        trial = parse(row)['trials'][0]
        snapshot = trial['candidateDiagnostics'][0]['summary']
        for key in ('candidate', 'observedElapsedMs', 'candidatePresent', 'acceptedAdvertisementCount',
                    'candidateAdvertisementCount', 'suppressedReadCount', 'lastAcceptedReceivedElapsedMs',
                    'lastAcceptedDeliveredElapsedMs', 'expiresElapsedMs'):
            self.assertIsNone(snapshot[key])
        self.assertNotIn('private-', json.dumps(trial))

    def test_connect_retry_preserves_first_133_without_counting_another_visit_or_verification(self):
        events = [
            ('TESLA_APPROACH_READ_STARTED', 0, None),
            ('TESLA_APPROACH_GATT_CONNECTION_CALLBACK', 100, 133),
            ('TESLA_APPROACH_GATT_RETRY_SCHEDULED', 110, 133),
            ('TESLA_APPROACH_GATT_RETRY_STARTED', 1110, None),
            ('TESLA_APPROACH_GATT_CONNECTION_CALLBACK', 1150, 0),
        ]
        rows = []
        for event, offset, status in events:
            row = approach(event, offset, stage='CONNECTING', gattConnectionStatus=status)
            row['version'] = 16
            rows.append(row)
        trial = parse(*rows)['trials'][0]
        self.assertEqual(len(trial['approachReadAttempts']), 1)
        attempt = trial['approachReadAttempts'][0]
        self.assertEqual(attempt['startCount'], 1)
        self.assertEqual([point['summary']['gattConnectionStatus'] for point in attempt['stages']],
                         [None, 133, 133, None, 0])
        self.assertFalse(attempt['bodyVerifiedObserved'])
        self.assertFalse(attempt['driveVerifiedObserved'])
        self.assertEqual(trial['approachCompleteReadCount'], 0)

    def test_nordic_queue_boundary_is_not_native_connect_or_verified_status(self):
        row = approach(stage='CONNECTING', gattTransport='NORDIC',
            connectSettleStartedElapsedMs=400, connectSettleCompletedElapsedMs=900,
            libraryConnectQueuedElapsedMs=950, advertisementAgeAtConnectQueueMs=600,
            libraryConnectionObservedElapsedMs=980, libraryDescriptorCompletedElapsedMs=990,
            libraryWriteCompletedElapsedMs=995, libraryLastRxDeliveredElapsedMs=1000)
        row['version'] = 14
        trial = parse(row)['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['gattTransport'], 'NORDIC')
        self.assertEqual(summary['connectSettleCompletedElapsedMs'] - summary['connectSettleStartedElapsedMs'], 500)
        self.assertIsNone(summary['gattConnectIssuedElapsedMs'])
        self.assertIsNone(summary['gattConnectReturnedElapsedMs'])
        self.assertIsNone(summary['writeRequestStatus'])
        for native_time in ('gattConnectionElapsedMs', 'gattDescriptorCallbackElapsedMs', 'writeCallbackElapsedMs', 'lastRxElapsedMs'):
            self.assertIsNone(summary[native_time])
        self.assertEqual(summary['libraryLastRxDeliveredElapsedMs'], 1000)
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        row['version'] = 13
        legacy = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(legacy['gattTransport'])
        self.assertIsNone(legacy['libraryConnectQueuedElapsedMs'])

    def test_nordic_observation_rejects_private_transport_and_future_or_negative_time(self):
        row = approach(gattTransport='private-device-address',
            connectSettleStartedElapsedMs=-1, connectSettleCompletedElapsedMs=1001,
            libraryConnectQueuedElapsedMs=True, advertisementAgeAtConnectQueueMs=-1,
            libraryConnectionObservedElapsedMs=1001, libraryDescriptorCompletedElapsedMs=-1,
            libraryWriteCompletedElapsedMs='private-time', libraryLastRxDeliveredElapsedMs=True)
        row['version'] = 14
        report = parse(row)
        summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        for key in ('gattTransport', 'connectSettleStartedElapsedMs', 'connectSettleCompletedElapsedMs',
                    'libraryConnectQueuedElapsedMs', 'advertisementAgeAtConnectQueueMs',
                    'libraryConnectionObservedElapsedMs', 'libraryDescriptorCompletedElapsedMs',
                    'libraryWriteCompletedElapsedMs', 'libraryLastRxDeliveredElapsedMs'):
            self.assertIsNone(summary[key])
        self.assertNotIn('private-device-address', json.dumps(report))

    def test_v1_through_v17_preserve_read_schema_absence_and_boolean_boundary(self):
        for version in range(1, 18):
            with self.subTest(version=version):
                row = complete_approach()
                row.update(version=version, state={'bleFieldSupplementalScan': True, 'bleFieldAuthenticatedRead': True})
                report = parse(row)
                trial = report['trials'][0]
                self.assertEqual(report['issues'], [])
                self.assertEqual(trial['approachReadSupported'], version >= 9)
                self.assertEqual(trial['options'][0]['values']['bleFieldAuthenticatedRead'], True if version >= 9 else None)
                self.assertEqual(trial['approachCompleteReadCount'], 1 if version >= 9 else None)
                if version < 9:
                    self.assertEqual(trial['approachReadAttempts'], [])
        for value in (True, False, 1, 0, 'BLE_FIELD_RUNNING', None, [], {}):
            report = parse(record(version=9, state={'bleFieldSupplementalScan': True, 'bleFieldAuthenticatedRead': value}))
            self.assertEqual(report['trials'][0]['options'][0]['values']['bleFieldAuthenticatedRead'],
                value if type(value) is bool else None)

    def test_absent_domain_is_not_default_zero_and_old_mismatch_details_are_unknown(self):
        row = approach('TESLA_APPROACH_RX_RESPONSE', query='DRIVE_STATE',
            responseQuery='DRIVE_STATE', responseDisposition='DOMAIN_MISMATCH',
            responseExpectedDomain=3, responseFromPresent=False, responseFromKind='UNSET',
            responseFromDomain=0, responseDomainMismatchReason='FROM_MISSING')
        row['version'] = 13
        trial = parse(row)['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['responseFromDomain'])
        self.assertEqual(summary['responseDomainMismatchReason'], 'FROM_MISSING')
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        row['teslaApproachRead'].update(responseFromPresent=True, responseFromKind='DOMAIN',
            responseDomainMismatchReason='DOMAIN_DIFFERENT')
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['responseFromDomain'], 0)
        row['version'] = 12
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['responseExpectedDomain'])
        self.assertIsNone(summary['responseDomainMismatchReason'])

    def test_response_header_diagnostics_do_not_expose_unknown_private_strings(self):
        row = approach(responseFromPresent=True, responseFromKind='private-route',
            responseFromDomain=True, responseToKind='private-address',
            responsePayloadKind='raw-private-packet', responseDomainMismatchReason='secret-key')
        row['version'] = 13
        report = parse(row)
        summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        for key in ('responseFromKind', 'responseFromDomain', 'responseToKind',
            'responsePayloadKind', 'responseDomainMismatchReason'):
            self.assertIsNone(summary[key])
        self.assertNotIn('private-', json.dumps(report))
        self.assertNotIn('secret-key', json.dumps(report))

    def test_connectability_false_is_observed_not_unknown_and_legacy_cannot_claim_admission(self):
        row = approach('TESLA_APPROACH_GATT_CONNECT_RESULT', stage='CONNECTING',
            advertisementConnectable=False, gattConnectIssuedElapsedMs=999,
            gattConnectReturnedElapsedMs=1000, advertisementAgeAtConnectMs=25)
        row['version'] = 13
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIs(summary['advertisementConnectable'], False)
        self.assertEqual(summary['advertisementAgeAtConnectMs'], 25)
        row['version'] = 12
        self.assertEqual(parse(row)['trials'][0]['approachReadAttempts'], [])
        row['version'] = 13
        row['teslaApproachRead'].update(advertisementConnectable=0,
            gattConnectReturnedElapsedMs=1001, advertisementAgeAtConnectMs=-1)
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['advertisementConnectable'])
        self.assertIsNone(summary['gattConnectReturnedElapsedMs'])
        self.assertIsNone(summary['advertisementAgeAtConnectMs'])

    def test_queued_write_is_not_submission_and_native_states_do_not_fill_unknown_success(self):
        row = approach('TESLA_APPROACH_WRITE_QUEUED', writeDispatchQueued=True,
            gattConnectionStatus=0, gattConnectionState=2, gattDescriptorRequestStatus=0,
            gattDescriptorCallbackStatus=0, writeRequestStatus=None, writeCallbackStatus=None)
        row['version'] = 12
        trial = parse(row)['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertTrue(summary['writeDispatchQueued'])
        self.assertIsNone(summary['writeRequestStatus'])
        self.assertIsNone(summary['writeCallbackStatus'])
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        row['version'] = 11
        self.assertEqual(parse(row)['trials'][0]['approachReadAttempts'], [])
        row['event'] = 'TESLA_APPROACH_READ_STAGE'
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['gattConnectionStatus'])
        self.assertIsNone(summary['writeDispatchQueued'])

    def test_native_diagnostics_reject_boolean_status_and_unobserved_dispatch_time(self):
        row = approach(gattConnectionStatus=True, gattConnectionState='private-address',
            gattDescriptorCallbackStatus='secret-key', writeDispatchQueued=1,
            writeDispatchElapsedMs=1001, gattDescriptorCallbackElapsedMs=-1)
        row['version'] = 12
        report = parse(row)
        summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        for key in ('gattConnectionStatus', 'gattConnectionState', 'gattDescriptorCallbackStatus',
            'writeDispatchQueued', 'writeDispatchElapsedMs', 'gattDescriptorCallbackElapsedMs'):
            self.assertIsNone(summary[key])
        self.assertNotIn('secret-key', json.dumps(report))
        self.assertNotIn('private-address', json.dumps(report))

    def test_body_response_cannot_be_misread_as_drive_response_and_legacy_rx_is_unknown(self):
        row = approach(query='DRIVE_STATE', bodyVerified=True, responseQuery='BODY_STATUS',
            responseDisposition='STATUS_VERIFIED', rxNotificationCount=2, rxFrameCount=2,
            rxProtocolCount=2, lastRxElapsedMs=1000, lastFrameElapsedMs=1000)
        row['version'] = 11
        trial = parse(row)['trials'][0]
        summary = trial['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['responseQuery'], 'BODY_STATUS')
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        row['version'] = 10
        summary = parse(row)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['rxNotificationCount'])
        self.assertIsNone(summary['responseDisposition'])
        row['event'] = 'TESLA_APPROACH_RX_RESPONSE'
        self.assertEqual(parse(row)['trials'][0]['approachReadAttempts'], [])

    def test_rx_diagnostic_rejects_private_text_boolean_counts_and_future_times(self):
        row = approach(responseDisposition='private-packet-key', responseQuery='private-VIN',
            rxNotificationCount=True, rxFrameCount=-1, lastRxElapsedMs=1001)
        row['version'] = 11
        report = parse(row)
        summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        for key in ('responseDisposition', 'responseQuery', 'rxNotificationCount', 'rxFrameCount', 'lastRxElapsedMs'):
            self.assertIsNone(summary[key])
        self.assertNotIn('private-', json.dumps(report))

    def test_write_submission_is_not_native_completion_and_old_logs_cannot_claim_it(self):
        rejected = approach('TESLA_APPROACH_WRITE_RESULT', writeSequence=1,
            writeRequestStatus=201, writeCallbackStatus=None, writeReturnedElapsedMs=1000)
        rejected['version'] = 10
        report = parse(rejected)
        summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual(summary['writeRequestStatus'], 201)
        self.assertIsNone(summary['writeCallbackStatus'])
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        failed = approach('TESLA_APPROACH_WRITE_CALLBACK', writeSequence=1,
            writeRequestStatus=0, writeCallbackStatus=133, writeCallbackElapsedMs=1000)
        failed['version'] = 10
        summary = parse(failed)['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertEqual((summary['writeRequestStatus'], summary['writeCallbackStatus']), (0, 133))
        rejected['version'] = 9
        self.assertEqual(parse(rejected)['trials'][0]['approachReadAttempts'], [])

    def test_write_metadata_redacts_exception_text_and_future_callback_time(self):
        row = approach(writeSequence=1, writeRequestStatus=True,
            writeException='secret-key-and-vehicle-data', writeCallbackElapsedMs=1001)
        row['version'] = 10
        report = parse(row)
        summary = report['trials'][0]['approachReadAttempts'][0]['stages'][0]['summary']
        self.assertIsNone(summary['writeRequestStatus'])
        self.assertIsNone(summary['writeException'])
        self.assertIsNone(summary['writeCallbackElapsedMs'])
        self.assertNotIn('secret-key-and-vehicle-data', json.dumps(report))

    def test_sequential_body_drive_share_one_approach_read_and_keep_source_time(self):
        rows = [
            approach('TESLA_APPROACH_READ_STARTED', stage='PAUSING_SCAN', query=None),
            approach('TESLA_APPROACH_BODY_VERIFIED', 100, bodyVerified=True,
                bodyReceivedElapsedMs=1100, lockState=0, frontTrunkState=0),
            approach(offset=200, stage='HANDSHAKING', query='DRIVE_STATE', bodyVerified=True,
                bodyReceivedElapsedMs=1100),
            approach('TESLA_APPROACH_DRIVE_VERIFIED', 300, query='DRIVE_STATE',
                bodyVerified=True, driveVerified=True, bodyReceivedElapsedMs=1100,
                driveReceivedElapsedMs=1300, driveSourceWallMs=BASE-200, gear='P'),
            complete_approach(400, bodyReceivedElapsedMs=1100,
                driveReceivedElapsedMs=1300, driveSourceWallMs=BASE-200, gear='P'),
        ]
        report = json.loads(json.dumps(parse(*rows), allow_nan=False))
        self.assertEqual(report['issues'], [])
        trial = report['trials'][0]
        self.assertEqual(trial['approachStartedReadCount'], 1)
        self.assertEqual(trial['approachFinalReadCount'], 1)
        self.assertEqual(trial['approachCompleteReadCount'], 1)
        self.assertEqual(trial['approachPartialReadCount'], 0)
        group = trial['approaches'][0]
        self.assertEqual(group['readAttemptCount'], 1)
        self.assertEqual(group['startedReadCount'], 1)
        self.assertEqual(group['observedQueries'], ['BODY_STATUS', 'DRIVE_STATE'])
        self.assertFalse(group['repeatedReadObserved'])
        read = trial['approachReadAttempts'][0]
        self.assertEqual(read['elapsedReadMs'], 400)
        self.assertEqual(read['cleanupStatus'], 'clean')
        self.assertEqual(read['final']['driveSourceWallMs'], BASE-200)
        self.assertEqual(read['finalTime']['bodyReceivedProjectedWallMs'], BASE+100)
        self.assertEqual(read['finalTime']['driveReceivedProjectedWallMs'], BASE+300)
        self.assertEqual(read['stages'][1]['summary']['lockState'], 0)

    def test_body_verified_drive_failed_is_partial_not_complete(self):
        trial = parse(complete_approach(stage='FAILED', outcome='PARTIAL',
            driveVerified=False, reason='DRIVE_READ_FAILED', gear=None,
            bodyReceivedElapsedMs=900))['trials'][0]
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertEqual(trial['approachPartialReadCount'], 1)
        read = trial['approachReadAttempts'][0]
        self.assertEqual(read['result'], 'partial')
        self.assertEqual(read['final']['stage'], 'FAILED')
        self.assertEqual(read['cleanupStatus'], 'clean')
        self.assertIsNone(read['final']['driveSourceWallMs'])
        self.assertIsNone(read['final']['gear'])

    def test_verified_stage_without_final_is_not_counted_as_completion(self):
        rows = [
            approach('TESLA_APPROACH_BODY_VERIFIED', bodyVerified=True),
            approach('TESLA_APPROACH_DRIVE_VERIFIED', 100, query='DRIVE_STATE',
                bodyVerified=True, driveVerified=True, stage='COMPLETE',
                outcome='VERIFIED_STATUS', localClosed=True),
        ]
        trial = parse(*rows)['trials'][0]
        self.assertEqual(trial['approachFinalReadCount'], 0)
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        read = trial['approachReadAttempts'][0]
        self.assertTrue(read['bodyVerifiedObserved'])
        self.assertTrue(read['driveVerifiedObserved'])
        self.assertIsNone(read['final'])
        self.assertEqual(read['result'], 'unfinished')

    def test_full_verification_requires_complete_outcome_and_closed_released_lease(self):
        for changes in (
            {'localClosed': False}, {'leaseRetained': True},
            {'stage': 'FAILED'}, {'outcome': 'UNKNOWN'}, {'driveVerified': 'true'},
            {'bodyVerified': 1}, {'stage': 'complete'},
        ):
            with self.subTest(changes=changes):
                trial = parse(complete_approach(**changes))['trials'][0]
                self.assertEqual(trial['approachCompleteReadCount'], 0)
        report = parse(complete_approach(),
            approach('TESLA_APPROACH_READ_CLEANUP_FAILED', 100,
                stage='CLEANUP_FAILED', outcome='FAILED', query='DRIVE_STATE',
                reason='LOCAL_CLEANUP_FAILED_RESTART_REQUIRED',
                bodyVerified=True, driveVerified=True, leaseRetained=True))
        read = report['trials'][0]['approachReadAttempts'][0]
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        self.assertEqual(report['trials'][0]['approachCleanupFailedCount'], 1)
        self.assertEqual(read['cleanupStatus'], 'failed')
        self.assertEqual(read['result'], 'cleanup_failed')
        self.assertEqual(read['final']['stage'], 'COMPLETE')
        self.assertTrue(read['lastSummary']['leaseRetained'])

    def test_timeout_can_preserve_partial_body_and_is_not_a_deadline_timestamp(self):
        trial = parse(complete_approach(stage='TIMED_OUT', outcome='PARTIAL',
            reason='DRIVE_READ_TIMEOUT', driveVerified=False))['trials'][0]
        read = trial['approachReadAttempts'][0]
        self.assertTrue(read['partialSuccess'])
        self.assertTrue(read['deadlineExpired'])
        self.assertEqual(read['result'], 'timed_out')
        self.assertEqual(trial['approachDeadlineExpiredCount'], 1)
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertIsNone(read['final']['driveReceivedElapsedMs'])
        self.assertIsNone(read['elapsedReadMs'])

    def test_duplicate_final_counts_once_and_conflicting_final_never_counts_complete(self):
        same = complete_approach()
        report = parse(same, same)
        self.assertEqual(report['trials'][0]['approachFinalReadCount'], 1)
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 1)
        self.assertIn('DUPLICATE_APPROACH_FINAL', [issue['code'] for issue in report['issues']])
        conflict = complete_approach(100, stage='FAILED', outcome='FAILED', reason='DRIVE_READ_FAILED')
        report = parse(same, conflict)
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        self.assertEqual(report['trials'][0]['approachReadAttempts'][0]['conflictingFinals'], 1)
        self.assertIn('CONFLICTING_APPROACH_FINAL', [issue['code'] for issue in report['issues']])

    def test_verification_regression_and_candidate_change_cannot_produce_complete(self):
        report = parse(approach('TESLA_APPROACH_BODY_VERIFIED', bodyVerified=True),
            approach(offset=100, bodyVerified=False), complete_approach(200))
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        self.assertIn('APPROACH_VERIFICATION_REGRESSED', [issue['code'] for issue in report['issues']])
        report = parse(approach('TESLA_APPROACH_READ_STARTED'), complete_approach(100, candidate=2))
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        self.assertIn('APPROACH_CANDIDATE_CONFLICT', [issue['code'] for issue in report['issues']])

    def test_same_approach_repeated_reads_are_reported_and_new_candidate_is_separate(self):
        rows = [
            approach('TESLA_APPROACH_READ_STARTED'),
            complete_approach(100),
            approach('TESLA_APPROACH_READ_STARTED', 200, attempt=2),
            complete_approach(300, attempt=2),
            approach('TESLA_APPROACH_READ_STARTED', 400, attempt=3, candidate=2),
            complete_approach(500, attempt=3, candidate=2),
        ]
        report = parse(*rows)
        trial = report['trials'][0]
        self.assertEqual(trial['approachStartedReadCount'], 3)
        self.assertEqual(trial['approachFinalReadCount'], 3)
        self.assertEqual(trial['approachCompleteReadCount'], 1)
        self.assertEqual(trial['approaches'][0]['readAttemptCount'], 2)
        self.assertTrue(trial['approaches'][0]['repeatedReadObserved'])
        self.assertFalse(trial['approaches'][1]['repeatedReadObserved'])
        self.assertIn('REPEATED_APPROACH_READ', [issue['code'] for issue in report['issues']])

    def test_v18_clean_transient_recovery_counts_only_second_verified_read(self):
        failures = (
            'GATT_CONNECT_FAILED', 'GATT_CONNECTION_LOST', 'GATT_DISCOVERY_FAILED',
            'GATT_MTU_FAILED', 'GATT_SUBSCRIPTION_FAILED', 'GATT_WRITE_FAILED',
        )
        for stage, reason in [('FAILED', reason) for reason in failures] + [('TIMED_OUT', 'DEADLINE_EXPIRED')]:
            with self.subTest(stage=stage, reason=reason):
                report = json.loads(json.dumps(parse(*recovery_rows(stage, reason)), allow_nan=False))
                self.assertEqual(report['issues'], [])
                trial = report['trials'][0]
                self.assertEqual(trial['approachStartedReadCount'], 2)
                self.assertEqual(trial['approachRecoveryReadCount'], 1)
                self.assertEqual(trial['approachCompleteReadCount'], 1)
                self.assertEqual([a['countedComplete'] for a in trial['approachReadAttempts']], [False, True])
                group = trial['approaches'][0]
                self.assertTrue(group['repeatedReadObserved'])
                self.assertTrue(group['recoveryReadObserved'])
                self.assertFalse(group['repeatedReadPolicyViolation'])
                self.assertEqual(group['completeReadCount'], 1)
        rows = recovery_rows()
        rows.insert(4, dict(approach(offset=200, stage='RESUMING_SCAN', outcome='FAILED',
            reason='GATT_CONNECT_FAILED', localClosed=True, leaseRetained=False), version=18))
        self.assertEqual(parse(*rows)['trials'][0]['approachCompleteReadCount'], 1)

    def test_v18_recovery_without_complete_body_and_drive_is_not_success(self):
        pending = recovery_rows()[:6]
        pending.append(dict(approach('TESLA_APPROACH_RX_RESPONSE', 3800, attempt=2,
            responseDisposition='ACK_WAITING'), version=18))
        partial = recovery_rows()[:7]
        partial.append(dict(complete_approach(4000, attempt=2, stage='FAILED', outcome='PARTIAL',
            reason='DRIVE_READ_FAILED', driveVerified=False,
            bodyReceivedElapsedMs=4800), version=18))
        blocked = recovery_rows()[:6]
        blocked.append(dict(approach('TESLA_APPROACH_READ_BLOCKED', 3800, attempt=2,
            stage='BLOCKED', outcome='NOT_SENT', reason='EXISTING_KEY_UNAVAILABLE',
            query=None, localClosed=True, leaseRetained=False), version=18))
        for rows in (pending, partial, blocked, recovery_rows()[:-1]):
            with self.subTest(final=rows[-1]['event']):
                report = parse(*rows)
                self.assertEqual(report['issues'], [])
                trial = report['trials'][0]
                self.assertEqual(trial['approachRecoveryReadCount'], 1)
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertEqual(trial['approaches'][0]['completeReadCount'], 0)

    def test_v18_success_block_cancel_security_and_protocol_never_allow_recovery(self):
        for stage, reason in (
            ('COMPLETE', 'AUTHENTICATED_READ_COMPLETE'), ('BLOCKED', 'EXISTING_KEY_UNAVAILABLE'),
            ('CANCELED', 'USER_CANCELED'), ('FAILED', 'AUTHENTICATION_FAILED'),
            ('FAILED', 'PROTOCOL_RESPONSE_INVALID'), ('FAILED', 'PROTOCOL_START_FAILED'),
            ('FAILED', 'GATT_CONNECT_PERMISSION_DENIED'), ('FAILED', 'GATT_WRITE_EXCEPTION'),
            ('FAILED', 'GATT_WRITE_REJECTED'), ('FAILED', 'GATT_CALLBACK_FAILED'),
            ('FAILED', 'GATT_DISCOVERY_REJECTED'), ('FAILED', 'TESLA_GATT_PROFILE_UNSUPPORTED'),
            ('TIMED_OUT', 'AUTHENTICATION_TIMEOUT'), ('TIMED_OUT', 'READ_DEADLINE_EXPIRED'),
        ):
            with self.subTest(stage=stage, reason=reason):
                rows = recovery_rows(stage, reason)
                if stage == 'COMPLETE':
                    for index in (1, 2, 4):
                        rows[index]['teslaApproachRead'].update(bodyVerified=True,
                            driveVerified=True, outcome='VERIFIED_STATUS')
                report = parse(*rows)
                trial = report['trials'][0]
                self.assertIn('REPEATED_APPROACH_READ', [i['code'] for i in report['issues']])
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertEqual(trial['approachRecoveryReadCount'], 0)
                self.assertTrue(trial['approaches'][0]['repeatedReadPolicyViolation'])

    def test_v18_uncertain_cleanup_missing_sequence_and_third_read_remain_violations(self):
        cases = []
        for changes in ({'localClosed': False}, {'localClosed': None},
                        {'leaseRetained': True}, {'leaseRetained': None}):
            rows = recovery_rows()
            for index in (2, 4):
                rows[index]['teslaApproachRead'].update(changes)
            cases.append(rows)
        rows = recovery_rows()
        cases.extend((rows[1:], rows[:1]+rows[2:]))
        cases.append(rows[:1]+[rows[0]]+rows[1:])
        cases.append(rows+[dict(approach('TESLA_APPROACH_READ_STARTED', 4500, attempt=3), version=18),
                           dict(complete_approach(4800, attempt=3), version=18)])
        blocked = dict(approach('TESLA_APPROACH_READ_BLOCKED', 250, attempt=None,
            stage='BLOCKED', outcome='NOT_SENT', reason='EXISTING_KEY_UNAVAILABLE',
            query=None, localClosed=True, leaseRetained=False), version=18)
        cases.append(rows[:5]+[blocked]+rows[5:])
        for rows in cases:
            with self.subTest(events=[row['event'] for row in rows]):
                report = parse(*rows)
                trial = report['trials'][0]
                self.assertIn('REPEATED_APPROACH_READ', [i['code'] for i in report['issues']])
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertEqual(trial['approachRecoveryReadCount'], 0)

    def test_v18_cooldown_and_shared_budget_boundaries(self):
        for second_start, allowed in ((3699, False), (3700, True), (39700, True), (39701, False), (40001, False)):
            with self.subTest(second_start=second_start):
                report = parse(*recovery_rows(second_start=second_start))
                trial = report['trials'][0]
                self.assertEqual(trial['approachRecoveryReadCount'], int(allowed))
                self.assertEqual(trial['approachCompleteReadCount'], int(allowed))
                self.assertEqual('REPEATED_APPROACH_READ' in [i['code'] for i in report['issues']], not allowed)
        rows = recovery_rows(second_start=3701)
        rows[3].update(wallMs=BASE+201, elapsedMs=1201)
        rows[3], rows[4] = rows[4], rows[3]
        self.assertEqual(parse(*rows)['trials'][0]['approachCompleteReadCount'], 1)
        rows = recovery_rows()
        retry = rows.pop(3)
        retry.update(wallMs=BASE+250, elapsedMs=1250)
        rows.insert(4, retry)
        self.assertEqual(parse(*rows)['trials'][0]['approachRecoveryReadCount'], 0)

    def test_v18_retry_requires_matching_scoped_cooldown_evidence_in_cleanup_window(self):
        cases = []
        rows = recovery_rows()
        cases.append(rows[:3]+rows[4:])
        for changes in (
            {'gate': 'SUCCESS'}, {'kind': 'RETRY_SUPPRESSED'}, {'candidate': 2},
            {'candidate': None}, {'candidate': True},
        ):
            rows = recovery_rows()
            rows[3]['bleEvidence'].update(changes)
            cases.append(rows)
        for changes in ({'version': 17}, {'event': 'BLE_FIELD_RUNNING'}, {'trialId': U}):
            rows = recovery_rows()
            rows[3].update(changes)
            cases.append(rows)
        rows = recovery_rows()
        retry = rows.pop(3)
        retry.update(wallMs=BASE+50, elapsedMs=1050)
        rows.insert(1, retry)
        cases.append(rows)
        rows = recovery_rows()
        retry = rows.pop(3)
        retry.update(wallMs=BASE+3750, elapsedMs=4750)
        rows.insert(5, retry)
        cases.append(rows)
        for rows in cases:
            with self.subTest(evidence=[row.get('bleEvidence') for row in rows if 'bleEvidence' in row]):
                report = parse(*rows)
                trial = next(t for t in report['trials'] if t['trialId'] == T)
                self.assertIn('REPEATED_APPROACH_READ', [i['code'] for i in report['issues']])
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertEqual(trial['approachRecoveryReadCount'], 0)

    def test_v18_recovery_survives_rotation_but_not_process_or_clock_boundary(self):
        rows = recovery_rows()
        encode = lambda part: io.StringIO(''.join(json.dumps(row)+'\n' for row in part))
        report = analyze(encode(rows[:3]), additional_streams=(encode(rows[3:5]), encode(rows[5:])))
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['trials'][0]['approachRecoveryReadCount'], 1)
        for boundary in ('process', 'clock', 'regression'):
            rows = recovery_rows()
            for row in rows[5:]:
                if boundary == 'process':
                    row['processId'] = Q
                elif boundary == 'clock':
                    row['wallMs'] += 10000
                else:
                    row['elapsedMs'] -= 3700
                    for key in ('bodyReceivedElapsedMs', 'driveReceivedElapsedMs'):
                        if row['teslaApproachRead'][key] is not None:
                            row['teslaApproachRead'][key] -= 3700
            with self.subTest(boundary=boundary):
                trial = parse(*rows)['trials'][0]
                self.assertEqual(trial['approachRecoveryReadCount'], 0)
                self.assertFalse(any(g['recoveryReadObserved'] for g in trial['approaches']))

    def test_mixed_versions_never_grant_v18_recovery_to_legacy_reads(self):
        for first_version, second_version in (
            *[(version, version) for version in range(9, 18)], (17, 18), (18, 17),
        ):
            with self.subTest(first=first_version, second=second_version):
                rows = recovery_rows()
                for row in rows[:3]+rows[4:5]:
                    row['version'] = first_version
                for row in rows[5:]:
                    row['version'] = second_version
                report = parse(*rows)
                trial = report['trials'][0]
                self.assertIn('REPEATED_APPROACH_READ', [i['code'] for i in report['issues']])
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertEqual(trial['approachRecoveryReadCount'], 0)
                self.assertEqual([a['versions'] for a in trial['approachReadAttempts']],
                                 [[first_version], [second_version]])
        rows = recovery_rows()
        rows[1]['version'] = 17
        report = parse(*rows)
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        self.assertEqual(report['trials'][0]['approachReadAttempts'][0]['versions'], [17, 18])
        legacy = recovery_rows()
        for row in legacy:
            row['version'] = 17
        current = recovery_rows()
        for row in current:
            row['wallMs'] += 5000
            row['elapsedMs'] += 5000
            if 'teslaApproachRead' in row:
                snapshot = row['teslaApproachRead']
                snapshot.update(candidate=2, attempt=snapshot['attempt']+2)
                for key in ('bodyReceivedElapsedMs', 'driveReceivedElapsedMs'):
                    if snapshot[key] is not None:
                        snapshot[key] += 5000
            else:
                row['bleEvidence']['candidate'] = 2
        report = parse(*legacy, *current)
        trial = report['trials'][0]
        self.assertEqual(trial['approachCompleteReadCount'], 1)
        self.assertEqual(trial['approachRecoveryReadCount'], 1)
        self.assertEqual([g['repeatedReadPolicyViolation'] for g in trial['approaches']], [True, False])

    def test_blocked_read_without_attempt_does_not_create_gatt_or_query_success(self):
        report = parse(approach('TESLA_APPROACH_READ_BLOCKED', attempt=None, candidate=7,
            stage='BLOCKED', query=None, outcome='NOT_SENT', reason='EXISTING_KEY_UNAVAILABLE'))
        trial = report['trials'][0]
        self.assertEqual(report['issues'], [])
        self.assertEqual(trial['approachReadAttempts'], [])
        self.assertEqual(trial['attempts'], [])
        self.assertEqual(trial['approachStartedReadCount'], 0)
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertEqual(trial['approaches'][0]['readAttemptCount'], 0)
        self.assertEqual(trial['approaches'][0]['blockedCount'], 1)
        self.assertEqual(trial['approachReadBlocks'][0]['summary']['reason'], 'EXISTING_KEY_UNAVAILABLE')

    def test_blocked_and_cleanup_events_are_terminal_even_without_finished_event(self):
        trial = parse(approach('TESLA_APPROACH_READ_BLOCKED', stage='BLOCKED',
            reason='SCAN_RESULT_STALE', localClosed=True))['trials'][0]
        read = trial['approachReadAttempts'][0]
        self.assertEqual(trial['approachStartedReadCount'], 0)
        self.assertEqual(trial['approachFinalReadCount'], 1)
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertEqual(read['result'], 'blocked')
        self.assertEqual(read['cleanupStatus'], 'clean')
        self.assertEqual(read['finalEvent'], 'TESLA_APPROACH_READ_BLOCKED')
        trial = parse(approach('TESLA_APPROACH_READ_CLEANUP_FAILED',
            stage='CLEANUP_FAILED', leaseRetained=True, localClosed=True,
            reason='LOCAL_CLEANUP_FAILED_RESTART_REQUIRED'))['trials'][0]
        read = trial['approachReadAttempts'][0]
        self.assertTrue(read['ended'])
        self.assertEqual(read['result'], 'cleanup_failed')
        self.assertEqual(read['cleanupStatus'], 'failed')
        self.assertEqual(trial['approachCleanupFailedCount'], 1)

    def test_terminal_timeout_stage_cannot_turn_into_late_success(self):
        trial = parse(approach(stage='TIMED_OUT', reason='DEADLINE_EXPIRED'),
            complete_approach(100))['trials'][0]
        self.assertEqual(trial['approachCompleteReadCount'], 0)
        self.assertEqual(trial['approachDeadlineExpiredCount'], 1)
        self.assertEqual(trial['approachReadAttempts'][0]['result'], 'timed_out')

    def test_invalid_approach_attempt_warns_once_and_never_creates_read(self):
        for attempt in (None, 0, -1, True, '1', 2**63):
            with self.subTest(attempt=attempt):
                report = parse(approach(attempt=attempt))
                self.assertEqual(report['issues'], [{'line': 1, 'code': 'INVALID_APPROACH_ATTEMPT'}])
                self.assertEqual(report['trials'][0]['approachReadAttempts'], [])
                self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)

    def test_missing_and_zero_source_times_remain_distinct_from_received_projection(self):
        trial = parse(complete_approach(bodyReceivedElapsedMs=0, driveReceivedElapsedMs=0))['trials'][0]
        read = trial['approachReadAttempts'][0]
        self.assertEqual(read['final']['bodyReceivedElapsedMs'], 0)
        self.assertEqual(read['finalTime']['bodyReceivedProjectedWallMs'], BASE-1000)
        self.assertIsNone(read['final']['driveSourceWallMs'])
        self.assertEqual(trial['approachCompleteReadCount'], 1)
        trial = parse(complete_approach(driveSourceWallMs=0))['trials'][0]
        self.assertEqual(trial['approachReadAttempts'][0]['final']['driveSourceWallMs'], 0)
        self.assertIsNone(trial['approachReadAttempts'][0]['finalTime']['driveReceivedProjectedWallMs'])

    def test_invalid_received_times_do_not_become_zero_or_complete(self):
        for value in (-1, True, 1.5, '1000', [], {}, 2**63, 1001):
            with self.subTest(value=value):
                report = parse(complete_approach(bodyReceivedElapsedMs=value))
                trial = report['trials'][0]
                read = trial['approachReadAttempts'][0]
                self.assertIsNone(read['final']['bodyReceivedElapsedMs'])
                self.assertIsNone(read['finalTime']['bodyReceivedProjectedWallMs'])
                self.assertEqual(trial['approachCompleteReadCount'], 0)
                self.assertIn('INVALID_APPROACH_TIME', [issue['code'] for issue in report['issues']])

    def test_attempts_do_not_merge_or_project_across_process_and_clock_boundaries(self):
        first = approach('TESLA_APPROACH_READ_STARTED')
        other = dict(complete_approach(), processId=Q, elapsedMs=10, wallMs=BASE+100000,
            teslaApproachRead=dict(complete_approach()['teslaApproachRead'], bodyReceivedElapsedMs=900))
        report = parse(first, other)
        reads = report['trials'][0]['approachReadAttempts']
        self.assertEqual([read['processId'] for read in reads], [P, Q])
        self.assertFalse(reads[0]['ended'])
        self.assertIsNone(reads[1]['elapsedReadMs'])
        self.assertIsNone(reads[1]['final']['bodyReceivedElapsedMs'])
        final = dict(complete_approach(), elapsedMs=10, wallMs=BASE+10)
        report = parse(first, final)
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)
        self.assertIn('APPROACH_TIME_BOUNDARY', [issue['code'] for issue in report['issues']])
        shifted = dict(complete_approach(), elapsedMs=1010, wallMs=BASE+10000,
            teslaApproachRead=dict(complete_approach()['teslaApproachRead'], bodyReceivedElapsedMs=1000))
        report = parse(first, shifted)
        self.assertIsNone(report['trials'][0]['approachReadAttempts'][-1]['finalTime']['bodyReceivedProjectedWallMs'])
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 0)

    def test_approach_private_strings_never_reach_json_or_cli_consumers(self):
        secret = 'SYNTHETIC-VIN-MAC-KEY-EXCEPTION'
        row = complete_approach(stage=secret, query=secret, outcome=secret, reason=secret,
            gear=secret, lockState=secret, frontTrunkState=secret, bodyVerified=secret,
            driveSourceWallMs=secret, unknown=secret)
        row['state'].update(bleFieldAuthenticatedRead=secret, vin=secret)
        row.update(appVersion=secret, rawResponse=secret)
        report = parse(row)
        self.assertNotIn(secret, json.dumps(report, allow_nan=False))
        read = report['trials'][0]['approachReadAttempts'][0]
        self.assertIsNone(read['final']['stage'])
        self.assertIsNone(read['final']['reason'])
        self.assertIsNone(read['final']['driveSourceWallMs'])
        self.assertFalse(read['countedComplete'])
        for flags in ([], ['--json']):
            out, err = io.StringIO(), io.StringIO()
            payload = (json.dumps(row)+'\n').encode('utf-8')
            with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = main(['synthetic-input.jsonl'] + flags)
            self.assertEqual(code, 1)
            self.assertNotIn(secret, out.getvalue() + err.getvalue())
            if flags:
                self.assertEqual(json.loads(out.getvalue()), report)

    def test_rotated_authenticated_read_and_excluded_trial_keep_consumer_boundaries(self):
        rows = [
            approach('TESLA_APPROACH_READ_STARTED'),
            approach('TESLA_APPROACH_BODY_VERIFIED', 100, bodyVerified=True, bodyReceivedElapsedMs=1100),
            complete_approach(200, bodyReceivedElapsedMs=1100, driveReceivedElapsedMs=1200),
        ]
        streams = [io.BytesIO((json.dumps(row)+'\n').encode('utf-8')) for row in rows]
        out = io.StringIO()
        with patch('builtins.open', side_effect=streams), contextlib.redirect_stdout(out):
            code = main(['events-v2.2.jsonl', 'events-v2.1.jsonl', 'events-v2.jsonl', '--json'])
        report = json.loads(out.getvalue())
        self.assertEqual(code, 0)
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['trials'][0]['approachCompleteReadCount'], 1)
        excluded = parse(*rows, exclude=(T,))
        self.assertEqual(excluded['trials'], [])
        self.assertEqual(excluded['counts']['excludedRows'], 3)

    def test_cli_combines_rotated_trial_boundaries_in_input_order(self):
        rows = [
            record(version=8, event='BLE_FIELD_START_REQUESTED'),
            record(version=8, event='BLE_FIELD_TRIAL_FINISHED',
                   wallMs=BASE+100, elapsedMs=1100,
                   bleTrial={'attempt': 1, 'status': 'COMPLETE'}),
            record(version=8, event='BLE_FIELD_STOPPED',
                   wallMs=BASE+200, elapsedMs=1200),
        ]
        streams = [io.BytesIO((json.dumps(row)+'\n').encode('utf-8')) for row in rows]
        out, err = io.StringIO(), io.StringIO()
        with patch('builtins.open', side_effect=streams), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = main(['events-v2.2.jsonl', 'events-v2.1.jsonl', 'events-v2.jsonl', '--json'])
        report = json.loads(out.getvalue())
        self.assertEqual(code, 0)
        self.assertEqual(err.getvalue(), '')
        self.assertEqual(report['counts']['analyzedRows'], 3)
        self.assertEqual(report['issues'], [])
        self.assertEqual(report['trials'][0]['termination'], 'normal_stop')
        self.assertEqual(report['trials'][0]['finalAttemptCount'], 1)
        self.assertEqual(report['trials'][0]['completeAttemptCount'], 1)

    def test_partial_archive_does_not_consume_first_active_record(self):
        start = (json.dumps(record(event='BLE_FIELD_START_REQUESTED'))+'\n').encode('utf-8')
        secret = b'private-partial-record'
        active_rows = [
            record(wallMs=BASE+100, elapsedMs=1100,
                   bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1}),
            record(wallMs=BASE+200, elapsedMs=1200, event='BLE_FIELD_STOPPED'),
        ]
        active = io.BytesIO(''.join(json.dumps(row)+'\n' for row in active_rows).encode('utf-8'))
        report = analyze(io.BytesIO(start + secret), additional_streams=(active,))
        self.assertEqual(report['counts']['lines'], 4)
        self.assertEqual(report['counts']['analyzedRows'], 3)
        self.assertEqual(report['issues'], [{'line': 2, 'code': 'PARTIAL_LINE'}])
        self.assertEqual(report['trials'][0]['candidateTypedCount'], 1)
        self.assertEqual(report['trials'][0]['termination'], 'normal_stop')
        self.assertNotIn(secret.decode('ascii'), json.dumps(report))

    def test_unterminated_oversized_archive_does_not_swallow_next_file(self):
        active = io.BytesIO((json.dumps(record(event='BLE_FIELD_STOPPED'))+'\n').encode('utf-8'))
        report = analyze(io.BytesIO(b'x' * (MAX_LINE + 1)),
                         additional_streams=(io.BytesIO(b''), active))
        self.assertEqual(report['counts']['lines'], 2)
        self.assertEqual(report['counts']['analyzedRows'], 1)
        self.assertEqual(report['issues'], [{'line': 1, 'code': 'LINE_TOO_LONG'}])
        self.assertEqual(report['trials'][0]['termination'], 'normal_stop')

    def test_v7_scan_fields_remain_unknown(self):
        trial = parse(record(version=7, bleEvidence={
            'kind': 'SCAN_MATCH', 'receivedElapsedMs': 1000,
            'interactive': False, 'deviceLocked': True, 'timerDelayMs': 3000,
        }))['trials'][0]
        self.assertIsNone(trial['scanTimerDelayMs'])
        self.assertIsNone(trial['scanRecoveryCount'])
        self.assertIsNone(trial['scanRecoveryExhaustedCount'])
        self.assertIsNone(trial['scanMatches'][0]['interactive'])
        self.assertIsNone(trial['scanMatches'][0]['deviceLocked'])

    def test_v8_locked_match_keeps_delivery_delay_separate_from_timer_delay(self):
        trial = parse(record(version=8, elapsedMs=22000, wallMs=BASE+21000,
            bleEvidence={'kind': 'SCAN_MATCH', 'receivedElapsedMs': 1000,
                         'queueDelayMs': 21000, 'rssi': -72,
                         'interactive': False, 'deviceLocked': True}),
            record(version=8, elapsedMs=23000, wallMs=BASE+22000,
                bleEvidence={'kind': 'SCAN_TIMER_DELAYED', 'timerDelayMs': 1500}),
        )['trials'][0]
        self.assertEqual(trial['scanMatches'], [{
            'receivedElapsedMs': 1000, 'processedWallMs': BASE+21000,
            'queueDelayMs': 21000, 'rssi': -72,
            'interactive': False, 'deviceLocked': True,
        }])
        self.assertEqual(trial['scanTimerDelayMs']['min'], 1500)
        self.assertEqual(trial['scanTimerDelayMs']['count'], 1)
        self.assertEqual(trial['candidateTypedCount'], 0)

    def test_scan_recovery_does_not_create_gatt_attempts_or_candidates(self):
        rows = [record(version=8, bleEvidence={
            'kind': kind, 'candidate': 99, 'retryCount': retry,
            'scheduledElapsedMs': 121000, 'scanFailureCode': 2,
        }, bleTrial={'attempt': 99, 'status': 'COMPLETE'})
            for kind, retry in [('SCAN_RECOVERY_SCHEDULED', 1),
                                ('SCAN_RECOVERY_SCHEDULED', 2),
                                ('SCAN_RECOVERY_EXHAUSTED', 2)]]
        trial = parse(*rows, record(version=8,
            bleEvidence={'kind': 'RETRY_SCHEDULED'},
            event='BLE_FIELD_TRIAL_FINISHED',
            bleTrial={'attempt': 1, 'status': 'COMPLETE'}))['trials'][0]
        self.assertEqual(trial['scanRecoveryCount'], 2)
        self.assertEqual(trial['scanRecoveryExhaustedCount'], 1)
        self.assertEqual(trial['evidence']['RETRY_SCHEDULED'], 1)
        self.assertEqual(trial['candidateTypedCount'], 0)
        self.assertEqual([a['attempt'] for a in trial['attempts']], [1])
        self.assertEqual(trial['completeAttemptCount'], 1)

    def test_scan_values_reject_invalid_types_ranges_and_strings(self):
        sentinel = 'PRIVATE_SYNTHETIC_VALUE'
        rows = []
        for value in (sentinel, True, -1, 1.5, [], {}, 2**63):
            rows.append(record(version=8, bleEvidence={
                'kind': 'SCAN_MATCH', 'receivedElapsedMs': value,
                'queueDelayMs': value, 'rssi': sentinel,
                'interactive': value, 'deviceLocked': value,
            }))
            rows.append(record(version=8, bleEvidence={
                'kind': 'SCAN_TIMER_DELAYED', 'timerDelayMs': value,
                'scheduledElapsedMs': sentinel, 'retryCount': sentinel,
            }))
        report = parse(*rows)
        trial = report['trials'][0]
        self.assertEqual(trial['scanTimerDelayMs']['count'], 0)
        for match in trial['scanMatches']:
            self.assertIsNone(match['receivedElapsedMs'])
            self.assertIsNone(match['queueDelayMs'])
            self.assertIsNone(match['rssi'])
        self.assertNotIn(sentinel, json.dumps(report, allow_nan=False))
        self.assertIsNone(trial['scanMatches'][0]['interactive'])
        self.assertIsNone(trial['scanMatches'][0]['deviceLocked'])

    def test_scan_matches_do_not_project_across_process_or_clock_boundaries(self):
        report = parse(record(version=8),
            record(version=8, processId=Q, elapsedMs=10, wallMs=BASE+50000,
                bleEvidence={'kind': 'SCAN_MATCH', 'receivedElapsedMs': 1000}),
            record(version=8, processId=Q, elapsedMs=20, wallMs=BASE+60000,
                bleEvidence={'kind': 'SCAN_MATCH', 'receivedElapsedMs': 20}))
        matches = report['trials'][0]['scanMatches']
        self.assertIsNone(matches[0]['receivedElapsedMs'])
        self.assertEqual(matches[1]['receivedElapsedMs'], 20)
        self.assertTrue(report['clockMismatchObserved'])
        self.assertEqual(report['trials'][0]['candidates'], [])

    def test_schema_absence_and_zero_are_distinct(self):
        report = parse(record(version=1, trialId=U, bleTrial={'attempt': 1, 'status': 'COMPLETE'}), record(version=5, bleTrial={'attempt': 1, 'status': 'COMPLETE', 'primaryGattStatus': 0, 'notificationCount': 0}, event='BLE_FIELD_TRIAL_FINISHED'), record())
        old, typed = report['trials']
        self.assertFalse(old['bleTypedSupported'])
        self.assertIsNone(old['candidateTypedCount'])
        self.assertEqual(old['attempts'], [])
        self.assertEqual(typed['finalAttemptCount'], 1)
        final = typed['attempts'][0]['final']
        self.assertEqual(final['primaryGattStatus'], 0)
        self.assertIsNone(final['cleanupGattStatus'])
        self.assertEqual(final['notificationCount'], 0)
        self.assertEqual(typed['speechSummaryRows'], 0)

    def test_scan_registration_failure_is_not_a_detection_or_completed_attempt(self):
        report = parse(
            record(version=7, event='BLE_FIELD_START_REQUESTED',
                   state={'bleFieldObservationOnly': True, 'bleFieldSupplementalScan': True}),
            record(version=7, bleEvidence={'kind': 'SCAN_FAILED', 'scanFailureCode': 4, 'accepted': False}),
            record(version=7, event='BLE_FIELD_STOPPED',
                   state={'bleFieldTrialStopReason': 'BLE_SCAN_PENDING_INTENT_FAILED'}),
        )
        trial = json.loads(json.dumps(report))['trials'][0]
        self.assertEqual(trial['stopReason'], 'BLE_SCAN_PENDING_INTENT_FAILED')
        self.assertEqual(trial['evidenceDimensions']['scanFailureCode'], {'4': 1})
        self.assertEqual(trial['candidateTypedCount'], 0)
        self.assertEqual(trial['completeAttemptCount'], 0)
        self.assertEqual(trial['candidates'], [])

    def test_advertised_name_filter_preserves_legacy_unknown_and_explicit_booleans(self):
        key = 'bleFieldAdvertisedNameFilter'
        for version in range(1, 7):
            with self.subTest(version=version):
                report = parse(record(version=version, event='BLE_FIELD_START_REQUESTED', state={'bleFieldSupplementalScan': True, key: True}))
                self.assertEqual(report['counts']['analyzedRows'], 1)
                self.assertEqual(report['issues'], [])
                trial = report['trials'][0]
                self.assertIsNone(trial['options'][0]['values'][key])
                self.assertEqual(trial['candidateSupported'], version >= 6)
                self.assertEqual(trial['bleTypedSupported'], version >= 5)
        report = parse(
            record(version=7, event='BLE_FIELD_START_REQUESTED', state={'bleFieldSupplementalScan': True}),
            record(version=7, trialId=U, event='BLE_FIELD_START_REQUESTED', state={'bleFieldSupplementalScan': True, key: False}),
            record(version=7, trialId=Q, event='BLE_FIELD_START_REQUESTED', state={'bleFieldSupplementalScan': True, key: True}),
        )
        self.assertEqual(report['versions'], {'7': 3})
        self.assertEqual([t['options'][0]['values'][key] for t in report['trials']], [None, False, True])

    def test_advertised_name_options_stay_with_run_and_missing_values_are_not_filled(self):
        key = 'bleFieldAdvertisedNameFilter'
        name_options = {'bleFieldObservationOnly': True, 'bleFieldSupplementalScan': True, 'bleFieldBtAssist': False, 'bleFieldBackgroundConnect': False, 'bleFieldRetryEnabled': False, key: True}
        address_options = dict(name_options, bleFieldObservationOnly=False, bleFieldAdvertisedNameFilter=False)
        report = parse(
            record(version=7, event='BLE_FIELD_START_REQUESTED', state=name_options),
            record(version=7, event='BLE_FIELD_RUNNING', state=name_options),
            record(version=7, event='BLE_FIELD_STOPPED'),
            record(version=7, processId=Q, trialId=U, event='BLE_FIELD_START_REQUESTED', state=address_options),
            record(version=7, processId=Q, trialId=U, state={'bleFieldSupplementalScan': True}),
            record(version=7, processId=Q, trialId=U, event='BLE_FIELD_STOPPED'),
            record(version=7, processId=Q, trialId=P, event='BLE_FIELD_START_REQUESTED'),
        )
        name, address, unknown = report['trials']
        self.assertEqual(name['options'], [{'line': 1, 'values': dict(name_options, bleFieldAuthenticatedRead=None)}])
        self.assertEqual(address['options'][0], {'line': 4, 'values': dict(address_options, bleFieldAuthenticatedRead=None)})
        self.assertEqual(len(address['options']), 2)
        self.assertIsNone(address['options'][1]['values'][key])
        self.assertIsNone(address['options'][1]['values']['bleFieldObservationOnly'])
        self.assertEqual(unknown['options'], [])
        self.assertEqual(name['termination'], 'normal_stop')
        self.assertEqual(address['termination'], 'normal_stop')

    def test_schema_seven_scan_candidate_survives_json_and_visit_consumers(self):
        report = parse(
            record(version=7, event='BLE_FIELD_START_REQUESTED', state={'bleFieldAdvertisedNameFilter': True}),
            record(version=7, wallMs=BASE+1000, elapsedMs=2000, state={'bleFieldAdvertisedNameFilter': True, 'bleFieldCandidateCount': 1}, bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'signal': 'FILTERED_SCAN', 'candidate': 1, 'receivedElapsedMs': 2000, 'queueDelayMs': 0}),
            record(version=7, wallMs=BASE+1000, elapsedMs=2000, bleEvidence={'kind': 'CANDIDATE_MERGED', 'candidate': 1}),
        )
        restored = json.loads(json.dumps(report, allow_nan=False))
        trial = restored['trials'][0]
        self.assertTrue(trial['candidateSupported'])
        self.assertEqual(trial['candidateTypedCount'], 1)
        self.assertEqual(trial['candidateStateMax'], 1)
        self.assertEqual(trial['evidence'], {'CANDIDATE_SIGNAL': 1, 'CANDIDATE_MERGED': 1})
        self.assertEqual(trial['evidenceDimensions']['signal'], {'FILTERED_SCAN': 1})
        self.assertEqual(trial['finalAttemptCount'], 0)
        result = compare_visits(visits((1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:02Z', '2026-01-01T00:00:03Z')), restored, 0)
        self.assertEqual(result['detectedVisitRate'], 1)
        self.assertEqual(result['beforeHandleRate'], 1)
        self.assertEqual(result['observations'][0]['delayMs'], 1000)

    def test_name_detection_rejection_reasons_remain_typed_without_identifier_echo(self):
        for reason in ('BLE_NAME_DETECTION_OPTIONS_INVALID', 'VIN_FORMAT_INVALID', 'BLE_SCAN_NAME_UNAVAILABLE'):
            with self.subTest(reason=reason):
                report = parse(record(version=7, event='BLE_FIELD_STOPPED', state={'bleFieldTrialStopReason': reason, 'bleFieldAdvertisedNameFilter': True}, bleTrial={'attempt': 1, 'status': 'BLOCKED', 'reason': reason}))
                trial = report['trials'][0]
                self.assertEqual(trial['stopReason'], reason)
                self.assertEqual(trial['attempts'][0]['lastSummary']['reason'], reason)
                self.assertEqual(trial['completeAttemptCount'], 0)

    def test_advertised_name_material_never_reaches_report_or_cli_consumers(self):
        key = 'bleFieldAdvertisedNameFilter'
        secrets = ['S0123456789abcdefC', '0123456789abcdef0123456789abcdef01234567', 'SYNTHETIC-VIN-MATERIAL', '02:00:00:00:00:01', 'SYNTHETIC-NEW-STRING']
        rows = [
            record(version=7, state={'bleFieldSupplementalScan': True, key: value, 'advertisedName': secrets[0], 'hash': secrets[1], 'vin': secrets[2], 'address': secrets[3], 'peerKey': secrets[4]}, appVersion=secrets[4], bleEvidence={'kind': 'SCAN_FAILED', 'signal': secrets[0], 'gate': secrets[1]}, bleTrial={'attempt': 0, 'status': 'BLOCKED', 'reason': secrets[2]})
            for value in secrets + ['BLE_FIELD_RUNNING', 1, 0, None, [], {}]
        ]
        report = parse(*rows)
        self.assertEqual(report['counts']['analyzedRows'], len(rows))
        self.assertEqual(report['issues'], [])
        self.assertEqual(len(report['trials'][0]['options']), 1)
        self.assertIsNone(report['trials'][0]['options'][0]['values'][key])
        encoded = json.dumps(report, allow_nan=False)
        for secret in secrets:
            self.assertNotIn(secret, encoded)
        payload = ''.join(json.dumps(row)+'\n' for row in rows).encode('utf-8')
        for flags in ([], ['--json']):
            with self.subTest(flags=flags):
                out, err = io.StringIO(), io.StringIO()
                with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    code = main(['synthetic-input.jsonl'] + flags)
                self.assertEqual(code, 0)
                for secret in secrets:
                    self.assertNotIn(secret, out.getvalue() + err.getvalue())
                if flags:
                    self.assertEqual(json.loads(out.getvalue()), report)

    def test_stage_is_not_final_and_duplicate_conflict_is_not_success(self):
        stage = record(bleTrial={'attempt': 1, 'status': 'COMPLETE'})
        self.assertEqual(parse(stage)['trials'][0]['finalAttemptCount'], 0)
        final = dict(stage, event='BLE_FIELD_TRIAL_FINISHED')
        report = parse(final, final)
        self.assertEqual(report['trials'][0]['completeAttemptCount'], 1)
        self.assertEqual(report['trials'][0]['finalAttemptCount'], 1)
        conflict = dict(final, bleTrial={'attempt': 1, 'status': 'FAILED'})
        report = parse(final, conflict)
        self.assertEqual(report['trials'][0]['completeAttemptCount'], 0)
        self.assertIn('CONFLICTING_FINAL', [i['code'] for i in report['issues']])

    def test_process_boundary_and_elapsed_regression(self):
        report = parse(record(), record(processId=Q, elapsedMs=20, wallMs=BASE+100000))
        self.assertFalse(report['clockMismatchObserved'])
        report = parse(record(), record(elapsedMs=10, wallMs=BASE+1))
        self.assertTrue(report['clockMismatchObserved'])
        self.assertEqual(report['issues'][0]['code'], 'ELAPSED_REGRESSION')
        report = parse(record(), record(elapsedMs=1010, wallMs=BASE+5000))
        self.assertEqual(report['issues'][0]['code'], 'CLOCK_MISMATCH')

    def test_malformed_future_and_partial_do_not_hide_valid_rows(self):
        text = '{bad}\n' + json.dumps(record(version=40))+'\n'+json.dumps(record())+'\n{"version":'
        report = analyze(io.StringIO(text))
        self.assertEqual(report['counts']['analyzedRows'], 1)
        self.assertEqual(report['counts']['unsupportedRows'], 1)
        self.assertEqual(report['counts']['malformedRows'], 2)
        self.assertEqual([i['code'] for i in report['issues']], ['MALFORMED_JSON', 'UNSUPPORTED_VERSION', 'PARTIAL_LINE'])

    def test_unhashable_event_and_evidence_kind_preserve_following_rows_without_leaks(self):
        secret = 'SYNTHETIC-PRIVATE-VIN-ADDRESS-KEY'
        for field, version in (('event', 21), ('event', 22), ('event', 23), ('event', 24),
                               ('kind', 8), ('kind', 23), ('kind', 24)):
            for invalid in ([secret], {secret: secret}):
                with self.subTest(field=field, version=version, value=invalid):
                    malformed = record(version=version)
                    if field == 'event':
                        malformed['event'] = invalid
                    else:
                        malformed['bleEvidence'] = {'kind': invalid}
                    rows = [
                        malformed,
                        record(version=version, event='BLE_FIELD_TRIAL_FINISHED',
                            wallMs=BASE+1, elapsedMs=1001,
                            bleTrial={'attempt': 1, 'status': 'COMPLETE'},
                            bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1,
                                'receivedElapsedMs': 1001}),
                        uwb(offset=2),
                        record(version=21, trialId=None, event='TESLA_FRUNK_COMMAND_SENT',
                            wallMs=BASE+3, elapsedMs=1003,
                            state={'frunkCommandSent': True}),
                    ]
                    report = parse(*rows)
                    self.assertEqual(report['counts']['analyzedRows'], len(rows))
                    self.assertEqual(report['issues'],
                        [{'line': 1, 'code': 'INVALID_EVIDENCE_KIND'}] if field == 'kind' else [])
                    self.assertEqual(report['trials'][0]['completeAttemptCount'], 1)
                    self.assertEqual(report['trials'][0]['candidateTypedCount'], 1)
                    self.assertEqual([point['line'] for point in report['uwbEvents']], [3])
                    self.assertTrue(report['uwbEvents'][0]['newDistanceReceived'])
                    self.assertEqual([point['line'] for point in report['frunkEvents']], [4])
                    self.assertTrue(report['frunkEvents'][0]['state']['frunkCommandSent'])
                    self.assertNotIn(secret, json.dumps(report, allow_nan=False))
                    payload = ''.join(json.dumps(row)+'\n' for row in rows).encode('utf-8')
                    for flags in ([], ['--json']):
                        out, err = io.StringIO(), io.StringIO()
                        with patch('builtins.open', return_value=io.BytesIO(payload)), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                            code = main(['synthetic-input.jsonl'] + flags)
                        self.assertEqual(code, 1 if field == 'kind' else 0)
                        self.assertNotIn(secret, out.getvalue() + err.getvalue())
                        if flags:
                            self.assertEqual(json.loads(out.getvalue()), report)

    def test_exclusion_removes_trial_and_visit_denominator(self):
        report = parse(record(), record(trialId=U), exclude=(T, Q))
        self.assertEqual([t['trialId'] for t in report['trials']], [U])
        self.assertEqual(report['excludedTrialsFound'], [T])
        self.assertEqual(report['excludedTrialsNotFound'], [Q])
        result = compare_visits(visits((1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:02Z'), (2, U, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:02Z')), report, 0, (T,))
        self.assertEqual(result['validVisitCount'], 1)
        self.assertEqual(result['excludedVisitCount'], 1)

    def test_excluding_trial_does_not_hide_clock_changes_or_split_valid_attempts(self):
        final = {'attempt': 1, 'status': 'COMPLETE'}
        stable = parse(record(bleTrial={'attempt': 1, 'status': 'CONNECTING'}),
            record(trialId=U, wallMs=BASE+100, elapsedMs=1100),
            record(wallMs=BASE+200, elapsedMs=1200, event='BLE_FIELD_TRIAL_FINISHED', bleTrial=final),
            exclude=(U,))
        self.assertEqual(stable['issues'], [])
        self.assertEqual(stable['trials'][0]['completeAttemptCount'], 1)
        for wall, elapsed, code in ((BASE+10000, 1100, 'CLOCK_MISMATCH'), (BASE+100, 10, 'ELAPSED_REGRESSION')):
            with self.subTest(code=code):
                report = parse(record(), record(trialId=U, wallMs=wall, elapsedMs=elapsed),
                    record(wallMs=wall+100, elapsedMs=elapsed+100,
                        bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1}), exclude=(U,))
                self.assertIn(code, [i['code'] for i in report['issues']])
                self.assertEqual(report['counts']['excludedRows'], 1)
                self.assertEqual([t['trialId'] for t in report['trials']], [T])
                result = compare_visits(visits((1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:11Z', '2026-01-01T00:00:12Z')), report, 0)
                self.assertIsNone(result['detectedVisitRate'])
                self.assertFalse(result['clockAligned'])

    def test_privacy_unknown_state_enums_and_identifiers(self):
        secret = 'VIN-secret-address-speech-exception'
        report = parse(record(processId=secret, trialId=secret, state={'unknown': secret}), record(event=secret, bleTrial={'attempt': 1, 'status': secret, 'reason': secret}, bleEvidence={'kind': 'SCAN_FAILED', 'gate': secret}))
        encoded = json.dumps(report)
        self.assertNotIn(secret, encoded)
        self.assertIsNone(report['trials'][0]['attempts'][0]['lastSummary']['status'])
        out = io.StringIO()
        with contextlib.redirect_stderr(out):
            code = main(['--unknown-secret-option', secret])
        self.assertEqual(code, 2)
        self.assertNotIn(secret, out.getvalue())

    def test_received_time_projection_and_phase_not_visit_delay(self):
        report = parse(record(wallMs=BASE+2000, elapsedMs=5000, bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1, 'receivedElapsedMs': 4000}, bleTrial={'attempt': 1, 'elapsedMs': 999, 'phaseElapsedMs': 777}))
        row = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01.500Z', '2026-01-01T00:00:03Z')
        result = compare_visits(visits(row), report, 0)
        observation = result['observations'][0]
        self.assertEqual(observation['delayMs'], 1000)
        self.assertTrue(observation['beforeHandle'])
        self.assertEqual(observation['firstCandidateRecordedAt'], BASE+2000)
        self.assertEqual(observation['candidateTimeSource'], 'received_elapsed_projection')

    def test_offset_missing_and_explicit_offset_have_different_meaning(self):
        report = parse(record(wallMs=BASE+1000, bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1}))
        row = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')
        unknown = compare_visits(visits(row), report)
        self.assertIsNone(unknown['detectedVisitRate'])
        self.assertIsNone(unknown['observations'][0]['delayMs'])
        aligned = compare_visits(visits(row), report, 1000)
        self.assertEqual(aligned['detectedVisitRate'], 1)

    def test_overlap_and_duplicate_ids_reject_all_participants(self):
        report = parse(record())
        a = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:02Z')
        b = (2, T, '2026-01-01T00:00:02Z', '2026-01-01T00:00:03Z', '2026-01-01T00:00:04Z')
        result = compare_visits(visits(a, b), report, 0)
        self.assertEqual(result['validVisitCount'], 0)
        self.assertIsNone(result['detectedVisitRate'])
        c = (1, U, '2026-01-01T00:00:03Z', '2026-01-01T00:00:04Z', '2026-01-01T00:00:05Z')
        self.assertEqual(compare_visits(visits(a, c), report)['validVisitCount'], 0)

    def test_incomplete_timezone_and_reverse_visit_are_invalid(self):
        report = parse(record())
        bad = [(1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', ''), (2, T, '2026-01-01T00:00:00', '2026-01-01T00:00:01Z', '2026-01-01T00:00:02Z'), (3, T, '2026-01-01T00:00:02Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:03Z')]
        result = compare_visits(visits(*bad), report, 0)
        self.assertEqual(result['validVisitCount'], 0)
        self.assertEqual([e['code'] for e in result['errors']], ['INCOMPLETE_VISIT', 'INVALID_VISIT', 'INVALID_VISIT'])

    def test_attempt_cannot_merge_across_reboot(self):
        first = record(bleTrial={'attempt': 1, 'status': 'CONNECTING'})
        final = record(elapsedMs=10, wallMs=BASE+10, event='BLE_FIELD_TRIAL_FINISHED', bleTrial={'attempt': 1, 'status': 'COMPLETE'})
        report = parse(first, final)
        self.assertEqual(report['trials'][0]['completeAttemptCount'], 0)
        self.assertIn('ATTEMPT_TIME_BOUNDARY', [i['code'] for i in report['issues']])

    def test_clock_mismatch_prevents_visit_success(self):
        report = parse(record(), record(wallMs=BASE+10000, elapsedMs=1010, bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1}))
        row = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:11Z', '2026-01-01T00:00:12Z')
        result = compare_visits(visits(row), report, 0)
        self.assertIsNone(result['detectedVisitRate'])
        self.assertEqual(result['observations'][0]['detection'], 'clock_mismatch_unverified')

    def test_invalid_received_time_cannot_become_a_successful_visit(self):
        row = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:02Z', '2026-01-01T00:00:03Z')
        for received in (-1, 2001, True, '2000', 1.5, 2**63):
            with self.subTest(received=received):
                report = parse(record(wallMs=BASE+1000, elapsedMs=2000,
                    bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1, 'receivedElapsedMs': received}))
                result = compare_visits(visits(row), json.loads(json.dumps(report)), 0)
                self.assertIn('INVALID_RECEIVED_TIME', [i['code'] for i in report['issues']])
                self.assertIsNone(result['detectedVisitRate'])
                self.assertIsNone(result['beforeHandleRate'])
                self.assertIsNone(result['observations'][0]['delayMs'])
                self.assertFalse(result['clockAligned'])

    def test_missing_received_time_keeps_explicit_record_wall_fallback(self):
        report = parse(record(wallMs=BASE+1000, elapsedMs=2000,
            bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1, 'receivedElapsedMs': None}))
        result = compare_visits(visits((1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:02Z', '2026-01-01T00:00:03Z')), report, 0)
        self.assertEqual(report['issues'], [])
        self.assertEqual(result['detectedVisitRate'], 1)
        self.assertEqual(result['observations'][0]['candidateTimeSource'], 'record_wall')
        self.assertEqual(result['observations'][0]['delayMs'], 1000)

    def test_zero_idle_blocked_snapshot_then_first_final(self):
        idle = record(bleTrial={'attempt': 0, 'status': 'IDLE', 'phaseElapsedMs': None})
        blocked = record(bleTrial={'attempt': 0, 'status': 'BLOCKED', 'phaseElapsedMs': None})
        snapshots = parse(idle, blocked)
        self.assertEqual(snapshots['issues'], [])
        self.assertEqual(snapshots['trials'][0]['attempts'], [])
        final = record(event='BLE_FIELD_TRIAL_FINISHED', bleTrial={'attempt': 1, 'status': 'COMPLETE', 'primaryGattStatus': 0})
        report = parse(idle, blocked, final)
        self.assertEqual(report['issues'], [])
        self.assertEqual([a['attempt'] for a in report['trials'][0]['attempts']], [1])
        self.assertEqual(report['trials'][0]['completeAttemptCount'], 1)

    def test_invalid_attempt_counters_still_warn(self):
        for attempt, status, event in ((-1, 'IDLE', 'BLE_EVIDENCE'), (None, 'BLOCKED', 'BLE_EVIDENCE'), (0, 'COMPLETE', 'BLE_FIELD_TRIAL_FINISHED'), (0, 'IDLE', 'BLE_FIELD_TRIAL_FINISHED'), (0, 'CONNECTING', 'BLE_EVIDENCE')):
            with self.subTest(attempt=attempt, status=status, event=event):
                report = parse(record(event=event, bleTrial={'attempt': attempt, 'status': status}))
                self.assertEqual(report['issues'], [{'line': 1, 'code': 'INVALID_ATTEMPT'}])
                self.assertEqual(report['trials'][0]['attempts'], [])

    def test_candidate_equal_to_handle_is_not_before_handle(self):
        report = parse(record(wallMs=BASE+1000, bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 1}))
        row = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:02Z')
        result = compare_visits(visits(row), report, 0)
        self.assertEqual(result['detectedVisitRate'], 1)
        self.assertFalse(result['observations'][0]['beforeHandle'])
        self.assertEqual(result['beforeHandleRate'], 0)

    def test_binary_log_invalid_utf8_middle_and_partial_last(self):
        first = json.dumps(record()).encode('utf-8') + b'\n'
        last_valid = json.dumps(record(trialId=U)).encode('utf-8') + b'\n'
        secret = b'private-address-speech'
        report = analyze(io.BytesIO(first + secret + b'\xff\n' + last_valid + secret + b'\xe3\x81'))
        self.assertEqual(report['counts']['analyzedRows'], 2)
        self.assertEqual({t['trialId'] for t in report['trials']}, {T, U})
        self.assertEqual(report['issues'], [{'line': 2, 'code': 'INVALID_UTF8'}, {'line': 4, 'code': 'INVALID_UTF8'}])
        self.assertNotIn(secret.decode('ascii'), json.dumps(report))

    def test_binary_csv_invalid_utf8_preserves_valid_rows(self):
        report = parse(record())
        rows = visits((1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:02Z'), (2, U, '2026-01-01T00:00:03Z', '2026-01-01T00:00:04Z', '2026-01-01T00:00:05Z')).getvalue().encode('utf-8').splitlines(keepends=True)
        secret = b'private-vin'
        result = compare_visits(io.BytesIO(rows[0] + rows[1] + secret + b'\xff\n' + rows[2] + secret + b'\xe3\x81'), report)
        self.assertEqual([o['visitId'] for o in result['observations']], [1, 2])
        self.assertEqual(result['errors'], [{'line': 3, 'code': 'INVALID_UTF8'}, {'line': 5, 'code': 'INVALID_UTF8'}])
        self.assertNotIn(secret.decode('ascii'), json.dumps(result))
        header_error = compare_visits(io.BytesIO(secret + b'\xff\n'), report)
        self.assertEqual(header_error['errors'], [{'line': 1, 'code': 'INVALID_UTF8'}])
        self.assertNotIn(secret.decode('ascii'), json.dumps(header_error))

    def test_cross_trial_overlap_rejects_all_unexcluded_records(self):
        report = parse(record(), record(trialId=U))
        a = (1, T, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', '2026-01-01T00:00:03Z')
        b = (2, U, '2026-01-01T00:00:02Z', '2026-01-01T00:00:03Z', '2026-01-01T00:00:04Z')
        result = compare_visits(visits(a, b), report, 0)
        self.assertEqual(result['validVisitCount'], 0)
        self.assertIsNone(result['detectedVisitRate'])
        self.assertEqual(result['errors'], [{'line': 3, 'code': 'OVERLAPPING_VISIT'}])
        remaining = compare_visits(visits(a, b), report, 0, (T,))
        self.assertEqual(remaining['errors'], [])
        self.assertEqual(remaining['excludedVisitCount'], 1)
        self.assertEqual([o['visitId'] for o in remaining['observations']], [2])

    def test_mixed_trial_report_json_roundtrip_preserves_aggregates(self):
        secret = 'private-address-vin-speech'
        report = parse(
            record(version=5, trialId=U, processId=Q, event='BLE_FIELD_STAGE', bleTrial={'attempt': 1, 'status': 'CONNECTING'}),
            record(version=5, trialId=U, processId=Q, event='BLE_FIELD_TRIAL_FINISHED', bleTrial={'attempt': 1, 'status': 'COMPLETE', 'primaryGattStatus': 0, 'notificationCount': 0}),
            record(version=5, trialId=U, event='BLE_FIELD_TRIAL_FINISHED', bleTrial={'attempt': 2, 'status': 'FAILED', 'reason': 'CONNECTION_FAILED'}),
            record(bleEvidence={'kind': 'CANDIDATE_SIGNAL', 'candidate': 7, 'queueDelayMs': 20}, state={'unknown': secret}),
            record(bleEvidence={'kind': 'CANDIDATE_MERGED', 'candidate': 7, 'queueDelayMs': 40, 'unknown': secret}),
            record(event='BLE_FIELD_TRIAL_FINISHED', bleTrial={'attempt': 1, 'status': 'COMPLETE', 'subscriptionConfirmed': True}, appVersion=secret),
        )
        encoded = json.dumps(report, allow_nan=False)
        restored = json.loads(encoded)
        self.assertEqual(restored, report)
        self.assertNotIn(secret, encoded)
        trials = {t['trialId']: t for t in restored['trials']}
        old = trials[U]
        self.assertEqual(old['processIds'], sorted([P, Q]))
        self.assertEqual(old['versions'], [5])
        self.assertEqual(old['finalAttemptCount'], 2)
        self.assertEqual(old['completeAttemptCount'], 1)
        self.assertIsNone(old['candidateTypedCount'])
        self.assertIsNone(old['evidence'])
        self.assertEqual(old['attempts'][0]['final']['primaryGattStatus'], 0)
        self.assertIsNone(old['attempts'][0]['final']['cleanupGattStatus'])
        current = trials[T]
        self.assertEqual(current['candidateTypedCount'], 1)
        self.assertEqual(current['evidence'], {'CANDIDATE_SIGNAL': 1, 'CANDIDATE_MERGED': 1})
        self.assertEqual(current['queueDelayMs']['mean'], 30)
        self.assertEqual(current['finalAttemptCount'], 1)
        self.assertEqual(current['completeAttemptCount'], 1)


if __name__ == '__main__':
    unittest.main()
