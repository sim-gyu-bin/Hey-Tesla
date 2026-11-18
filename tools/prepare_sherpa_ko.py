#!/usr/bin/env python3
"""Hey Tesla v0.52 그림자 인식기용 sherpa-onnx 한국어 zipformer 파일 준비 도구.

용도:
    gitignore된 `.local-models/`를 새 클론에서도 재현합니다.
    1) sherpa-onnx v1.13.8 AAR 검증(필요 시 다운로드)
    2) 원본 모델 sherpa-onnx-zipformer-korean-2024-06-24 검증(필요 시 tar.bz2 다운로드·선택 추출)
    3) APK asset `.local-models/sherpa-assets/zipformer-ko/` 준비(onnx·tokens 복사, bpe.model → bpe.vocab 생성)
    표준 라이브러리만 사용합니다. 기존 파일은 절대 삭제·덮어쓰지 않습니다.
    `--download`가 없으면 네트워크를 쓰지 않고, 빠진 파일을 안내한 뒤 종료 코드 1로 끝냅니다.
    이 인식기는 진단 화면 전용 그림자 인식이며 Moonshine 재도입이 아닙니다.

실행 예:
    python3 tools/prepare_sherpa_ko.py --download
    python3 tools/prepare_sherpa_ko.py            # 오프라인 검증만

라이선스 요약:
    - sherpa-onnx: Apache-2.0
    - ONNX Runtime(AAR 내부 포함): MIT
    - 모델 icefall KsponSpeech zipformer(2024-06-24): Apache-2.0
    - 학습 데이터 KsponSpeech(AI Hub): 별도 데이터 이용 조건이 있을 수 있음
"""

import argparse
import hashlib
import os
import struct
import sys
import tarfile
import urllib.request
from pathlib import Path

AAR_NAME = "sherpa-onnx-1.13.8.aar"
AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

MODEL_DIR_NAME = "sherpa-onnx-zipformer-korean-2024-06-24"
MODEL_TAR_NAME = MODEL_DIR_NAME + ".tar.bz2"
MODEL_TAR_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + MODEL_TAR_NAME
)

# 파일명: (크기 바이트, sha256)
MODEL_FILES = {
    "encoder-epoch-99-avg-1.int8.onnx": (
        70784728, "8b196d723421a0513c98ec25da2c43420c029e817f5e4a90b29ff80291c0af2b"),
    "decoder-epoch-99-avg-1.int8.onnx": (
        2844692, "2cc8c04ea080a657c18ebc59702e6b049cef08163eba5d68ac5bf707925cb0fb"),
    "joiner-epoch-99-avg-1.int8.onnx": (
        2581421, "eb654db1ea2cc9d63474855f65958b6059084692a9f2eb4f3812aceb1e416a20"),
    "tokens.txt": (
        60246, "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668"),
    "bpe.model": (
        314212, "1491bc92c47dfda4225f5b8930fba3cfa34c3b1ccd25e7d96c630a262f3e918d"),
}
ASSET_COPY_FILES = (
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.int8.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt",
)
ASSET_SUBDIR = ("sherpa-assets", "zipformer-ko")
BPE_VOCAB_NAME = "bpe.vocab"
BPE_VOCAB_SIZE = 132232
BPE_VOCAB_SHA256 = "4dbdff57db7b1e00e8feba8637dec4d2606221f614ce44336c04fd20b07f4c0f"

CHUNK = 1 << 20
TIMEOUT_SECONDS = 60


class PrepareError(Exception):
    """사용자에게 보여 줄 준비 실패."""


def log(message):
    print(message, flush=True)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(path, label, expected_sha, expected_size=None):
    size = path.stat().st_size
    if expected_size is not None and size != expected_size:
        raise PrepareError(
            f"오류 {label} 크기 불일치: {path} 실제 {size} B, 기대 {expected_size} B")
    actual = sha256_of(path)
    if actual != expected_sha:
        raise PrepareError(
            f"오류 {label} sha256 불일치: {path} 실제 {actual}, 기대 {expected_sha}")
    log(f"확인 {label} sha256 일치")


