#!/usr/bin/env python3
"""Optional real Minecraft 26.3 metadata/font codec validation; no game UI or login."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
from bootstrap import ROOT, fetch, verified
import sys
import zipfile


def main():
    pack=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'src/main/resources/cinema/crown-cinema.zip'
    with zipfile.ZipFile(pack) as archive:
        fonts=[json.loads(archive.read(name)) for name in archive.namelist() if name.startswith('assets/crowncinema/font/') and name.endswith('.json')]
    glyphs=sum(sum(sum(c!='\0' for c in row) for row in p.get('chars',[])) for font in fonts for p in font['providers'])
    target=ROOT/'.deps/minecraft-26.3-validation'
    libs=target/'libs';libs.mkdir(parents=True,exist_ok=True)
    manifest=json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
    entry=next(x for x in manifest['versions'] if x['id']=='26.3')
    version=json.loads(verified(entry['url'],'sha1',entry['sha1']))
    def artifact(info, path):
        if not path.exists() or hashlib.sha1(path.read_bytes()).hexdigest()!=info['sha1']:
            path.write_bytes(verified(info['url'],'sha1',info['sha1']))
    artifact(version['downloads']['client'],target/'client.jar')
    selected=[x for x in version['libraries'] if 'natives' not in x['name'] and any(k in x['name'] for k in [
        'gson','guava','failureaccess','fastutil','datafixerupper','logging','slf4j','log4j','brigadier','joml',
        'commons-lang3','netty','authlib','annotations','jspecify','commons-io','commons-codec'])]
    def download(lib):
        info=lib['downloads'].get('artifact')
        if info: artifact(info,libs/Path(info['path']).name)
    with ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(download,selected))
    classes=ROOT/'build/validation';classes.mkdir(parents=True,exist_ok=True)
    classpath=str(target/'client.jar')+':'+str(libs/'*')
    subprocess.run([str(ROOT/'.tools/jdk/bin/javac'),'-cp',classpath,'-d',str(classes),str(ROOT/'tools/ValidatePack.java')],check=True)
    subprocess.run([str(ROOT/'.tools/jdk/bin/java'),'-cp',str(classes)+':'+classpath,'ValidatePack',str(pack),str(glyphs)],check=True,cwd=classes)
    (ROOT/'build/pack-validation-result.json').write_text(json.dumps({'passed':True,'clientCodecs':'26.3',
        'packSha256':hashlib.sha256(pack.read_bytes()).hexdigest(),'visualClientTest':False},indent=2)+'\n')


if __name__=='__main__':main()
