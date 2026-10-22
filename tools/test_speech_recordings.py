#!/usr/bin/env python3
"""tools/speech_recordings.py의 경로 탈출/덮어쓰기/형식 검증 테스트.

adb나 기기에 접근하지 않는다. 실행은 `python3 -m unittest tools.test_speech_recordings` 또는
`python3 tools/test_speech_recordings.py`.
"""

from __future__ import annotations

import json
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import speech_recordings as sr  # noqa: E402

UUID = "3f2c1a4e-5b6d-4c7e-8f90-123456789abc"
UUID_B = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
UUID_C = "11223344-5566-4778-899a-bbccddeeff00"
RUN = "11112222-3333-4444-8888-99990000aaaa"
RUN_B = "aaaabbbb-cccc-4ddd-8eee-ffff00001111"


def make_wav(
    pcm: bytes = b"\x00\x00" * 1_600,
    *,
    sample_rate: int = 16_000,
    channels: int = 1,
    bits: int = 16,
    audio_format: int = 1,
    riff_size: int | None = None,
) -> bytes:
    block_align = channels * bits // 8
    byte_rate = sample_rate * block_align
    fmt = struct.pack(
        "<HHIIHH", audio_format, channels, sample_rate, byte_rate, block_align, bits
    )
    body = fmt + pcm
    if riff_size is None:
        riff_size = 4 + (8 + len(fmt)) + (8 + len(pcm))
    return (
        b"RIFF"
        + struct.pack("<I", riff_size)
        + b"WAVE"
        + b"fmt "
        + struct.pack("<I", len(fmt))
        + fmt
        + b"data"
        + struct.pack("<I", len(pcm))
        + pcm
    )


def capture_bytes(recording_id: str = UUID, **overrides) -> bytes:
    # SpeechRecordingStore.captureJson과 같은 8개 필드. 테스트 일부는 complete를 덧붙인다.
    meta = {
        "schemaVersion": 1,
        "id": recording_id,
        "createdAtMs": 1_720_000_000_000,
        "sampleRate": 16_000,
        "channels": 1,
        "bitsPerSample": 16,
        "pcmBytes": 3_200,
        "source": "DIRECT_MIC",
    }
    meta.update(overrides)
    return json.dumps(meta).encode("utf-8")


def result_bytes(
    recording_id: str = UUID, run_id: str = RUN, **overrides
) -> bytes:
    # SpeechRecordingStore.resultJson과 같은 필드. 원문은 전용 결과 JSON에만 있다.
    result = {
        "schemaVersion": 1,
        "id": run_id,
        "recordingId": recording_id,
        "createdAtMs": 1_720_000_100_000,
        "elapsedMs": 50,
        "engine": "Android on-device SpeechRecognizer ko-KR",
        "confidence": None,
        "source": "STORED_PCM",
        "text": "secret transcript",
        "failure": None,
    }
    result.update(overrides)
    return json.dumps(result).encode("utf-8")


class FakeRemote:
    """AdbRemote와 같은 읽기 메서드만 제공하고 명령은 기록하지 않는다."""

    def __init__(self, files, sizes=None):
        # 실제 AdbRemote와 같이 find/cat/stat 모두 REMOTE_ROOT 포함 경로를 쓴다.
        self.files = {
            key if key.startswith(sr.REMOTE_ROOT + "/") else f"{sr.REMOTE_ROOT}/{key}": value
            for key, value in files.items()
        }
        self.sizes = dict(sizes or {})
        self.read_paths = []
        self.size_paths = []

    def list_regular_files(self):
        return list(self.files)

    def file_size(self, path):
        self.size_paths.append(path)
        if path in self.sizes:
            return self.sizes[path]
        return len(self.files[path])

    def read_file(self, path, limit):
        self.read_paths.append(path)
        data = self.files[path]
        if len(data) > limit:
            raise sr.RemoteError(f"{path}: 상한 초과")
        return data


