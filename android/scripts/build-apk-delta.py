"""Build an exact-byte APK delta by reusing identical ZIP member data."""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import zipfile

MAGIC = b'STUDIOD1'
BUFFER = 65536
MAX_APK = 1024 * 1024 * 1024
MAX_OPERATIONS = 16384


def sha256(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def members(archive, source):
    entries = {}
    for entry in archive.infolist():
        if entry.is_dir() or not entry.compress_size:
            continue
        if entry.filename in entries:
            raise ValueError('Duplicate APK entry')
        source.seek(entry.header_offset)
        header = source.read(30)
        if len(header) != 30 or header[:4] != b'PK\x03\x04':
            raise ValueError('Invalid APK local header')
        name_size, extra_size = struct.unpack_from('<HH', header, 26)
        offset = entry.header_offset + 30 + name_size + extra_size
        if offset + entry.compress_size > archive.start_dir:
            raise ValueError('APK payload overlaps the directory')
        entries[entry.filename] = (offset, entry.compress_size, entry.compress_type, entry.CRC)
    return entries


def equal_slice(base, new, base_offset, new_offset, size):
    base.seek(base_offset)
    new.seek(new_offset)
    while size:
        count = min(BUFFER, size)
        left, right = base.read(count), new.read(count)
        if len(left) != count or left != right:
            return False
        size -= count
    return True


def write_literal(target, patch, offset, size):
    if size <= 0:
        return 0
    patch.write(struct.pack('>BI', 0, size))
    target.seek(offset)
    remaining = size
    while remaining:
        chunk = target.read(min(BUFFER, remaining))
        if not chunk:
            raise EOFError('Truncated target APK')
        patch.write(chunk)
        remaining -= len(chunk)
    return 1


def build(base, target, delta):
    if not all(0 < item.stat().st_size <= MAX_APK for item in (base, target)):
        raise ValueError('APK size outside bounds')
    target_size = target.stat().st_size
    created = False
    try:
        with base.open('rb') as old, target.open('rb') as new, delta.open('xb') as patch:
            created = True
            with zipfile.ZipFile(base) as old_zip, zipfile.ZipFile(target) as new_zip:
                previous, next_entries = members(old_zip, old), members(new_zip, new)
            copies = []
            for name, current in next_entries.items():
                prior = previous.get(name)
                if prior and prior[1:] == current[1:] and equal_slice(old, new, prior[0], current[0], current[1]):
                    copies.append((current[0], prior[0], current[1]))
            patch.write(MAGIC)
            cursor = count = 0
            for target_offset, source_offset, size in sorted(copies):
                if target_offset < cursor:
                    raise ValueError('Overlapping target APK ranges')
                count += write_literal(new, patch, cursor, target_offset - cursor)
                patch.write(struct.pack('>BQI', 1, source_offset, size))
                count += 1
                cursor = target_offset + size
            count += write_literal(new, patch, cursor, target_size - cursor)
            patch.write(b'\xff')
            if count >= MAX_OPERATIONS:
                raise ValueError('Too many delta commands')
        if delta.stat().st_size >= target_size:
            raise ValueError('Delta does not save download bytes')
    except Exception:
        if created:
            delta.unlink(missing_ok=True)
        raise
    return {'baseSHA256': sha256(base), 'size': delta.stat().st_size, 'sha256': sha256(delta),
            'targetSHA256': sha256(target), 'targetSize': target_size}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base', type=Path)
    parser.add_argument('target', type=Path)
    parser.add_argument('delta', type=Path)
    args = parser.parse_args()
    if args.delta.exists():
        parser.error('Choose a new output path')
    print(json.dumps(build(args.base, args.target, args.delta), indent=2))
