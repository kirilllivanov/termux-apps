#!/usr/bin/env python3
from __future__ import annotations

from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
import hashlib
import re
import struct
import zlib

OLD = b"com.termux"
NEW = b"com.termxx"
BOOTSTRAP = Path("termux-app/src/main/cpp/bootstrap-aarch64.zip")

if len(OLD) != len(NEW):
    raise SystemExit("Package ids must have equal byte length")

def patch_dex(data: bytes) -> tuple[bytes, int]:
    if not data.startswith(b"dex\n"):
        return data, 0
    count = data.count(OLD)
    if not count:
        return data, 0
    patched = bytearray(data.replace(OLD, NEW))
    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    patched[8:12] = struct.pack("<I", zlib.adler32(patched[12:]) & 0xFFFFFFFF)
    return bytes(patched), count

def restore_java_class_names(data: bytes) -> bytes:
    # applicationId is com.termxx, but Java namespace remains com.termux.
    data = data.replace(b"com.termxx.termuxam.Am", b"com.termux.termuxam.Am")
    data = data.replace(b"com.termxx/com.termxx.", b"com.termxx/com.termux.")
    data = re.sub(rb"com\.termxx\.app\.(?=[A-Z])", b"com.termux.app.", data)
    data = re.sub(rb"com\.termxx\.app\.api\.(?=[A-Z])", b"com.termux.app.api.", data)
    data = re.sub(rb"com\.termxx\.widget\.(?=Termux)", b"com.termux.widget.", data)
    return data

def patch_termux_am_apk(data: bytes) -> tuple[bytes, int]:
    src = BytesIO(data)
    dst = BytesIO()
    replacements = 0
    with ZipFile(src, "r") as zin, ZipFile(dst, "w") as zout:
        for info in zin.infolist():
            inner = zin.read(info.filename)
            if info.filename.endswith(".dex"):
                inner, count = patch_dex(inner)
                replacements += count
            zout.writestr(info, inner)
    if replacements == 0:
        raise SystemExit("termux-am DEX did not contain the expected package id")
    return dst.getvalue(), replacements

if not BOOTSTRAP.is_file():
    raise SystemExit(f"Missing bootstrap: {BOOTSTRAP}")

tmp = BOOTSTRAP.with_suffix(".patched.zip")
outer_replacements = 0
am_replacements = 0

with ZipFile(BOOTSTRAP, "r") as zin, ZipFile(tmp, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)

        if info.filename.endswith("libexec/termux-am/am.apk"):
            data, count = patch_termux_am_apk(data)
            am_replacements += count
        else:
            count = data.count(OLD)
            if count:
                data = data.replace(OLD, NEW)
                outer_replacements += count
            data = restore_java_class_names(data)

        if "com.termux" in info.filename:
            info.filename = info.filename.replace("com.termux", "com.termxx")
        zout.writestr(info, data)

tmp.replace(BOOTSTRAP)

with ZipFile(BOOTSTRAP, "r") as z:
    am_launcher = z.read("bin/am")
    if b"com.termux.termuxam.Am" not in am_launcher:
        raise SystemExit("bin/am Java entry point was not preserved")
    if b"com.termxx.termuxam.Am" in am_launcher:
        raise SystemExit("bin/am points at a non-existent renamed Java class")

    setup_storage = z.read("bin/termux-setup-storage")
    if b"com.termxx.app.reload_style" not in setup_storage:
        raise SystemExit("termux-setup-storage was not renamed to com.termxx")

digest = hashlib.sha256(BOOTSTRAP.read_bytes()).hexdigest()
print(f"bootstrap outer replacements: {outer_replacements}")
print(f"termux-am DEX replacements: {am_replacements}")
print(f"patched bootstrap sha256: {digest}")