def recording_files(
    recording_id: str = UUID,
    *,
    run_id: str = RUN,
    audio: bytes | None = None,
    meta: bytes | None = None,
    result: bytes | None = None,
):
    return {
        f"{recording_id}/{sr.CAPTURE_FILE}": (
            capture_bytes(recording_id) if meta is None else meta
        ),
        f"{recording_id}/{sr.AUDIO_FILE}": (
            make_wav() if audio is None else audio
        ),
        f"{recording_id}/{sr.RUNS_DIR}/{run_id}.json": (
            result_bytes(recording_id, run_id) if result is None else result
        ),
    }


class UuidAndPathTests(unittest.TestCase):
    def test_canonical_uuid_normalizes_case(self):
        self.assertEqual(sr.normalize_uuid(UUID.upper()), UUID)

    def test_rejects_non_uuid_and_path_tricks(self):
        for value in (
            "",
            "not-a-uuid",
            "../" + UUID,
            UUID + "/../x",
            "../../etc/passwd",
            UUID + "\x00",
            UUID.replace("-", ""),
            "{" + UUID + "}",
            UUID + "\n",
        ):
            with self.subTest(value=value):
                with self.assertRaises(sr.ValidationError):
                    sr.normalize_uuid(value)
        with self.assertRaises(sr.ValidationError):
            sr.normalize_uuid(123)  # type: ignore[arg-type]

    def test_remote_path_is_fixed_under_recordings_root(self):
        self.assertEqual(
            sr.remote_path(UUID, sr.AUDIO_FILE),
            sr.REMOTE_ROOT + "/" + UUID + "/" + sr.AUDIO_FILE,
        )
        self.assertEqual(
            sr.remote_path(UUID, sr.RUNS_DIR, RUN + ".json"),
            sr.REMOTE_ROOT + "/" + UUID + "/" + sr.RUNS_DIR + "/" + RUN + ".json",
        )

    def test_remote_path_rejects_injection_and_escape(self):
        for part in ("..", ".", "a/b", "../x", "x\x00y", "x y", ""):
            with self.subTest(part=part):
                with self.assertRaises(sr.ValidationError):
                    sr.remote_path(UUID, part)
        with self.assertRaises(sr.ValidationError):
            sr.remote_path("../../etc", sr.CAPTURE_FILE)
        with self.assertRaises(sr.ValidationError):
            sr.remote_path(UUID, "runs", "../../x.json")

    def test_serial_validation(self):
        for serial in ("R58M12345", "emulator-5554", "192.168.0.10:5555"):
            self.assertEqual(sr.normalize_serial(serial), serial)
        for serial in ("", "-s", "a b", "a;b", "a\nb"):
            with self.subTest(serial=serial):
                with self.assertRaises(sr.ValidationError):
                    sr.normalize_serial(serial)


class InventoryTests(unittest.TestCase):
    def test_only_contract_paths_are_inventoried(self):
        prefix = sr.REMOTE_ROOT + "/"
        lines = [
            prefix + UUID + "/" + sr.CAPTURE_FILE,
            prefix + UUID + "/" + sr.AUDIO_FILE,
            prefix + UUID + "/" + sr.RUNS_DIR + "/" + RUN + ".json",
            prefix + UUID + "/notes.txt",
            prefix + "../evil/" + sr.CAPTURE_FILE,
            prefix + UUID + "/../../outside",
            prefix + UUID.upper() + "/" + sr.AUDIO_FILE,
            prefix + UUID + "/" + sr.RUNS_DIR + "/not-a-uuid.json",
            prefix + UUID + "/" + sr.RUNS_DIR + "/" + RUN_B + ".txt",
            "other-root/" + UUID + "/" + sr.CAPTURE_FILE,
        ]
        inventory = sr._parse_inventory(lines)
        self.assertEqual(list(inventory), [UUID])
        entry = inventory[UUID]
        self.assertTrue(entry.has_capture)
        self.assertTrue(entry.has_audio)
        self.assertEqual(entry.runs, (RUN,))

    def test_empty_listing(self):
        self.assertEqual(sr._parse_inventory([]), {})


