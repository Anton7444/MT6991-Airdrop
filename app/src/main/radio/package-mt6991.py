import hashlib
from copy import copy
from pathlib import Path
from struct import pack_into, unpack_from
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

def restore_wifi_policy(daemon: bytes) -> bytes:
    old_digest = "51975d0a4eda2e4de878e98d417b6b64526b0d2edef17f05778c99787e504891"
    offsets = (0x64F90, 0xCF230, 0x11DF60)
    unconditional = bytes.fromhex("f6 00 00 14")
    conditional = bytes.fromhex("c0 1e 00 37")
    restored = bytearray(daemon)
    for offset in offsets:
        restored[offset : offset + 4] = conditional

    original = bytearray(restored)
    for offset in offsets:
        original[offset : offset + 4] = unconditional
    if hashlib.sha256(original).hexdigest() != old_digest:
        raise ValueError("Unexpected Pad 2 daemon build")
    if daemon not in (bytes(original), bytes(restored)):
        raise ValueError("Pad 2 daemon has inconsistent coexistence branches")
    return bytes(restored)

def channel_six_daemon(daemon: bytes) -> bytes:

    sites = (
        (0x66068, 0), (0x66238, 4),
        (0xD0308, 0), (0xD04D8, 4),
        (0x11F038, 0), (0x11F208, 4),
    )
    profile = bytearray(daemon)
    for offset, register in sites:
        mov = 0x52800000 | register
        if unpack_from("<I", daemon, offset)[0] != mov | (149 << 5):
            raise ValueError(f"Unexpected channel instruction at {offset:#x}")
        pack_into("<I", profile, offset, mov | (6 << 5))
    return bytes(profile)

MODULE_PROP = "\n".join(
    [
        "id=owly",
        "name=AirDrop (MT6991)",
        "version=1.0.0-mt6991",
        "versionCode=100",
        "author=happybear; kernel stack ported from OPAirDrop (TimelessU/Weverses)",
        "description=AirDrop for MediaTek MT6991 devices: OnePlus Pad 2, OPPO Find X8s and X8s+.",
        "",
    ]
).encode()

def strip_hash_comments(data: bytes) -> bytes:
    out = []
    sq = dq = False
    for number, line in enumerate(data.decode().split("\n")):
        if number == 0 and line.startswith("#!"):
            out.append(line)
            continue
        kept = []
        cut = False
        i = 0
        while i < len(line):
            c = line[i]
            if sq:
                sq = c != "'"
            elif dq:
                if c == "\\" and i + 1 < len(line):
                    kept.append(line[i : i + 2])
                    i += 2
                    continue
                dq = c != '"'
            elif c == "\\" and i + 1 < len(line):
                kept.append(line[i : i + 2])
                i += 2
                continue
            elif c == "'":
                sq = True
            elif c == '"':
                dq = True
            elif c == "#" and (i == 0 or line[i - 1] in " \t;&|("):
                cut = True
                break
            kept.append(c)
            i += 1
        text = "".join(kept)
        if cut:
            text = text.rstrip()
            if not text.strip():
                continue
        out.append(text)
    return ("\n".join(out).rstrip("\n") + "\n").encode()


def main() -> None:
    source = Path(__file__).resolve().parent
    asset = source.parent / "assets" / "radio-mt6991.zip"
    staged = asset.with_suffix(".zip.new")
    try:
        with ZipFile(asset) as old, ZipFile(staged, "w") as new:
            daemon = restore_wifi_policy(old.read("owly"))
            for entry in old.infolist():
                if entry.filename.startswith("profiles/6/"):
                    continue
                if entry.filename == "bin/remove.sh":
                    continue
                data = old.read(entry)
                if entry.filename == "owly":
                    data = daemon
                elif entry.filename == "bin/speed-vcmd":
                    data = (source / "speed-vcmd.bin").read_bytes()
                elif entry.filename == "service.sh":
                    data = (source / "service.sh").read_bytes()
                elif entry.filename in {"bin/owly-watch.sh", "bin/stack.sh", "bin/speed-watch.sh"}:
                    data = (source / Path(entry.filename).name).read_bytes()
                elif entry.filename == "module.prop":
                    data = MODULE_PROP
                elif entry.filename.endswith((".sh", ".toml")):
                    data = strip_hash_comments(data)
                new.writestr(copy(entry), data)
            remove = ZipInfo("bin/remove.sh")
            remove.external_attr = 0o755 << 16
            new.writestr(remove, (source / "remove.sh").read_bytes(), compress_type=ZIP_DEFLATED)
            new.writestr(ZipInfo("profiles/6/owly"),channel_six_daemon(daemon), compress_type=ZIP_DEFLATED)
            new.writestr(ZipInfo("profiles/6/owly_coex.ko"), old.read("owly_coex.ko"), compress_type=ZIP_DEFLATED)
        with ZipFile(staged) as check:
            damaged = check.testzip()
            if damaged:
                raise ValueError(f"Damaged ZIP member {damaged}")
        staged.replace(asset)
    finally:
        staged.unlink(missing_ok=True)

if __name__ == "__main__":
    main()
