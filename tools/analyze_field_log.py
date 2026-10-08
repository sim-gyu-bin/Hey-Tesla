#!/usr/bin/env python3
"""읽기 전용 v1–v7 현장 로그 분석. 원문 문자열은 출력 계약에 포함하지 않는다."""
import argparse
import csv
import json
import sys
import uuid
from collections import Counter
from datetime import datetime, timezone

MAX_LINE = 65536
STATUSES = set('IDLE BLOCKED CONNECTING DISCOVERING SUBSCRIBING OBSERVING CLEANING_UP COMPLETE CANCELED TIMED_OUT FAILED CLEANUP_FAILED'.split())
REASONS = set('INTERNAL_FAILURE SESSION_EXPIRED CONNECT_REQUEST_FAILED USER_CANCELED SUBSCRIPTION_OBSERVED CONNECTION_LOST CONNECTION_FAILED DISCOVERY_REQUEST_FAILED DISCOVERY_FAILED PROFILE_READ_FAILED TESLA_SERVICE_MISSING TESLA_TX_MISSING TESLA_RX_MISSING RX_SUBSCRIPTION_UNSUPPORTED RX_CCCD_MISSING LOCAL_SUBSCRIPTION_FAILED SUBSCRIPTION_REQUEST_FAILED SUBSCRIPTION_FAILED LOCAL_CLOSE_FAILED_RESTART_REQUIRED CONNECTING_TIMEOUT DISCOVERING_TIMEOUT SUBSCRIBING_TIMEOUT OBSERVING_TIMEOUT LEASE_UNAVAILABLE LEASE_LOST VISIBLE_UI_REQUIRED BLUETOOTH_PERMISSION_REQUIRED BLUETOOTH_PERMISSION_REVOKED BLUETOOTH_UNAVAILABLE BLUETOOTH_OFF SINGLE_ASSOCIATION_REQUIRED ASSOCIATION_ADDRESS_UNAVAILABLE ASSOCIATION_RESOLVE_FAILED ASSOCIATION_REMOVED DIAGNOSTIC_EXCLUSIVITY_LOST PREREQUISITE_READ_FAILED USER_STOP DISABLED DEPARTED BLE_FIELD_NOTIFICATION_STOP FIELD_OWNER_NOT_ARMED OBSERVATION_ACTIVE FIELD_MARKER_UNAVAILABLE FIELD_LOG_UNHEALTHY FIELD_MARKER_WRITE_FAILED CDM_UNSUPPORTED CDM_UNAVAILABLE ASSOCIATION_REQUIRED MULTIPLE_ASSOCIATIONS_UNSUPPORTED OBSERVATION_BLUETOOTH_PERMISSION_REQUIRED OBSERVATION_NOTIFICATION_PERMISSION_REQUIRED OBSERVATION_NOTIFICATIONS_BLOCKED OBSERVATION_FGS_START_TIMEOUT OBSERVATION_FGS_SECURITY_DENIED OBSERVATION_FGS_BACKGROUND_START_DENIED OBSERVATION_FGS_START_FAILED OBSERVE_SECURITY_DENIED OBSERVE_UNAVAILABLE OBSERVATION_SERVICE_DESTROYED BLUETOOTH_SCAN_PERMISSION_REQUIRED BLE_SCAN_ADDRESS_UNAVAILABLE BLE_SCAN_PERMISSION_REQUIRED BLE_SCAN_PERMISSION_REVOKED BLE_SCAN_NO_OFFLOADED_FILTER BLE_SCAN_UNAVAILABLE BLE_SCAN_START_FAILED BLE_SCAN_STOP_FAILED BLE_SCAN_CALLBACK_FAILED BLE_NAME_DETECTION_OPTIONS_INVALID VIN_FORMAT_INVALID BLE_SCAN_NAME_UNAVAILABLE'.split())
REASONS.update(('BLE_SCAN_NO_OFFLOADED_BATCH', 'BLE_SCAN_PENDING_INTENT_FAILED',
                'BLE_SCAN_DELIVERY_FAILED', 'BLE_SCAN_ORPHAN_CLEANUP_FAILED'))
KINDS = set('PRESENCE_RECEIVED PRESENCE_DISPATCHED PRESENCE_REJECTED CANDIDATE_SIGNAL CANDIDATE_MERGED CANDIDATE_EXPIRED SIGNAL_SHADOWED SIGNAL_SELF_SUPPRESSED SCAN_STARTED SCAN_STOPPED SCAN_FAILED SCAN_MATCH RETRY_SCHEDULED RETRY_SUPPRESSED GATT_REQUEST GATT_CALLBACK GATT_CALLBACK_IGNORED GATT_FIRST_RX'.split())
ENUMS = {
    'signal': set('CDM_BLE_APPEARED CDM_BLE_DISAPPEARED BT_CONNECTED BT_DISCONNECTED FILTERED_SCAN'.split()),
    'action': set('CONNECT DISCOVER PROFILE NOTIFICATION_ON NOTIFICATION_OFF CCCD_ON CCCD_OFF CCCD_READ DISCONNECT CLOSE CONNECTION_CALLBACK SERVICES_CALLBACK DESCRIPTOR_WRITE_CALLBACK DESCRIPTOR_READ_CALLBACK RX_CALLBACK'.split()),
    'gate': set('UNARMED STOPPING WRONG_ASSOCIATION NOT_OBSERVING OWNER_MISSING UNSUPPORTED DUPLICATE LOCAL_GATT RECENT_LOCAL_GATT COOLDOWN STALE PERMISSION UNAVAILABLE NO_OFFLOADED_FILTER BUDGET SUCCESS BLOCKED CLEANUP_FAILED OBSERVATION_ONLY'.split()),
    'result': set('ACCEPTED REJECTED COMPLETED EXCEPTION'.split()),
    'phase': set('CONNECTING DISCOVERING SUBSCRIBING OBSERVING CLEANING_UP'.split()),
}
OPTIONS = ('bleFieldObservationOnly', 'bleFieldSupplementalScan', 'bleFieldBtAssist', 'bleFieldBackgroundConnect', 'bleFieldRetryEnabled', 'bleFieldAdvertisedNameFilter')
SUMMARY_NUMBERS = ('gattStatus', 'primaryGattStatus', 'cleanupGattStatus', 'elapsedMs', 'phaseElapsedMs', 'firstRxElapsedMs', 'notificationCount', 'batteryPercent')
SUMMARY_BOOLS = ('connected', 'serviceFound', 'txFound', 'rxFound', 'subscriptionConfirmed', 'remoteUnsubscribeConfirmed', 'disconnectConfirmed', 'localClosed', 'leaseRetained', 'interactive', 'deviceLocked', 'backgroundConnect')
EVENTS = set('BLE_FIELD_START_REQUESTED BLE_FIELD_RUNNING BLE_FIELD_STOPPED BLE_FIELD_PREVIOUS_RUN_UNCLOSED BLE_FIELD_MARKER_READ_FAILED BLE_FIELD_MARKER_WRITE_FAILED BLE_FIELD_TRIAL_FINISHED BLE_FIELD_CLEANUP_FAILED BLE_FIELD_START_REJECTED BLE_EVIDENCE PROCESS_START_OFF'.split())


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