class TimeTests(unittest.TestCase):
    def test_epoch_seconds_and_milliseconds(self):
        self.assertEqual(sr._parse_time_value(1_720_000_000), "2024-07-03T09:46:40.000Z")
        self.assertEqual(
            sr._parse_time_value(1_720_000_000_000), "2024-07-03T09:46:40.000Z"
        )
        self.assertEqual(
            sr._parse_time_value("1720000000000"), "2024-07-03T09:46:40.000Z"
        )

    def test_iso_strings_are_normalized_to_utc(self):
        self.assertEqual(
            sr._parse_time_value("2024-07-03T18:46:40+09:00"),
            "2024-07-03T09:46:40.000Z",
        )
        self.assertEqual(
            sr._parse_time_value("2024-07-03T09:46:40Z"),
            "2024-07-03T09:46:40.000Z",
        )

    def test_invalid_times_are_ignored(self):
        for value in ("", "not-a-time", True, None, -1, 0, float("nan"), "9" * 60):
            with self.subTest(value=value):
                self.assertIsNone(sr._parse_time_value(value))

    def test_capture_time_uses_supported_keys_in_order(self):
        self.assertEqual(
            sr._capture_time({"capturedAtEpochMs": 1_720_000_000_000}),
            "2024-07-03T09:46:40.000Z",
        )
        self.assertEqual(
            sr._capture_time({"createdAtMs": 1_720_000_000_000}),
            "2024-07-03T09:46:40.000Z",
        )
        self.assertIsNone(sr._capture_time({"text": "secret"}))


class CompletionTests(unittest.TestCase):
    def test_completion_rules(self):
        self.assertTrue(sr._is_complete({}))
        self.assertTrue(sr._is_complete({"complete": True}))
        self.assertFalse(sr._is_complete({"complete": False}))
        self.assertFalse(sr._is_complete({"status": "FAILED"}))
        self.assertFalse(sr._is_complete({"state": "capturing"}))
        self.assertTrue(sr._is_complete({"status": "COMPLETE"}))


