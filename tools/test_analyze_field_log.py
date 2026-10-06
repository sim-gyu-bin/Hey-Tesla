"""파일 생성/삭제 없이 StringIO로 검증하는 소비자 계약 회귀."""
import contextlib
import io
import json
import unittest

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
