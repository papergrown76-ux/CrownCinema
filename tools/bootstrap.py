#!/usr/bin/env python3
"""Pinned development tools and compile-only LimboAPI; TLS/checksums stay enabled."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tarfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
USER_AGENT = 'CrownCinema/0.1 (+https://github.com/papergrown76-ux/CrownCinema)'
JDK_URL = 'https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jdk25.0.4.1-linux_x64.tar.gz'
JDK_SHA256 = 'e11d92589de8fd55616a843e0298ba72348848bd49b675088e930b67538497cb'
LIMBO_URL = 'https://cdn.modrinth.com/data/TZOteSf2/versions/zOTyppCs/limboapi-1.1.27-SNAPSHOT.jar'
LIMBO_SHA512 = 'b78ae1a9b87716bace7ccadc5ebe555f7a8b7ef18fed6f549cfac3a1f784e6bfe1760637283e0f4a6808dfd0488dd68311fc2672b7039367668c3217a92f038d'
VELOCITY_URL = 'https://fill-data.papermc.io/v1/objects/aab3cc7e60ee758ea8cfff3cb0ab88e92aececfd0025061c90f00d6aa9f1aa1e/velocity-4.2.1-SNAPSHOT-41.jar'
VELOCITY_SHA256 = 'aab3cc7e60ee758ea8cfff3cb0ab88e92aececfd0025061c90f00d6aa9f1aa1e'


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': USER_AGENT}), timeout=120) as response:
        return response.read()


def verified(url, algorithm, expected):
    data = fetch(url)
    if hashlib.new(algorithm, data).hexdigest() != expected:
        raise RuntimeError('Checksum mismatch: ' + url)
    return data


def install_archive(data, destination):
    import io
    temporary = TOOLS / ('unpack-' + destination.name)
    temporary.mkdir(exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        archive.extractall(temporary, filter='data')
    children = list(temporary.iterdir())
    if len(children) != 1 or not children[0].is_dir():
        raise RuntimeError('Unexpected tool archive layout')
    children[0].rename(destination)
    temporary.rmdir()


def main():
    import platform
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
        raise SystemExit('The bundled bootstrap targets Linux x86_64. Use Java 25 + Maven 3.9.11 and tools/bootstrap.py --dependency-only on other platforms.')
    TOOLS.mkdir(exist_ok=True)
    if not (TOOLS / 'jdk/bin/java').exists():
        print('Installing Java 25 (verified SHA-256)...', flush=True)
        install_archive(verified(JDK_URL, 'sha256', JDK_SHA256), TOOLS / 'jdk')
    if not (TOOLS / 'maven/bin/mvn').exists():
        print('Installing Maven 3.9.11 (verified SHA-512)...', flush=True)
        url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz'
        checksum = fetch(url + '.sha512').decode().split()[0]
        install_archive(verified(url, 'sha512', checksum), TOOLS / 'maven')
    dependency_only()
    # Add public platform CA certificates to a project-local copy of Java's trust store.
    truststore = TOOLS / 'cacerts'
    if not truststore.exists():
        shutil.copy2(TOOLS / 'jdk/lib/security/cacerts', truststore)
        for index, certificate in enumerate(sorted(Path('/usr/local/share/ca-certificates').rglob('*.crt'))):
            subprocess.run([str(TOOLS / 'jdk/bin/keytool'), '-importcert', '-noprompt', '-trustcacerts',
                            '-alias', 'crown-platform-' + str(index), '-file', str(certificate),
                            '-keystore', str(truststore), '-storepass', 'changeit'], check=True, capture_output=True)
    settings = ET.Element('settings', xmlns='http://maven.apache.org/SETTINGS/1.2.0')
    proxy_url = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
    if proxy_url:
        proxy = urllib.parse.urlsplit(proxy_url)
        if proxy.username or proxy.password:
            raise RuntimeError('Configure authenticated Maven proxy through your own secure settings; credentials are never copied by bootstrap.')
        proxies = ET.SubElement(settings, 'proxies')
        node = ET.SubElement(proxies, 'proxy')
        for key, value in {'id': 'session-proxy', 'active': 'true', 'protocol': 'http', 'host': proxy.hostname,
                           'port': str(proxy.port or 80), 'nonProxyHosts': 'localhost|127.0.0.1'}.items():
            ET.SubElement(node, key).text = value
    ET.ElementTree(settings).write(TOOLS / 'settings.xml', encoding='UTF-8', xml_declaration=True)


def dependency_only():
    directory = ROOT / '.deps/repository/net/elytrium/limboapi/limboapi/1.1.27-bfef579'
    directory.mkdir(parents=True, exist_ok=True)
    jar = directory / 'limboapi-1.1.27-bfef579.jar'
    if not jar.exists() or hashlib.sha512(jar.read_bytes()).hexdigest() != LIMBO_SHA512:
        print('Downloading pinned LimboAPI bfef579 (verified SHA-512)...', flush=True)
        jar.write_bytes(verified(LIMBO_URL, 'sha512', LIMBO_SHA512))
    pom = directory / 'limboapi-1.1.27-bfef579.pom'
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>net.elytrium.limboapi</groupId><artifactId>limboapi</artifactId>'
                   '<version>1.1.27-bfef579</version></project>\n')
    for path in (jar, pom):
        path.with_name(path.name + '.sha1').write_text(hashlib.sha1(path.read_bytes()).hexdigest())
        path.with_name(path.name + '.sha512').write_text(hashlib.sha512(path.read_bytes()).hexdigest())
    # The proxy's own packet encoder handles protocol changes for our Limbo sound.
    proxy = ROOT / '.deps/velocity-4.2.1-41.jar'
    if not proxy.exists() or hashlib.sha256(proxy.read_bytes()).hexdigest() != VELOCITY_SHA256:
        proxy.write_bytes(verified(VELOCITY_URL, 'sha256', VELOCITY_SHA256))
    directory = ROOT / '.deps/repository/com/velocitypowered/velocity-proxy-pinned/4.2.1-41'
    directory.mkdir(parents=True, exist_ok=True)
    jar = directory / 'velocity-proxy-pinned-4.2.1-41.jar'
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != VELOCITY_SHA256:
        shutil.copy2(proxy, jar)
    pom = directory / 'velocity-proxy-pinned-4.2.1-41.pom'
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                   '<groupId>com.velocitypowered</groupId><artifactId>velocity-proxy-pinned</artifactId>'
                   '<version>4.2.1-41</version></project>\n')
    for path in (jar, pom):
        path.with_name(path.name + '.sha1').write_text(hashlib.sha1(path.read_bytes()).hexdigest())
        path.with_name(path.name + '.sha512').write_text(hashlib.sha512(path.read_bytes()).hexdigest())


if __name__ == '__main__':
    import sys
    if '--dependency-only' in sys.argv:
        dependency_only()
    else:
        main()