class WavTests(unittest.TestCase):
    def test_accepts_contract_wav(self):
        sr.validate_wav(make_wav())

    def test_rejects_malformed_wav(self):
        valid = make_wav()
        cases = {
            "short": valid[:20],
            "not-riff": b"XXXX" + valid[4:],
            "riff-too-large": make_wav(riff_size=len(valid) + 100),
            "truncated-pcm": valid[:-2],
            "wrong-rate": make_wav(sample_rate=8_000),
            "stereo": make_wav(channels=2),
            "float": make_wav(audio_format=3),
            "oversized": make_wav(b"\x00\x00" * (sr.MAX_PCM_BYTES // 2 + 1)),
            "empty-pcm": make_wav(b""),
        }
        for name, data in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(sr.ValidationError):
                    sr.validate_wav(data)


class LocalWriteTests(unittest.TestCase):
    def test_new_file_is_exclusive_and_private(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "audio.wav"
            self.assertEqual(sr._write_new_file(path, b"abc"), "new")
            self.assertEqual(path.read_bytes(), b"abc")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_identical_file_is_left_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "capture.json"
            path.write_bytes(b"same")
            fixed = 1_600_000_000_000_000_000
            os.utime(path, ns=(fixed, fixed))
            self.assertEqual(sr._write_new_file(path, b"same"), "unchanged")
            self.assertEqual(path.read_bytes(), b"same")
            self.assertEqual(path.stat().st_mtime_ns, fixed)

    def test_different_file_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "capture.json"
            path.write_bytes(b"original")
            with self.assertRaises(sr.ConflictError):
                sr._write_new_file(path, b"changed")
            self.assertEqual(path.read_bytes(), b"original")

    def test_symlink_is_rejected_without_touching_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "target.json"
            target.write_bytes(b"target")
            link = Path(tmp) / "capture.json"
            link.symlink_to(target)
            with self.assertRaises(sr.ConflictError):
                sr._write_new_file(link, b"target")
            self.assertEqual(target.read_bytes(), b"target")
            self.assertTrue(link.is_symlink())

    def test_directory_at_destination_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "capture.json"
            path.mkdir()
            with self.assertRaises(sr.ConflictError):
                sr._write_new_file(path, b"data")

    def test_symlinked_parent_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as outside:
            root = Path(tmp)
            (root / UUID).symlink_to(Path(outside))
            with self.assertRaises(sr.ConflictError):
                sr._ensure_parent(root, (UUID,))
            self.assertEqual(list(Path(outside).iterdir()), [])

    def test_output_root_cannot_be_symlink_or_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "real"
            target.mkdir()
            link = Path(tmp) / "link"
            link.symlink_to(target)
            with self.assertRaises(sr.ConflictError):
                sr._ensure_output_root(link)
            file_path = Path(tmp) / "file"
            file_path.write_bytes(b"x")
            with self.assertRaises(sr.ConflictError):
                sr._ensure_output_root(file_path)

    def test_aggregate_digest_is_order_independent(self):
        first = {"path": "a", "sha256": "1" * 64}
        second = {"path": "b", "sha256": "2" * 64}
        self.assertEqual(
            sr._aggregate_digest([first, second]),
            sr._aggregate_digest([second, first]),
        )
        self.assertNotEqual(
            sr._aggregate_digest([first, second]),
            sr._aggregate_digest([first, {"path": "b", "sha256": "3" * 64}]),
        )


class RemoteCommandTests(unittest.TestCase):
    def test_commands_are_read_only_exec_out_invocations(self):
        commands = []

        def runner(command, limit):
            commands.append(list(command))
            return 0, b"x", b""

        remote = sr.AdbRemote("adb", "R58M12345", runner=runner)
        path = sr.remote_path(UUID, sr.CAPTURE_FILE)
        self.assertEqual(remote.read_file(path, 10), b"x")
        self.assertEqual(
            commands,
            [
                [
                    "adb",
                    "-s",
                    "R58M12345",
                    "exec-out",
                    "run-as",
                    sr.APP_PACKAGE,
                    "cat",
                    path,
                ]
            ],
        )

    def test_missing_recordings_root_is_empty_listing(self):
        def runner(command, limit):
            return 1, b"", b"find: no_backup/speech-recordings: No such file or directory"

        remote = sr.AdbRemote("adb", "R58M12345", runner=runner)
        self.assertEqual(remote.list_regular_files(), [])

    def test_file_size_falls_back_to_wc(self):
        calls = []

        def runner(command, limit):
            calls.append(command[6])
            if command[6] == "stat":
                return 1, b"", b"stat: invalid option"
            return 0, b"123 no_backup/x/audio.wav\n", b""

        remote = sr.AdbRemote("adb", "R58M12345", runner=runner)
        self.assertEqual(remote.file_size("no_backup/x/audio.wav"), 123)
        self.assertEqual(calls, ["stat", "wc"])

    def test_read_limit_is_enforced_even_for_injected_runner(self):
        def runner(command, limit):
            return 0, b"123456", b""

        remote = sr.AdbRemote("adb", "R58M12345", runner=runner)
        with self.assertRaises(sr.RemoteError):
            remote.read_file("no_backup/x/audio.wav", 5)


class ListTests(unittest.TestCase):
    def test_lists_only_completed_recordings_and_no_transcript(self):
        complete = recording_files()
        incomplete_meta = recording_files(
            UUID_B,
            run_id=RUN_B,
            meta=capture_bytes(UUID_B, complete=False),
        )
        audio_only = {f"{UUID_C}/{sr.AUDIO_FILE}": make_wav()}
        remote = FakeRemote({**complete, **incomplete_meta, **audio_only})

        result = sr.list_recordings(remote)

        self.assertEqual(
            result,
            [
                {
                    "id": UUID,
                    "capturedAt": "2024-07-03T09:46:40.000Z",
                    "bytes": len(make_wav()),
                }
            ],
        )
        serialized = json.dumps(result, ensure_ascii=False)
        self.assertNotIn("secret transcript", serialized)
        self.assertEqual(set(result[0]), {"id", "capturedAt", "bytes"})

    def test_identity_mismatch_fails_listing(self):
        files = recording_files(meta=capture_bytes(UUID_B))
        remote = FakeRemote(files)
        with self.assertRaises(sr.ValidationError):
            sr.list_recordings(remote)

    def test_listing_does_not_read_run_results(self):
        remote = FakeRemote(recording_files())
        result = sr.list_recordings(remote)
        self.assertEqual(len(result), 1)
        self.assertFalse(
            any("/runs/" in path for path in remote.read_paths), remote.read_paths
        )


class FetchTests(unittest.TestCase):
    def test_fetch_writes_flat_per_recording_tree_and_summary(self):
        remote = FakeRemote(recording_files())
        with tempfile.TemporaryDirectory() as tmp:
            summary = sr.fetch_recording(remote, UUID, tmp)

            self.assertEqual(summary["id"], UUID)
            self.assertEqual(summary["fileCount"], 3)
            self.assertEqual(len(summary["sha256"]), 64)
            self.assertTrue(all(item["status"] == "new" for item in summary["files"]))
            self.assertEqual(
                [item["path"] for item in summary["files"]],
                sorted(item["path"] for item in summary["files"]),
            )

            root = Path(tmp) / UUID
            self.assertEqual(
                (root / sr.CAPTURE_FILE).read_bytes(), capture_bytes()
            )
            self.assertEqual((root / sr.AUDIO_FILE).read_bytes(), make_wav())
            self.assertEqual(
                (root / sr.RUNS_DIR / (RUN + ".json")).read_bytes(),
                result_bytes(),
            )

    def test_refetch_leaves_identical_files_untouched(self):
        remote = FakeRemote(recording_files())
        with tempfile.TemporaryDirectory() as tmp:
            sr.fetch_recording(remote, UUID, tmp)
            paths = [
                Path(tmp) / UUID / sr.CAPTURE_FILE,
                Path(tmp) / UUID / sr.AUDIO_FILE,
                Path(tmp) / UUID / sr.RUNS_DIR / (RUN + ".json"),
            ]
            stamps = {path: path.stat().st_mtime_ns for path in paths}

            summary = sr.fetch_recording(remote, UUID, tmp)

            self.assertTrue(
                all(item["status"] == "unchanged" for item in summary["files"])
            )
            for path in paths:
                self.assertEqual(path.stat().st_mtime_ns, stamps[path])

    def test_conflict_aborts_before_any_new_file_is_written(self):
        remote = FakeRemote(recording_files())
        with tempfile.TemporaryDirectory() as tmp:
            existing = Path(tmp) / UUID / sr.CAPTURE_FILE
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b"different")

            with self.assertRaises(sr.ConflictError):
                sr.fetch_recording(remote, UUID, tmp)

            self.assertEqual(existing.read_bytes(), b"different")
            self.assertFalse((Path(tmp) / UUID / sr.AUDIO_FILE).exists())
            self.assertFalse((Path(tmp) / UUID / sr.RUNS_DIR).exists())

    def test_identical_existing_file_allows_fetch_to_continue(self):
        remote = FakeRemote(recording_files())
        with tempfile.TemporaryDirectory() as tmp:
            existing = Path(tmp) / UUID / sr.CAPTURE_FILE
            existing.parent.mkdir(parents=True)
            existing.write_bytes(capture_bytes())

            summary = sr.fetch_recording(remote, UUID, tmp)

            statuses = {item["path"]: item["status"] for item in summary["files"]}
            self.assertEqual(statuses[UUID + "/" + sr.CAPTURE_FILE], "unchanged")
            self.assertEqual(statuses[UUID + "/" + sr.AUDIO_FILE], "new")

    def test_invalid_id_never_touches_remote_or_output(self):
        remote = FakeRemote(recording_files())
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            for bad_id in ("../../etc", UUID + "/../x", "", "not-a-uuid"):
                with self.subTest(bad_id=bad_id):
                    with self.assertRaises(sr.ValidationError):
                        sr.fetch_recording(remote, bad_id, str(output))
            self.assertFalse(output.exists())
            self.assertEqual(remote.read_paths, [])

    def test_unknown_id_fails_cleanly(self):
        remote = FakeRemote(recording_files())
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(sr.RemoteError):
                sr.fetch_recording(remote, UUID_B, tmp)

    def test_invalid_audio_fails_without_writing(self):
        files = recording_files(audio=b"not a wav")
        remote = FakeRemote(files)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            with self.assertRaises(sr.ValidationError):
                sr.fetch_recording(remote, UUID, str(output))
            self.assertFalse(output.exists())

    def test_incomplete_capture_fails_without_writing(self):
        files = recording_files(meta=capture_bytes(complete=False))
        remote = FakeRemote(files)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            with self.assertRaises(sr.ValidationError):
                sr.fetch_recording(remote, UUID, str(output))
            self.assertFalse(output.exists())

    def test_invalid_run_json_fails_without_writing(self):
        files = recording_files(result=b"{not-json")
        remote = FakeRemote(files)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            with self.assertRaises(sr.ValidationError):
                sr.fetch_recording(remote, UUID, str(output))
            self.assertFalse(output.exists())

    def test_run_json_must_be_an_object(self):
        files = recording_files(result=b'["secret transcript"]')
        remote = FakeRemote(files)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out"
            with self.assertRaises(sr.ValidationError):
                sr.fetch_recording(remote, UUID, str(output))
            self.assertFalse(output.exists())

    def test_run_identity_mismatch_fails_without_writing(self):
        for result in (
            result_bytes(recording_id=UUID_B),
            result_bytes(run_id=RUN_B),
        ):
            remote = FakeRemote(recording_files(result=result))
            with tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp) / "out"
                with self.assertRaises(sr.ValidationError):
                    sr.fetch_recording(remote, UUID, str(output))
                self.assertFalse(output.exists())

    def test_capture_identity_mismatch_fails(self):
        files = recording_files(meta=capture_bytes(UUID_B))
        remote = FakeRemote(files)
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(sr.ValidationError):
                sr.fetch_recording(remote, UUID, tmp)


class ReplayTests(unittest.TestCase):
    def test_replay_uses_fixed_class_and_runner_and_requires_ok(self):
        commands = []

        def runner(command, limit):
            commands.append(list(command))
            return (
                0,
                b"INSTRUMENTATION_STATUS: numtests=1\n"
                b"INSTRUMENTATION_STATUS_CODE: 1\n"
                b"Time: 0.5\n"
                b"OK (1 test)\n",
                b"",
            )

        result = sr.replay_recording("adb", "R58M12345", UUID, runner=runner)

        self.assertEqual(result, {"id": UUID, "status": "OK"})
        self.assertEqual(
            commands,
            [
                [
                    "adb",
                    "-s",
                    "R58M12345",
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-r",
                    "-e",
                    "class",
                    sr.REPLAY_TEST_CLASS,
                    "-e",
                    "recordingId",
                    UUID,
                    sr.REPLAY_RUNNER,
                ]
            ],
        )

    def test_nonzero_exit_fails_without_echoing_instrumentation_output(self):
        def runner(command, limit):
            return 1, b"FAILURES!!!\nsecret transcript", b"secret transcript"

        with self.assertRaises(sr.RemoteError) as caught:
            sr.replay_recording("adb", "R58M12345", UUID, runner=runner)
        self.assertNotIn("secret", str(caught.exception))
        self.assertIn(UUID, str(caught.exception))

    def test_missing_ok_report_fails_without_echoing_output(self):
        def runner(command, limit):
            return 0, b"FAILURES!!!\nsecret transcript\n", b""

        with self.assertRaises(sr.RemoteError) as caught:
            sr.replay_recording("adb", "R58M12345", UUID, runner=runner)
        self.assertNotIn("secret", str(caught.exception))

    def test_ok_zero_tests_is_not_success(self):
        def runner(command, limit):
            return 0, b"OK (0 tests)\n", b""

        with self.assertRaises(sr.RemoteError) as caught:
            sr.replay_recording("adb", "R58M12345", UUID, runner=runner)
        self.assertNotIn("OK (0 tests)", str(caught.exception))

    def test_invalid_uuid_never_runs_instrumentation(self):
        commands = []

        def runner(command, limit):
            commands.append(list(command))
            return 0, b"OK (1 test)\n", b""

        for bad_id in ("../../etc", UUID + "/../x", "", "not-a-uuid"):
            with self.subTest(bad_id=bad_id):
                with self.assertRaises(sr.ValidationError):
                    sr.replay_recording("adb", "R58M12345", bad_id, runner=runner)
        self.assertEqual(commands, [])

    def test_invalid_utf8_output_is_rejected(self):
        def runner(command, limit):
            return 0, b"\xffOK (1 test)\n", b""

        with self.assertRaises(sr.RemoteError):
            sr.replay_recording("adb", "R58M12345", UUID, runner=runner)


if __name__ == "__main__":
    unittest.main()
