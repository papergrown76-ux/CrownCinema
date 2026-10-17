#!/usr/bin/env python3
"""Loopback-only Paper 26.2 + ViaVersion/Backwards runtime for bridge regression tests."""
import os, shutil, urllib.parse, hashlib, subprocess, zipfile
from pathlib import Path
from bootstrap import ROOT, verified

ARTIFACTS = [{'name': 'ViaVersion', 'version': '5.12.1', 'url': 'https://cdn.modrinth.com/data/P1OZGk5p/versions/cc7QiCYw/ViaVersion-5.12.1.jar', 'algorithm': 'sha512', 'hash': 'b2e5c03689e2265410613a928640076cfcd576e7a3dc7ca1276a76dbe624f2f5467348c3b3eaf8b5987ed7662afb93cc5cb9373035f4c5512884116e4bb6add9', 'filename': 'ViaVersion.jar'}, {'name': 'ViaBackwards', 'version': '5.12.1', 'url': 'https://cdn.modrinth.com/data/NpvuJQoq/versions/GXuG4GNJ/ViaBackwards-5.12.1.jar', 'algorithm': 'sha512', 'hash': '65752dd113bf1266ef2c3b25a748e79e9808bb67682a3f5fbcf3e2b097d76182786d0871562175af8d491bde7ec6addfeaf97fdb23987cb11b829b0c22f6e5ff', 'filename': 'ViaBackwards.jar'}, {'name': 'Paper', 'version': '26.2-134', 'url': 'https://fill-data.papermc.io/v1/objects/3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b/paper-26.2-134.jar', 'algorithm': 'sha256', 'hash': '3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b', 'filename': 'paper.jar'}]

runtime=ROOT/'build/real-paper-bridge'
plugins=runtime/'plugins';plugins.mkdir(parents=True,exist_ok=True)
cache=ROOT/'.deps/paper-bridge-test';cache.mkdir(parents=True,exist_ok=True)
for info in ARTIFACTS:
    artifact=cache/info['filename']
    if not artifact.exists() or hashlib.new(info['algorithm'],artifact.read_bytes()).hexdigest()!=info['hash']:
        artifact.write_bytes(verified(info['url'],info['algorithm'],info['hash']))
    if info['name']!='Paper': shutil.copy2(artifact,plugins/info['filename'])
shutil.copy2(ROOT/'backend-bridge/target/crown-cinema-backend.jar',plugins/'crown-cinema-backend.jar')
classes=ROOT/'build/bridge-test-faults';classes.mkdir(exist_ok=True)
api=next((ROOT/'.tools/m2/org/spigotmc/spigot-api').rglob('spigot-api-*.jar'))
subprocess.run([str(ROOT/'.tools/jdk/bin/javac'),'--release','17','-cp',str(api),'-d',str(classes),str(ROOT/'tools/BridgeTestFaults.java')],check=True)
with zipfile.ZipFile(plugins/'bridge-test-faults.jar','w') as archive:
    archive.write(classes/'BridgeTestFaults.class','BridgeTestFaults.class')
    archive.writestr('plugin.yml','name: CrownBridgeTestFaults\nversion: 1\nmain: BridgeTestFaults\napi-version: "1.20"\ncommands:\n  cinemafault:\n    description: Local bridge regression test only\npermissions:\n  crownbridgetest.admin:\n    default: op\n')

(runtime/'eula.txt').write_text('# Isolated local development server only.\neula=true\n')
(runtime/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\ngenerate-structures=false\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\nallow-flight=true\ndifficulty=peaceful\nmax-players=5\n')
command=[str(ROOT/'.tools/jdk/bin/java'),'-Xms256m','-Xmx1536m','-Dterminal.jline=false','-Dterminal.ansi=false','-Djavax.net.ssl.trustStore='+str(ROOT/'.tools/cacerts'),'-Djavax.net.ssl.trustStorePassword=changeit']
proxy_url=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy_url:
    proxy=urllib.parse.urlsplit(proxy_url)
    if proxy.username or proxy.password: raise SystemExit('Use secure JVM proxy configuration for authenticated proxies')
    for scheme in ['http','https']:
        command += [f'-D{scheme}.proxyHost={proxy.hostname}',f'-D{scheme}.proxyPort={proxy.port or 80}']
    command += ['-Dhttp.nonProxyHosts=127.*|localhost']
os.chdir(runtime)
os.execv(command[0],command+['-jar',str(cache/'paper.jar'),'--nogui'])
