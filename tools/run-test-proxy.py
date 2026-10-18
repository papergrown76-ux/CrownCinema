#!/usr/bin/env python3
"""Local-only Velocity + LimboAPI runtime. Never edits a production proxy."""
import argparse
import os
from pathlib import Path
import shutil
import sys
import urllib.parse
import subprocess
import zipfile
import json
from bootstrap import ROOT, verified, VELOCITY_URL, VELOCITY_SHA256


def prepare(smoke=False, real_bridge=False):
    runtime = ROOT / 'build' / ('real-bridge-proxy' if real_bridge else 'smoke-runtime' if smoke else 'test-proxy')
    plugins = runtime / 'plugins'
    plugins.mkdir(parents=True, exist_ok=True)
    jar = ROOT / 'target/crown-cinema.jar'
    if not jar.is_file():
        raise SystemExit('Build the plugin first: ./mvnw verify')
    velocity = ROOT / '.deps/velocity-4.2.1-41.jar'
    if not velocity.is_file():
        velocity.write_bytes(verified(VELOCITY_URL, 'sha256', VELOCITY_SHA256))
    import hashlib
    if hashlib.sha256(velocity.read_bytes()).hexdigest() != VELOCITY_SHA256:
        raise SystemExit('Velocity checksum mismatch; refusing to start')
    shutil.copy2(jar, plugins/'crown-cinema.jar')
    limbo = ROOT/'.deps/repository/net/elytrium/limboapi/limboapi/1.1.27-bfef579/limboapi-1.1.27-bfef579.jar'
    shutil.copy2(limbo, plugins/'limboapi.jar')
    config=runtime/'velocity.toml'
    if smoke or real_bridge or not config.exists():
        config.write_text('''# Local development ONLY: offline authentication, loopback binding.
config-version = "2.9"
bind = "127.0.0.1:25565"
motd = "Crown Cinema local test"
show-max-players = 20
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
forwarding-secret-file = "forwarding.secret"
[servers]
lobby = "127.0.0.1:25566"
old = "127.0.0.1:25567"
launch = "127.0.0.1:25569"
disabled = "127.0.0.1:25569"
try = ["lobby"]
[forced-hosts]
"old.crown.test" = ["old"]
"off.crown.test" = ["disabled"]
[advanced]
login-ratelimit = 0
''')
    if real_bridge:
        config.write_text(config.read_text().replace('lobby = "127.0.0.1:25566"','lobby = "127.0.0.1:25576"'))
        own=plugins/'crowncinema';own.mkdir(exist_ok=True)
        (own/'config.properties').write_text('pack.url=\npack.http.bind=127.0.0.1\npack.http.port=28124\ncinema.server.lobby=bridge\ncinema.endcard-seconds=1\n',encoding='utf-8')
        # Explicitly grant the named protocol-test actor admin rights, only in this isolated runtime.
        fixture=ROOT/'build/test-permissions';fixture.mkdir(exist_ok=True)
        subprocess.run([str(ROOT/'.tools/jdk/bin/javac'),'-proc:none','-cp',str(velocity),'-d',str(fixture),str(ROOT/'tools/CinemaTestPermissions.java')],check=True)
        with zipfile.ZipFile(plugins/'cinema-test-permissions.jar','w') as archive:
            archive.write(fixture/'de/crown/test/CinemaTestPermissions.class','de/crown/test/CinemaTestPermissions.class')
            archive.writestr('velocity-plugin.json',json.dumps({'id':'cinema-test-permissions','name':'Isolated test permissions','version':'0','main':'de.crown.test.CinemaTestPermissions'}))
        videos=own/'videos';videos.mkdir(exist_ok=True)
        binary=ROOT/'.deps/ffmpeg/bin/linux-x86_64/ffmpeg'
        subprocess.run([str(binary),'-nostdin','-v','error','-y','-f','lavfi','-i','color=c=blue:s=320x180:r=20:d=6',
                        '-f','lavfi','-i','sine=frequency=330:duration=6','-c:v','mpeg4',
                        '-c:a','aac','-shortest',str(videos/'test-pattern.mp4')],check=True)
        (own/'video.properties').write_text('file=test-pattern.mp4\nfps=20\nffmpeg=auto\n',encoding='utf-8')
    if smoke:
        own=plugins/'crowncinema';own.mkdir(exist_ok=True)
        (own/'config.properties').write_text('pack.url=\npack.required=false\npack.http.enabled=true\npack.http.bind=127.0.0.1\npack.http.port=28124\ncinema.endcard-seconds=1\ncinema.server.lobby=proxy\ncinema.server.launch=bridge\n',encoding='utf-8')
    return runtime, velocity


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--smoke',action='store_true',help='Prepare the disposable protocol-test configuration')
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--real-bridge',action='store_true',help='Use the local Paper 26.2 backend on port 25576')
    args=parser.parse_args()
    runtime,velocity=prepare(args.smoke,args.real_bridge)
    if args.prepare_only:
        print(runtime)
        raise SystemExit(0)
    os.chdir(runtime)
    java=ROOT/'.tools/jdk/bin/java'
    command=[str(java),'-Xms128m','-Xmx512m','-Djavax.net.ssl.trustStore='+str(ROOT/'.tools/cacerts'),
             '-Djavax.net.ssl.trustStorePassword=changeit']
    proxy_url=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
    if proxy_url:
        proxy=urllib.parse.urlsplit(proxy_url)
        if proxy.username or proxy.password:
            raise SystemExit('Use secure JVM proxy configuration for authenticated proxies.')
        for scheme in ['http','https']:
            command += [f'-D{scheme}.proxyHost={proxy.hostname}',f'-D{scheme}.proxyPort={proxy.port or 80}']
        command += ['-Dhttp.nonProxyHosts=127.*|localhost']
    os.execv(java,command+['-jar',str(velocity)])
