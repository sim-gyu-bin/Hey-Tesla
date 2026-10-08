"""파일 생성/삭제 없이 StringIO로 검증하는 소비자 계약 회귀."""
import contextlib
import io
import json
import unittest
from unittest.mock import patch

from analyze_field_log import analyze, compare_visits, main

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


class AnalysisTests(unittest.TestCase):
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
        self.assertEqual(name['options'], [{'line': 1, 'values': name_options}])
        self.assertEqual(address['options'][0], {'line': 4, 'values': address_options})
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
        text = '{bad}\n' + json.dumps(record(version=13))+'\n'+json.dumps(record())+'\n{"version":'
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