# ---------- 다운로드 ----------

def stream_to_part(url, part):
    try:
        out = open(part, "xb")
    except FileExistsError:
        raise PrepareError(
            f"오류 이전 다운로드 잔여 파일이 있습니다: {part} — 내용을 확인한 뒤 직접 정리하고 다시 실행하세요")
    log(f"다운로드 시작 {url}")
    with out, urllib.request.urlopen(url, timeout=TIMEOUT_SECONDS) as resp:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        last_pct = -10
        for block in iter(lambda: resp.read(CHUNK), b""):
            out.write(block)
            done += len(block)
            if total:
                pct = done * 100 // total
                if pct >= last_pct + 10:
                    last_pct = pct
                    log(f"다운로드 진행 {pct}% ({done}/{total} B)")
    log(f"다운로드 완료 {part} {done} B")


def promote_part(part, final):
    if final.exists():
        raise PrepareError(f"오류 최종 경로가 이미 생겼습니다: {final} — {part}는 그대로 둡니다")
    os.rename(part, final)
    log(f"저장 {final}")


def download(url, final, label, expected_sha=None):
    part = final.with_name(final.name + ".part")
    stream_to_part(url, part)
    if expected_sha is not None:
        actual = sha256_of(part)
        if actual != expected_sha:
            raise PrepareError(
                f"오류 {label} 다운로드 sha256 불일치: {part} 실제 {actual}, 기대 {expected_sha} (파일은 보존)")
        log(f"확인 {label} 다운로드 sha256 일치")
    promote_part(part, final)


# ---------- 단계 1: AAR ----------

def step_aar(models, allow_download):
    aar = models / AAR_NAME
    if not aar.exists():
        if not allow_download:
            raise PrepareError(f"없음 {aar} — --download로 받거나 직접 받으세요: {AAR_URL}")
        download(AAR_URL, aar, "aar", AAR_SHA256)
    verify(aar, "aar", AAR_SHA256)


# ---------- 단계 2: 원본 모델 ----------

def missing_model_files(model_dir):
    return [name for name in MODEL_FILES if not (model_dir / name).exists()]


def extract_members(tar_path, model_dir, names):
    wanted = {f"{MODEL_DIR_NAME}/{name}": name for name in names}
    model_dir.mkdir(parents=True, exist_ok=True)
    found = set()
    with tarfile.open(tar_path, "r|bz2") as tf:
        for member in tf:
            name = wanted.get(member.name)
            if name is None:
                continue
            if not member.isreg():
                raise PrepareError(f"오류 tar 멤버가 일반 파일이 아닙니다(링크 등 거부): {member.name}")
            target = (model_dir / name).resolve()
            if target.parent != model_dir.resolve():
                raise PrepareError(f"오류 tar 멤버 경로 탈출 거부: {member.name}")
            source = tf.extractfile(member)
            if source is None:
                raise PrepareError(f"오류 tar 멤버를 읽을 수 없습니다: {member.name}")
            with source, open(target, "xb") as out:
                for block in iter(lambda: source.read(CHUNK), b""):
                    out.write(block)
            found.add(name)
            log(f"추출 {name}")
    lost = sorted(set(names) - found)
    if lost:
        raise PrepareError(f"오류 tar에 필요한 멤버가 없습니다: {', '.join(lost)}")


def step_model(models, allow_download):
    model_dir = models / MODEL_DIR_NAME
    missing = missing_model_files(model_dir)
    if missing:
        tar_path = models / MODEL_TAR_NAME
        if not allow_download and not tar_path.exists():
            raise PrepareError(
                f"없음 {model_dir} 안의 {', '.join(missing)} — --download로 받거나 직접 받으세요: {MODEL_TAR_URL}")
        if not tar_path.exists():
            download(MODEL_TAR_URL, tar_path, "model tar")
        else:
            log(f"사용 기존 tar {tar_path}")
        extract_members(tar_path, model_dir, missing)
    for name, (size, sha) in MODEL_FILES.items():
        verify(model_dir / name, f"model {name}", sha, size)
    return model_dir


