"""Independent proposed post-extraction binding check; never loads Java code.

Caller must first perform the unchanged whole-Boot and nested-JAR identity checks.
May be called before execution and again after evidence collection.
"""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
from urllib.parse import unquote, urlsplit
import zipfile


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(boot, expected_boot_sha, extracted, manifest, adapter_classes, origin_maps=()):
    boot, extracted, adapter_classes = map(Path, (boot, extracted, adapter_classes))
    require(sha(boot.read_bytes()) == expected_boot_sha, 'BOOT_IDENTITY_CHANGED')
    inventory, classes, migration = {}, set(), None
    with zipfile.ZipFile(boot) as z:
        names = [i.filename for i in z.infolist()]
        require(len(names) == len(set(names)), 'DUPLICATE_BOOT_ENTRY')
        for info in z.infolist():
            if info.is_dir():
                continue
            if info.filename.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(z.read(info))) as nested:
                    classes.update(n for n in nested.namelist() if n.endswith('.class'))
            if not info.filename.startswith('BOOT-INF/classes/'):
                continue
            name = info.filename.removeprefix('BOOT-INF/classes/')
            p = PurePosixPath(name)
            require(not p.is_absolute() and '..' not in p.parts and name == str(p), 'UNSAFE_APP_ENTRY')
            require(name not in inventory, 'DUPLICATE_APP_ENTRY')
            data = z.read(info)
            inventory[name] = sha(data)
            if name.endswith('.class'):
                classes.add(name)
            if name == 'db/migration/V22__preserve_persisted_timestamp_nanoseconds.sql':
                migration = data.decode('utf-8')
    require(json.loads(Path(manifest).read_text()) == inventory, 'EXTRACTOR_MANIFEST_DIFFERS_FROM_BOOT')
    actual = {}
    for p in extracted.rglob('*'):
        require(not p.is_symlink(), 'EXTRACTED_APP_SYMLINK')
        if p.is_file():
            actual[p.relative_to(extracted).as_posix()] = sha(p.read_bytes())
    require(actual == inventory, 'EXTRACTED_APP_BYTES_DIFFERS_FROM_BOOT')
    for p in adapter_classes.rglob('*.class'):
        require(p.relative_to(adapter_classes).as_posix() not in classes, 'ADAPTER_SHADOWS_PACKAGED_CLASS')
    expected_types = {'blue.myos.mini.persistence.CommandRepository', 'blue.myos.mini.persistence.CommandRecord',
                      'blue.myos.mini.command.RootedRetainedCommands', 'blue.myos.mini.command.CanonicalJson',
                      'blue.myos.mini.coordination.SdkEvidenceMapper'}
    for origins in origin_maps:
        require(set(origins) == expected_types, 'MISSING_EXECUTED_CLASS_ORIGIN')
        for name, evidence in origins.items():
            path = name.replace('.', '/') + '.class'
            require(evidence['sha256'] == inventory[path], 'EXECUTED_CLASS_DIFFERS_FROM_BOOT')
            location = urlsplit(evidence['codeSource'])
            require(location.scheme == 'file' and location.netloc in ('', 'localhost')
                    and not location.query and not location.fragment
                    and Path(unquote(location.path)).resolve() == extracted.resolve(), 'WRONG_EXECUTED_APP_LOCATION')
    require(migration is not None, 'MISSING_V22_MIGRATION')
    columns = re.findall(r'ALTER TABLE (mini_\w+) ALTER COLUMN (\w+) TIMESTAMP\(9\) WITH TIME ZONE;', migration)
    require(columns and len(set(columns)) == len(columns), 'INVALID_V22_COLUMN_INVENTORY')
    return sorted([table.upper(), column.upper()] for table, column in columns)
