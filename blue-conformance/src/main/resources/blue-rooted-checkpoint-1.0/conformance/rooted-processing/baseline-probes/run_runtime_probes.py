#!/usr/bin/env python3
"""Run the included probes against an existing MyOS boot JAR, offline.

Usage: python3 run_runtime_probes.py --jar /absolute/path/myos-mini.jar --output ./probe-run
Requires java and javac 21+ on PATH. Never modifies the input JAR.
These are narrow class-boundary tests, not the full application suite.
"""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tempfile, zipfile

def run(args: list[str], out: Path) -> None:
    done=subprocess.run(args, capture_output=True, text=True, timeout=90)
    out.write_text(done.stdout+done.stderr)
    if done.returncode:
        raise RuntimeError(f'{args[0]} returned {done.returncode}; see {out}')

def main() -> None:
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--jar',type=Path,required=True)
    ap.add_argument('--output',type=Path,default=Path('runtime-probe-results'))
    a=ap.parse_args(); jar=a.jar.resolve()
    if not jar.is_file(): raise FileNotFoundError(jar)
    for exe in ('java','javac'):
        if shutil.which(exe) is None: raise RuntimeError(f'{exe} not found on PATH')
    out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    source=Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix='blue-checkpoint-probe-') as tmp:
        root=Path(tmp);lib=root/'lib';lib.mkdir();classes=root/'classes';classes.mkdir()
        inventories=[]
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    p=lib/Path(name).name;data=z.read(name);p.write_bytes(data)
                    inventories.append({'file':p.name,'sha256':hashlib.sha256(data).hexdigest()})
        if not inventories: raise RuntimeError('No BOOT-INF/lib JARs found')
        import os
        cp=os.pathsep.join(str(p) for p in sorted(lib.glob('*.jar')))
        run(['javac','--release','21','-cp',cp,'-d',str(classes),
             str(source/'CheckpointRuntimeProbe.java'),str(source/'CursorRuntimeProbe.java')],out/'compile.log')
        for cls,filename in [('blue.coordination.processor.CheckpointRuntimeProbe','runtime_checkpoint.json'),
                             ('blue.coordination.internal.CursorRuntimeProbe','runtime_cursor.json')]:
            run(['java','-cp',str(classes)+os.pathsep+cp,cls],out/filename)
            result=json.loads((out/filename).read_text())
            if result.get('status')!='PASS': raise RuntimeError('Probe did not pass')
        (out/'provenance.json').write_text(json.dumps({'input_jar':str(jar),
             'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
             'libraries':inventories,'claim':'Class-boundary checks only; not application conformance'},indent=2)+'\n')
    print(f'PASS: {out}')
if __name__=='__main__': main()
