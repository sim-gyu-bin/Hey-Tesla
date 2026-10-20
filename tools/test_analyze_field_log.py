"""파일 생성/삭제 없이 StringIO로 검증하는 소비자 계약 회귀."""
import contextlib
import io
import json
import unittest
from unittest.mock import patch

from analyze_field_log import MAX_LINE, analyze, compare_visits, main

P = '11111111-1111-4111-8111-111111111111'
Q = '22222222-2222-4222-8222-222222222222'
T = '33333333-3333-4333-8333-333333333333'
U = '44444444-4444-4444-8444-444444444444'
BASE = 1767225600000
HEADER = 'visitId,trialId,approachAt,handleAt,departAt\n'


def record(**changes):
    row = dict(version=6, processId=P, trialId=T, wallMs=BASE, elapsedMs=1000, event='BLE_EVIDENCE', state={})
    row.update(changes)
    return row


def parse(*rows, exclude=()):
    return analyze(io.StringIO(''.join(json.dumps(row)+'\n' for row in rows)), exclude)


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


class AnalysisTests(unittest.TestCase):
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
        for first_version, second_version in ((18, 18), (19, 19), (20, 20), (21, 21), (22, 22), (21, 22), (22, 21), (20, 21), (21, 20), (18, 20), (20, 19), (18, 19), (19, 18), (17, 17)):
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
        text = '{bad}\n' + json.dumps(record(version=23))+'\n'+json.dumps(record())+'\n{"version":'
        report = analyze(io.StringIO(text))
        self.assertEqual(report['counts']['analyzedRows'], 1)
        self.assertEqual(report['counts']['unsupportedRows'], 1)
        self.assertEqual(report['counts']['malformedRows'], 2)
        self.assertEqual([i['code'] for i in report['issues']], ['MALFORMED_JSON', 'UNSUPPORTED_VERSION', 'PARTIAL_LINE'])

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