def analyze(stream, exclude=()):
    excluded = set(exclude)
    found = set()
    issues = []
    versions = Counter()
    processes = Counter()
    trials = {}
    totals = Counter()
    previous = None
    segment = 0
    clock_bad = False
    def issue(line, code):
        issues.append({'line': line, 'code': code})
    for line_no, text, error in bounded_lines(stream):
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
        if version not in range(1, 8):
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
        if trial is None or trial == 'invalid' or process == 'invalid':
            totals['unassignedRows'] += 1
            continue
        t = trials.setdefault(trial, {'trialId': trial, 'processIds': set(), 'versions': set(), 'rows': 0, 'firstStartWallMs': None, 'lastStopWallMs': None, 'unclosedMarkers': 0, 'markerErrors': 0, 'options': [], 'attempts': {}, 'candidateIds': set(), 'candidateStateMax': None, 'candidates': {}, 'evidence': Counter(), 'evidenceDimensions': {}, 'queue': Counter(), 'speechSummaryRows': 0})
        t['rows'] += 1
        t['processIds'].add(process)
        t['versions'].add(version)
        event = allowed(row.get('event'), EVENTS)
        state = row.get('state') if isinstance(row.get('state'), dict) else {}
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
        opts = {key: state.get(key) if type(state.get(key)) is bool and (key != 'bleFieldAdvertisedNameFilter' or version >= 7) else None for key in OPTIONS}
        if any(v is not None for v in opts.values()) and (not t['options'] or t['options'][-1]['values'] != opts):
            t['options'].append({'line': line_no, 'values': opts})
        if version >= 6:
            maximum = integer(state.get('bleFieldCandidateCount'))
            if maximum is not None and maximum >= 0:
                t['candidateStateMax'] = max(t['candidateStateMax'] or 0, maximum)
        if version >= 2 and isinstance(row.get('speechTrial'), dict):
            t['speechSummaryRows'] += 1
        raw = row.get('bleTrial')
        if version >= 5 and isinstance(raw, dict):
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
            if kind is None:
                issue(line_no, 'INVALID_EVIDENCE_KIND')
                continue
            t['evidence'][kind] += 1
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
            if candidate is not None and candidate > 0:
                t['candidateIds'].add((process, segment, candidate))
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
        t['bleTypedSupported'] = any(v >= 5 for v in t['versions'])
        t['candidateSupported'] = any(v >= 6 for v in t['versions'])
        candidate_count = len(t.pop('candidateIds'))
        t['candidateTypedCount'] = candidate_count if t['candidateSupported'] else None
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
    return {'reportVersion': 1, 'counts': {key: totals[key] for key in ('lines', 'analyzedRows', 'malformedRows', 'unsupportedRows', 'excludedRows', 'unassignedRows')}, 'versions': dict(versions), 'processes': dict(processes), 'trials': output_trials, 'issues': issues, 'excludedTrialsFound': sorted(found), 'excludedTrialsNotFound': sorted(excluded - found), 'clockMismatchObserved': clock_bad, 'logHealth': {'status': 'warnings' if issues else 'no_observed_parse_warnings', 'lossCount': None, 'writerHealth': 'not_in_record_schema', 'completeness': 'not_proven'}, 'visits': None}


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
    parser = PrivateParser(description='현장 로그 읽기 전용 분석; COMPLETE는 차량 인증/프렁크 성공이 아닙니다.')
    parser.add_argument('log', metavar='LOG')
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
        with open(args.log, 'rb') as stream:
            report = analyze(stream, exclusions)
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
        print('로그 유실 수/기록기 건강: 미확인. COMPLETE·구독·0 RX는 차량 인증/거리/프렁크 성공의 증거가 아닙니다.')
        termination_labels = {'normal_stop': '정상 정지', 'abnormal_marker': '비정상 종료 표식', 'stop_not_recorded': '종료 미기록'}
        for trial in report['trials']:
            print(f"시험 {trial['trialId']} · 종료 {termination_labels[trial['termination']]} · 최종 회차 {trial['finalAttemptCount']} · COMPLETE {trial['completeAttemptCount']} · 후보 {trial['candidateTypedCount'] if trial['candidateSupported'] else '미지원'}")
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
