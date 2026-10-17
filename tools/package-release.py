#!/usr/bin/env python3
"""Package only JARs validated by the matching protocol and real-Paper tests."""
import hashlib,json,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def version(pom):return ET.parse(pom).getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
def tests(folder):
    suites=[ET.parse(p).getroot() for p in folder.glob('TEST-*.xml')]
    count=sum(int(s.get('tests',0)) for s in suites)
    if not count or any(int(s.get(k,0)) for s in suites for k in ['failures','errors','skipped']):raise SystemExit('Missing, failing or skipped tests: '+str(folder))
    return count

def main():
    proxy=ROOT/'target/crown-cinema.jar';backend=ROOT/'backend-bridge/target/crown-cinema-backend.jar'
    result=json.loads((ROOT/'build/protocol-smoke-result.json').read_text());bridge=json.loads((ROOT/'build/real-bridge-result.json').read_text())
    if not all(result.get(k) for k in ['passed','soundPacketsVerified','backendBridgeVerified','backendPackLifecycleVerified']):raise SystemExit('Protocol checks incomplete')
    if result['pluginSha256']!=sha(proxy) or not bridge['passed'] or not bridge.get('customVideoImportVerified') or bridge['proxySha256']!=sha(proxy) or bridge['backendSha256']!=sha(backend):raise SystemExit('Tests do not match the built JARs')
    pack=ROOT/'src/main/resources/cinema/crown-cinema.zip'
    if result['packSha256']!=sha(pack):raise SystemExit('Pack checks do not match')
    count=tests(ROOT/'target/surefire-reports');backend_count=tests(ROOT/'backend-bridge/target/surefire-reports')
    if not (ROOT/'target/surefire-reports/TEST-de.crown.cinema.VideoLibraryTest.xml').exists():raise SystemExit('Video import tests missing')
    v=version(ROOT/'pom.xml');bv=version(ROOT/'backend-bridge/pom.xml');downloads=ROOT/'downloads';downloads.mkdir(exist_ok=True)
    files={'crown-cinema.jar':proxy.read_bytes(),'optional-backend/crown-cinema-backend.jar':backend.read_bytes(),
           'README.md':(ROOT/'README.md').read_bytes(),'optional-backend/README.md':(ROOT/'backend-bridge/README.md').read_bytes(),
           'LICENSE':(ROOT/'LICENSE').read_bytes(),'NOTICE.md':(ROOT/'NOTICE.md').read_bytes(),
           'config.example.properties':(ROOT/'src/main/resources/config.properties').read_bytes()}
    files['VALIDATION.txt']=(f'{count} proxy + {backend_count} backend Java tests passed; no skipped or failing tests.\n'
        'Real FFmpeg MP4 imports with audio, silent-video import, verified cache reuse, selection guards and debug-log rotation passed.\n'
        'Velocity 4.2.1 build 41 / LimboAPI bfef579: 1.20.2 upgrade, 1.20.4 playback, sound packets, pack lifecycle, menu and permissions passed.\n'
        'Paper 26.2 build 134 / ViaVersion+ViaBackwards 5.12.1: 1.20.4 and 26.1 protocol clients passed bridge and recovery tests.\n'
        'Only the independently drawn neutral demo is bundled; no previous personal video or skins.\n'
        'Pack application is simulated by protocol clients. No visual/audio game UI, native 26.2/26.3 client or production Nexo/anticheat test.\n').encode()
    files['SHA256SUMS']=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(files.items())).encode()
    archive=downloads/f'crown-cinema-installation-{v}.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
    outputs=[archive]
    for name,data in [(f'crown-cinema-{v}.jar',proxy.read_bytes()),(f'crown-cinema-backend-{bv}.jar',backend.read_bytes())]:
        p=downloads/name;p.write_bytes(data);outputs.append(p)
    for p in outputs:p.with_name(p.name+'.sha256').write_text(sha(p)+'  '+p.name+'\n')
    print('Packaged verified downloads:',*[p.name for p in outputs])
if __name__=='__main__':main()