# ---------- 단계 3: APK asset ----------

def copy_new(source, target):
    with open(source, "rb") as src, open(target, "xb") as out:
        for block in iter(lambda: src.read(CHUNK), b""):
            out.write(block)
    log(f"복사 {target.name}")


def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise PrepareError("오류 bpe.model varint가 잘렸습니다")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise PrepareError("오류 bpe.model varint가 너무 깁니다")


def iter_fields(buf):
    """(field_number, wire_type, value) 생성. wire 2는 bytes, 5는 4바이트, 1은 8바이트, 0은 int."""
    pos = 0
    end = len(buf)
    while pos < end:
        key, pos = read_varint(buf, pos)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, pos = read_varint(buf, pos)
        elif wire == 1:
            value, pos = buf[pos:pos + 8], pos + 8
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            value, pos = buf[pos:pos + length], pos + length
        elif wire == 5:
            value, pos = buf[pos:pos + 4], pos + 4
        else:
            raise PrepareError(f"오류 bpe.model 지원하지 않는 wire type {wire}")
        if pos > end:
            raise PrepareError("오류 bpe.model 필드가 잘렸습니다")
        yield field, wire, value


def parse_piece(buf):
    piece = None
    score = 0.0
    for field, wire, value in iter_fields(buf):
        if field == 1 and wire == 2:
            piece = bytes(value).decode("utf-8")
        elif field == 2 and wire == 5:
            score = struct.unpack("<f", value)[0]
    if piece is None:
        raise PrepareError("오류 bpe.model piece 문자열이 없습니다")
    return piece, score


def build_bpe_vocab(model_bytes):
    lines = []
    for field, wire, value in iter_fields(memoryview(model_bytes)):
        if field == 1 and wire == 2:
            piece, score = parse_piece(value)
            lines.append(f"{piece}\t{score}\n")
    if not lines:
        raise PrepareError("오류 bpe.model에서 piece를 찾지 못했습니다")
    return "".join(lines).encode("utf-8")


def ensure_bpe_vocab(model_dir, asset_dir):
    target = asset_dir / BPE_VOCAB_NAME
    if not target.exists():
        data = build_bpe_vocab((model_dir / "bpe.model").read_bytes())
        with open(target, "xb") as out:
            out.write(data)
        log(f"생성 {BPE_VOCAB_NAME}")
    verify(target, f"asset {BPE_VOCAB_NAME}", BPE_VOCAB_SHA256, BPE_VOCAB_SIZE)


def step_assets(models, model_dir):
    asset_dir = models.joinpath(*ASSET_SUBDIR)
    asset_dir.mkdir(parents=True, exist_ok=True)
    total = 0
    for name in ASSET_COPY_FILES:
        size, sha = MODEL_FILES[name]
        target = asset_dir / name
        if not target.exists():
            copy_new(model_dir / name, target)
        verify(target, f"asset {name}", sha, size)
        total += size
    ensure_bpe_vocab(model_dir, asset_dir)
    total += BPE_VOCAB_SIZE
    log(f"완료 asset 합계 {total} B ({total / 1e6:.1f} MB) {asset_dir}")


# ---------- CLI ----------

def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="sherpa-onnx 한국어 zipformer AAR·모델·APK asset 준비(표준 라이브러리 전용)")
    parser.add_argument(
        "--models-dir", type=Path,
        default=Path(__file__).resolve().parents[1] / ".local-models",
        help="모델 디렉터리(기본: 저장소 루트의 .local-models)")
    parser.add_argument(
        "--download", action="store_true",
        help="빠진 파일을 네트워크로 받습니다(없으면 네트워크 미사용)")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    models = args.models_dir.resolve()
    try:
        models.mkdir(parents=True, exist_ok=True)
        step_aar(models, args.download)
        model_dir = step_model(models, args.download)
        step_assets(models, model_dir)
    except PrepareError as error:
        log(str(error))
        return 1
    except OSError as error:
        log(f"오류 파일·네트워크 처리 실패: {error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
