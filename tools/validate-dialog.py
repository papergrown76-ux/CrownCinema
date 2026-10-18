#!/usr/bin/env python3
"""Use the actual test backend's vanilla dialog codec; no extra client download."""
import hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
paper=ROOT/'build/real-paper-bridge/versions/26.2/paper-26.2.jar'
if not paper.is_file():raise SystemExit('Run the real Paper integration test first.')
classpath=':'.join([str(paper),*[str(p) for p in (ROOT/'build/real-paper-bridge/libraries').rglob('*.jar')]])
classes=ROOT/'build/dialog-codec';classes.mkdir(exist_ok=True)
subprocess.run([str(ROOT/'.tools/jdk/bin/javac'),'-cp',classpath,'-d',str(classes),str(ROOT/'tools/ValidateDialog.java')],check=True)
subprocess.run([str(ROOT/'.tools/jdk/bin/java'),'-cp',str(classes)+':'+classpath,'ValidateDialog',str(ROOT/'build/dialog-validation.snbt'),'140'],check=True,cwd=classes)
(ROOT/'build/dialog-validation-result.json').write_text(json.dumps({'passed':True,'codec':'Paper 26.2','pluginSha256':hashlib.sha256((ROOT/'target/crown-cinema.jar').read_bytes()).hexdigest()},indent=2)+'\n')
