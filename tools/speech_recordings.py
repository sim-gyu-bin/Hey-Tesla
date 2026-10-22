#!/usr/bin/env python3
"""기기(no_backup/speech-recordings)에 남은 개발 시험 음성 녹음을 USB로 회수하고 재시험한다.

회수(list/fetch)는 읽기 전용이다. 기기에는 `find`, `stat`/`wc`, `cat`만
`adb exec-out run-as`로 실행하며 기기 파일을 쓰거나 지우지 않는다. `replay`는
확정된 계측 테스트(`SpeechRecordingReplayDeviceTest#replaySavedRecording`)만
실행하고, 테스트는 저장된 오디오를 Android 온디바이스 STT로 다시 인식해 결과를 `runs/`에 추가한다.
차량 BLE/마이크 경로는 사용하지 않는다.

원격 레이아웃(앱의 SpeechRecordingStore 계약):
    no_backup/speech-recordings/<UUID>/capture.json
    no_backup/speech-recordings/<UUID>/audio.wav          # 16 kHz mono PCM16 WAV
    no_backup/speech-recordings/<UUID>/runs/<UUID>.json   # 재시험 원문 결과

`capture.json`은 메타데이터이며, 폴더에 이 파일이 없거나 완료되지 않은 상태면
목록에서 제외한다. 시각은 capturedAt/createdAt/startedAt/recordedAt(문자열 또는
epoch 초/밀리초) 계열 키를 방어적으로 읽는다. `id`/`recordingId`가 있으면 폴더
UUID와 일치해야 한다.

사용 예:
    python3 tools/speech_recordings.py --serial R58M12345 list
    python3 tools/speech_recordings.py --serial R58M12345 \
        fetch --id 3f2c... --output .local-recordings
    python3 tools/speech_recordings.py --serial R58M12345 replay --id 3f2c...

`list`는 ID/시각/바이트만 JSON으로 출력한다. `fetch`는 선택한 폴더 아래에
`<UUID>/...` 구조로 파일을 만들고, 같은 내용의 기존 파일은 그대로 둔다. 내용이
다른 기존 파일이 있으면 덮어쓰지 않고 실패한다(부분 회수는 재실행으로 이어감).
`replay`는 adb 종료 코드 0과 `OK (1 test)`가 모두 확인될 때만 성공을 출력하며,
계측 출력(전사 원문 포함 가능)은 표시하지 않는다. 재시험 뒤 `fetch`로
`runs/<UUID>.json`을 회수한다. 원문 결과(transcript)는 stdout에 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import stat as stat_module
import struct
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

APP_PACKAGE = "com.heytesla.app"
REMOTE_ROOT = "no_backup/speech-recordings"
CAPTURE_FILE = "capture.json"
AUDIO_FILE = "audio.wav"
RUNS_DIR = "runs"

# SpeechMicCapture.MAX_BYTES. WAV 헤더/추가 청크를 감안해 파일 전체는 조금 더 허용한다.
MAX_PCM_BYTES = 320_000
MAX_AUDIO_FILE_BYTES = 1_000_000
MAX_METADATA_BYTES = 262_144
MAX_RESULT_BYTES = 1_048_576
MAX_LISTING_BYTES = 1_048_576
MAX_STAT_BYTES = 64
MAX_INSTRUMENTATION_BYTES = 1_048_576

# 메인 에이전트가 확정한 재시험 계측 대상. 기기 테스트는 load→decode→saveResult만 한다.
REPLAY_TEST_CLASS = (
    "com.heytesla.app.SpeechRecordingReplayDeviceTest#replaySavedRecording"
)
REPLAY_RUNNER = "com.heytesla.app.test/androidx.test.runner.AndroidJUnitRunner"
_OK_RESULT_RE = re.compile(r"^OK \(1 test\)$")

_UUID_RE = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
_SAFE_COMPONENT_RE = re.compile(r"[A-Za-z0-9._-]+")
_SERIAL_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

_ID_KEYS = ("id", "recordingId", "recording_id")
_INCOMPLETE_STATUSES = {
    "INCOMPLETE",
    "CAPTURING",
    "RECORDING",
    "FAILED",
    "ABORTED",
    "CANCELED",
    "CANCELLED",
    "TIMED_OUT",
}
_TIME_KEYS = (
    "capturedAt",
    "createdAt",
    "startedAt",
    "recordedAt",
    "createdAtMs",
    "capturedAtEpochMs",
    "createdAtEpochMs",
    "recordedAtEpochMs",
    "epochMs",
    "timestampMs",
    "timestamp",
)

Runner = Callable[[Sequence[str], int], Tuple[int, bytes, bytes]]


class SpeechRecordingError(Exception):
    """CLI가 사용자에게 그대로 보여 줄 수 있는 오류."""


class ValidationError(SpeechRecordingError):
    """UUID·경로·메타데이터·WAV 형식이 계약과 다름."""


class RemoteError(SpeechRecordingError):
    """adb/run-as 실행 실패 또는 원격 읽기 실패."""


class ConflictError(SpeechRecordingError):
    """로컬에 같은 경로의 다른 파일이 있어 덮어쓸 수 없음."""


def normalize_uuid(value: str) -> str:
    """표준 형식 UUID만 소문자로 정규화한다. 경로 구분자/`..`는 허용하지 않는다."""
    if not isinstance(value, str) or not _UUID_RE.fullmatch(value):
        raise ValidationError("UUID 형식이 아닙니다")
    return value.lower()


def normalize_serial(value: str) -> str:
    if not isinstance(value, str) or not _SERIAL_RE.fullmatch(value):
        raise ValidationError("adb 시리얼 형식이 올바르지 않습니다")
    return value


def remote_path(recording_id: str, *parts: str) -> str:
    """검증된 UUID와 고정 파일명만으로 원격 상대 경로를 만든다."""
    recording = normalize_uuid(recording_id)
    for part in parts:
        if not isinstance(part, str) or not _SAFE_COMPONENT_RE.fullmatch(part):
            raise ValidationError("원격 경로 조각이 허용되지 않습니다")
        if part in (".", ".."):
            raise ValidationError("원격 경로 조각이 허용되지 않습니다")
    path = "/".join((REMOTE_ROOT, recording) + parts)
    if not path.startswith(REMOTE_ROOT + "/") or os.path.normpath(path) != path:
        raise ValidationError("원격 경로가 recordings 루트를 벗어납니다")
    return path


@dataclass(frozen=True)
class RecordingInventory:
    recording_id: str
    has_capture: bool = False
    has_audio: bool = False
    runs: Tuple[str, ...] = ()


def _default_runner(command: Sequence[str], limit: int) -> Tuple[int, bytes, bytes]:
    """stdout을 상한까지만 읽어 원격 파일이 메모리를 무한히 쓰지 않게 한다."""
    with subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ) as process:
        assert process.stdout is not None and process.stderr is not None
        output = process.stdout.read(limit + 1)
        if len(output) > limit:
            process.kill()
            process.communicate()
            raise RemoteError(f"원격 출력이 {limit}바이트 상한을 넘었습니다")
        remainder, error = process.communicate()
        output += remainder
        return process.returncode, output, error or b""


class AdbRemote:
    """`adb -s SERIAL exec-out run-as com.heytesla.app ...` 읽기 전용 래퍼."""

    def __init__(
        self,
        adb: str,
        serial: str,
        package: str = APP_PACKAGE,
        runner: Optional[Runner] = None,
    ) -> None:
        if not isinstance(adb, str) or not adb.strip():
            raise ValidationError("adb 실행 경로가 비어 있습니다")
        self._adb = adb
        self._serial = normalize_serial(serial)
        self._package = package
        self._runner = runner or _default_runner

    def _exec(self, arguments: Sequence[str], limit: int) -> bytes:
        command = [
            self._adb,
            "-s",
            self._serial,
            "exec-out",
            "run-as",
            self._package,
            *arguments,
        ]
        code, output, error = self._runner(command, limit)
        if len(output) > limit:
            raise RemoteError(f"원격 출력이 {limit}바이트 상한을 넘었습니다")
        if code != 0:
            detail = error.decode("utf-8", "replace").strip()
            if len(detail) > 300:
                detail = detail[:300] + "..."
            suffix = f": {detail}" if detail else ""
            raise RemoteError(f"adb 명령이 실패했습니다 (exit {code}){suffix}")
        return output

    def list_regular_files(self) -> List[str]:
        try:
            output = self._exec(
                ("find", REMOTE_ROOT, "-type", "f", "-print"), MAX_LISTING_BYTES
            )
        except RemoteError as exc:
            if "No such file or directory" in str(exc):
                return []
            raise
        try:
            text = output.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise RemoteError("원격 파일 목록을 해석할 수 없습니다") from exc
        return [line for line in text.splitlines() if line]

    def file_size(self, path: str) -> int:
        try:
            output = self._exec(("stat", "-c", "%s", path), MAX_STAT_BYTES)
            text = output.decode("utf-8", "replace").strip()
            return int(text)
        except (RemoteError, ValueError):
            # 구형 toybox에는 stat -c가 없을 수 있다. wc -c는 "바이트 경로"를 출력한다.
            output = self._exec(("wc", "-c", path), MAX_STAT_BYTES)
            fields = output.decode("utf-8", "replace").split()
            if not fields:
                raise RemoteError(f"{path} 크기를 읽지 못했습니다")
            try:
                return int(fields[0])
            except ValueError as exc:
                raise RemoteError(f"{path} 크기를 읽지 못했습니다") from exc

    def read_file(self, path: str, limit: int) -> bytes:
        output = self._exec(("cat", path), limit)
        if len(output) > limit:
            raise RemoteError(f"{path}이(가) {limit}바이트 상한을 넘었습니다")
        return output


def _parse_inventory(lines: Sequence[str]) -> Dict[str, RecordingInventory]:
    """find 출력에서 계약 경로만 골라낸다. 알 수 없는 파일은 무시한다."""
    captures: set[str] = set()
    audios: set[str] = set()
    runs: Dict[str, set[str]] = {}
    prefix = REMOTE_ROOT + "/"

    for line in lines:
        if not line.startswith(prefix):
            continue
        parts = line[len(prefix) :].split("/")
        if len(parts) not in (2, 3):
            continue
        try:
            recording = normalize_uuid(parts[0])
        except ValidationError:
            continue
        # 기기 저장소는 항상 소문자 UUID 폴더를 쓴다. 비정규 이름은 다른 경로와 섞이지 않게 무시한다.
        if recording != parts[0]:
            continue
        if len(parts) == 2:
            if parts[1] == CAPTURE_FILE:
                captures.add(recording)
            elif parts[1] == AUDIO_FILE:
                audios.add(recording)
            continue
        if parts[1] != RUNS_DIR or not parts[2].endswith(".json"):
            continue
        run_id = parts[2][: -len(".json")]
        try:
            normalized_run = normalize_uuid(run_id)
        except ValidationError:
            continue
        if normalized_run != run_id:
            continue
        runs.setdefault(recording, set()).add(run_id)

    recording_ids = sorted(captures | audios | set(runs))
    return {
        recording_id: RecordingInventory(
            recording_id=recording_id,
            has_capture=recording_id in captures,
            has_audio=recording_id in audios,
            runs=tuple(sorted(runs.get(recording_id, set()))),
        )
        for recording_id in recording_ids
    }


def _inventory(remote: AdbRemote) -> Dict[str, RecordingInventory]:
    return _parse_inventory(remote.list_regular_files())


def _parse_json(data: bytes, path: str, require_object: bool = False):
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValidationError(f"{path} JSON을 해석할 수 없습니다") from exc
    if require_object and not isinstance(value, dict):
        raise ValidationError(f"{path}이(가) JSON 객체가 아닙니다")
    return value


def _check_identity(meta: Dict[str, object], recording_id: str, path: str) -> None:
    for key in _ID_KEYS:
        value = meta.get(key)
        if value is None:
            continue
        if not isinstance(value, str):
            raise ValidationError(f"{path}: {key} 값이 문자열 UUID가 아닙니다")
        try:
            actual = normalize_uuid(value.strip())
        except ValidationError as exc:
            raise ValidationError(f"{path}: {key} 값이 UUID가 아닙니다") from exc
        if actual != recording_id:
            raise ValidationError(f"{path}: {key} 값이 폴더 UUID와 다릅니다")


def _check_result_identity(
    result: Dict[str, object], recording_id: str, run_id: str, path: str
) -> None:
    """runs/<run>.json의 id/recordingId가 폴더·파일 이름과 같은지 확인한다."""
    for key, expected in (("id", run_id), ("recordingId", recording_id)):
        value = result.get(key)
        if value is None:
            continue
        if not isinstance(value, str):
            raise ValidationError(f"{path}: {key} 값이 문자열 UUID가 아닙니다")
        try:
            actual = normalize_uuid(value.strip())
        except ValidationError as exc:
            raise ValidationError(f"{path}: {key} 값이 UUID가 아닙니다") from exc
        if actual != expected:
            raise ValidationError(f"{path}: {key} 값이 일치하지 않습니다")


def _is_complete(meta: Dict[str, object]) -> bool:
    complete = meta.get("complete")
    if isinstance(complete, bool):
        return complete
    for key in ("status", "state", "result"):
        value = meta.get(key)
        if isinstance(value, str) and value.strip().upper() in _INCOMPLETE_STATUSES:
            return False
    return True


def _format_utc(value: datetime) -> str:
    return (
        value.astimezone(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def _parse_time_value(value: object) -> Optional[str]:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        if not math.isfinite(value) or value <= 0:
            return None
        seconds = value / 1000.0 if value >= 1e11 else float(value)
        if not 0 < seconds < 1e11:
            return None
        try:
            return _format_utc(datetime.fromtimestamp(seconds, timezone.utc))
        except (OverflowError, OSError, ValueError):
            return None
    if isinstance(value, str):
        text = value.strip()
        if not text or len(text) > 40:
            return None
        if re.fullmatch(r"\d{10,13}", text):
            return _parse_time_value(int(text))
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return None
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return _format_utc(parsed)
    return None


def _capture_time(meta: Dict[str, object]) -> Optional[str]:
    for key in _TIME_KEYS:
        if key in meta:
            parsed = _parse_time_value(meta[key])
            if parsed is not None:
                return parsed
    return None


def validate_wav(data: bytes) -> None:
    """16 kHz mono PCM16 WAV만 통과시킨다. 손상/절단 파일은 ValidationError."""
    if len(data) < 44:
        raise ValidationError("audio.wav가 너무 짧습니다")
    if data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValidationError("audio.wav RIFF/WAVE 헤더가 아닙니다")
    riff_size = int.from_bytes(data[4:8], "little")
    if riff_size + 8 > len(data):
        raise ValidationError("audio.wav RIFF 크기가 파일보다 큽니다")

    position = 12
    fmt: Optional[Tuple[int, int, int, int, int, int]] = None
    data_size: Optional[int] = None
    while position + 8 <= len(data):
        chunk_id = data[position : position + 4]
        chunk_size = int.from_bytes(data[position + 4 : position + 8], "little")
        body = position + 8
        if body + chunk_size > len(data):
            raise ValidationError("audio.wav 청크가 파일 끝을 넘습니다")
        if chunk_id == b"fmt ":
            if chunk_size < 16:
                raise ValidationError("audio.wav fmt 청크가 짧습니다")
            fmt = struct.unpack("<HHIIHH", data[body : body + 16])
        elif chunk_id == b"data":
            if fmt is None:
                raise ValidationError("audio.wav data 청크가 fmt보다 먼저 있습니다")
            data_size = chunk_size
            break
        position = body + chunk_size + (chunk_size & 1)

    if fmt is None or data_size is None:
        raise ValidationError("audio.wav에 fmt/data 청크가 없습니다")

    audio_format, channels, sample_rate, byte_rate, block_align, bits = fmt
    if audio_format != 1:
        raise ValidationError("audio.wav가 PCM이 아닙니다")
    if channels != 1:
        raise ValidationError("audio.wav가 모노가 아닙니다")
    if sample_rate != 16_000:
        raise ValidationError("audio.wav 샘플레이트가 16 kHz가 아닙니다")
    if bits != 16 or block_align != 2 or byte_rate != 32_000:
        raise ValidationError("audio.wav가 16-bit PCM16 형식이 아닙니다")
    if data_size <= 0 or data_size % 2 != 0:
        raise ValidationError("audio.wav PCM 길이가 올바르지 않습니다")
    if data_size > MAX_PCM_BYTES:
        raise ValidationError("audio.wav PCM이 허용 상한을 넘습니다")


def _output_root(directory: str) -> Path:
    if not isinstance(directory, str) or not directory.strip():
        raise ValidationError("--output 디렉터리가 필요합니다")
    return Path(directory)


def _ensure_output_root(root: Path) -> None:
    if root.is_symlink():
        raise ConflictError(f"{root}: 심볼릭 링크는 출력 폴더로 쓸 수 없습니다")
    if root.exists():
        if not root.is_dir():
            raise ConflictError(f"{root}: 출력 경로가 디렉터리가 아닙니다")
        return
    try:
        root.mkdir(parents=True, mode=0o700)
    except FileExistsError:
        if root.is_symlink() or not root.is_dir():
            raise ConflictError(f"{root}: 출력 경로를 사용할 수 없습니다") from None


def _destination(root: Path, parts: Sequence[str]) -> Path:
    destination = root
    for part in parts:
        if not _SAFE_COMPONENT_RE.fullmatch(part) or part in (".", ".."):
            raise ValidationError("출력 경로 조각이 허용되지 않습니다")
        destination = destination / part
    return destination


def _ensure_parent(root: Path, parts: Sequence[str]) -> None:
    current = root
    for part in parts:
        current = _destination(current, (part,))
        if current.is_symlink():
            raise ConflictError(f"{current}: 심볼릭 링크 아래에는 쓰지 않습니다")
        if current.exists():
            if not current.is_dir():
                raise ConflictError(f"{current}: 디렉터리가 아닙니다")
            continue
        try:
            current.mkdir(mode=0o700)
        except FileExistsError:
            if current.is_symlink() or not current.is_dir():
                raise ConflictError(f"{current}: 디렉터리를 만들 수 없습니다") from None


def _existing_status(destination: Path, data: bytes) -> str:
    try:
        info = destination.lstat()
    except FileNotFoundError:
        return "new"
    except OSError as exc:
        raise ConflictError(f"{destination}: 기존 경로를 확인할 수 없습니다") from exc
    if stat_module.S_ISLNK(info.st_mode):
        raise ConflictError(f"{destination}: 심볼릭 링크는 덮어쓰지 않습니다")
    if not stat_module.S_ISREG(info.st_mode):
        raise ConflictError(f"{destination}: 일반 파일이 아닙니다")
    try:
        existing = destination.read_bytes()
    except OSError as exc:
        raise ConflictError(f"{destination}: 기존 파일을 읽을 수 없습니다") from exc
    if len(existing) == len(data) and hashlib.sha256(existing).digest() == hashlib.sha256(
        data
    ).digest():
        return "unchanged"
    raise ConflictError(f"{destination}: 기존 파일과 내용이 다릅니다(덮어쓰지 않음)")


def _write_new_file(destination: Path, data: bytes) -> str:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(destination, flags | nofollow, 0o600)
    except FileExistsError:
        return _existing_status(destination, data)
    except OSError as exc:
        raise ConflictError(f"{destination}: 파일을 만들 수 없습니다") from exc
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    except OSError as exc:
        raise ConflictError(f"{destination}: 파일을 쓰지 못했습니다") from exc
    return "new"


def _aggregate_digest(entries: Sequence[Dict[str, object]]) -> str:
    digest = hashlib.sha256()
    for entry in sorted(entries, key=lambda item: str(item["path"])):
        digest.update(str(entry["path"]).encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(entry["sha256"]).encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def list_recordings(remote: AdbRemote) -> List[Dict[str, object]]:
    """완료된 녹음만 ID/시각/오디오 바이트로 나열한다. 원문은 읽지 않는다."""
    results: List[Dict[str, object]] = []
    for recording_id, inventory in _inventory(remote).items():
        if not (inventory.has_capture and inventory.has_audio):
            continue
        capture_path = remote_path(recording_id, CAPTURE_FILE)
        meta = _parse_json(
            remote.read_file(capture_path, MAX_METADATA_BYTES), capture_path, True
        )
        if not _is_complete(meta):
            continue
        _check_identity(meta, recording_id, capture_path)
        size = remote.file_size(remote_path(recording_id, AUDIO_FILE))
        if size <= 0 or size > MAX_AUDIO_FILE_BYTES:
            continue
        results.append(
            {
                "id": recording_id,
                "capturedAt": _capture_time(meta),
                "bytes": size,
            }
        )
    results.sort(key=lambda item: (item["capturedAt"] or "", item["id"]), reverse=True)
    return results


def fetch_recording(remote: AdbRemote, recording_id: str, output: str) -> Dict[str, object]:
    """한 녹음을 읽어 `output/<UUID>/...`로 만든다. 기존 파일은 덮어쓰지 않는다."""
    recording = normalize_uuid(recording_id)
    root = _output_root(output)

    inventory = _inventory(remote).get(recording)
    if inventory is None:
        raise RemoteError(f"기기에서 녹음 {recording}을(를) 찾지 못했습니다")
    if not inventory.has_capture:
        raise ValidationError(f"녹음 {recording}에 {CAPTURE_FILE}이(가) 없습니다")
    if not inventory.has_audio:
        raise ValidationError(f"녹음 {recording}에 {AUDIO_FILE}이(가) 없습니다")

    planned: List[Tuple[Tuple[str, ...], int]] = [
        ((CAPTURE_FILE,), MAX_METADATA_BYTES),
        ((AUDIO_FILE,), MAX_AUDIO_FILE_BYTES),
    ]
    planned.extend(
        ((RUNS_DIR, run_id + ".json"), MAX_RESULT_BYTES)
        for run_id in inventory.runs
    )
    planned.sort(key=lambda item: "/".join(item[0]))

    payloads: Dict[Tuple[str, ...], bytes] = {}
    for suffix, limit in planned:
        payloads[suffix] = remote.read_file(
            remote_path(recording, *suffix), limit
        )

    capture = _parse_json(
        payloads[(CAPTURE_FILE,)], remote_path(recording, CAPTURE_FILE), True
    )
    if not _is_complete(capture):
        raise ValidationError(f"녹음 {recording}은(는) 완료되지 않았습니다")
    _check_identity(capture, recording, remote_path(recording, CAPTURE_FILE))
    validate_wav(payloads[(AUDIO_FILE,)])
    for run_id in inventory.runs:
        suffix = (RUNS_DIR, run_id + ".json")
        run = _parse_json(
            payloads[suffix], remote_path(recording, *suffix), True
        )
        _check_result_identity(
            run, recording, run_id, remote_path(recording, *suffix)
        )

    # 모든 원격 파일을 먼저 받아 검증한 뒤, 로컬 충돌을 전부 확인하고 나서 쓴다.
    destinations: Dict[Tuple[str, ...], Path] = {}
    statuses: Dict[Tuple[str, ...], str] = {}
    for suffix, _limit in planned:
        destination = _destination(root, (recording,) + suffix)
        statuses[suffix] = _existing_status(destination, payloads[suffix])
        destinations[suffix] = destination

    _ensure_output_root(root)
    entries: List[Dict[str, object]] = []
    for suffix, _limit in planned:
        destination = destinations[suffix]
        data = payloads[suffix]
        if statuses[suffix] == "new":
            _ensure_parent(root, (recording,) + suffix[:-1])
            statuses[suffix] = _write_new_file(destination, data)
        entries.append(
            {
                "path": "/".join((recording,) + suffix),
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "status": statuses[suffix],
            }
        )

    entries.sort(key=lambda item: str(item["path"]))
    return {
        "id": recording,
        "output": str(root),
        "fileCount": len(entries),
        "sha256": _aggregate_digest(entries),
        "files": entries,
    }


def replay_recording(
    adb: str,
    serial: str,
    recording_id: str,
    runner: Optional[Runner] = None,
) -> Dict[str, str]:
    """기기 계측 테스트로 저장된 녹음만 재디코딩한다. 차량/마이크에는 접근하지 않는다.

    stdout 원문(전사 결과 포함 가능)은 절대 출력하지 않고, adb 종료 코드와
    `OK (1 test)` 보고가 모두 확인된 경우에만 성공을 반환한다.
    """
    if not isinstance(adb, str) or not adb.strip():
        raise ValidationError("adb 실행 경로가 비어 있습니다")
    serial = normalize_serial(serial)
    recording = normalize_uuid(recording_id)
    command = [
        adb,
        "-s",
        serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        REPLAY_TEST_CLASS,
        "-e",
        "recordingId",
        recording,
        REPLAY_RUNNER,
    ]
    run = runner or _default_runner
    try:
        code, output, _error = run(command, MAX_INSTRUMENTATION_BYTES)
    except RemoteError as exc:
        raise RemoteError(
            f"기기 재시험이 실행되지 않았습니다 (id={recording})"
        ) from exc
    if len(output) > MAX_INSTRUMENTATION_BYTES:
        raise RemoteError(f"기기 재시험 출력이 상한을 넘었습니다 (id={recording})")
    if code != 0:
        raise RemoteError(
            f"기기 재시험이 실패했습니다 (id={recording}, exit {code})"
        )
    try:
        text = output.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise RemoteError(
            f"기기 재시험 출력을 해석할 수 없습니다 (id={recording})"
        ) from exc
    if not any(_OK_RESULT_RE.match(line.strip()) for line in text.splitlines()):
        raise RemoteError(
            f"기기 재시험이 성공 결과를 보고하지 않았습니다 (id={recording})"
        )
    return {"id": recording, "status": "OK"}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="speech_recordings.py",
        description="USB 디버깅으로 기기의 개발 시험 음성 녹음을 회수하고 재시험한다.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "예:\n"
            "  python3 tools/speech_recordings.py --serial R58M12345 list\n"
            "  python3 tools/speech_recordings.py --serial R58M12345 "
            "fetch --id <UUID> --output .local-recordings\n"
            "  python3 tools/speech_recordings.py --serial R58M12345 "
            "replay --id <UUID>\n"
        ),
    )
    parser.add_argument("--serial", required=True, help="adb -s에 전달할 기기 시리얼")
    parser.add_argument(
        "--adb",
        default=os.environ.get("ADB", "adb"),
        help="adb 실행 파일 경로(기본: 환경 변수 ADB 또는 adb)",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser(
        "list", help="완료된 녹음의 ID/시각/오디오 바이트만 JSON으로 출력"
    )

    fetch = subparsers.add_parser("fetch", help="한 녹음의 메타/오디오/결과를 회수")
    fetch.add_argument("--id", required=True, help="녹음 폴더 UUID")
    fetch.add_argument(
        "--output",
        required=True,
        help="회수 대상 디렉터리(프로젝트에서는 .local-recordings 권장)",
    )

    replay = subparsers.add_parser(
        "replay",
        help="기기 계측 테스트로 저장된 녹음만 재디코딩(차량/마이크 미사용)",
    )
    replay.add_argument(
        "--id", required=True, help="재시험할 녹음 폴더 UUID"
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "replay":
            print(
                json.dumps(
                    replay_recording(args.adb, args.serial, args.id),
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
            )
            return 0
        remote = AdbRemote(args.adb, args.serial)
        if args.command == "list":
            print(
                json.dumps(
                    list_recordings(remote),
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
            )
            return 0
        if args.command == "fetch":
            summary = fetch_recording(remote, args.id, args.output)
            print(
                json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
            )
            return 0
        parser.error("알 수 없는 서브커맨드입니다")
    except ValidationError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except RemoteError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 3
    except ConflictError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 4
    except OSError as exc:
        print(f"error: 로컬 파일 작업이 실패했습니다: {exc}", file=sys.stderr)
        return 5
    return 2


if __name__ == "__main__":
    sys.exit(main())
