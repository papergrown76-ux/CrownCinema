#!/usr/bin/env python3
"""Test the executable actually distributed in the JAR on native Linux hardware."""
import gzip,hashlib,platform,subprocess,tempfile,zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
version=ET.parse(ROOT/'pom.xml').getroot().findtext('{http://maven.apache.org/POM/4.0.0}version')
name={'x86_64':'linux-x86_64','aarch64':'linux-aarch64'}[platform.machine()]
with tempfile.TemporaryDirectory(prefix='crown-ffmpeg-') as folder:
    work=Path(folder)
    with zipfile.ZipFile(ROOT/f'downloads/crown-cinema-{version}.jar') as jar:
        manifest=dict(line.split('=',1) for line in jar.read('native/ffmpeg/manifest.properties').decode().splitlines())
        binary=gzip.decompress(jar.read('native/ffmpeg/'+name+'.gz'))
    assert hashlib.sha256(binary).hexdigest()==manifest[name+'.sha256']
    assert len(binary)==int(manifest[name+'.size'])
    exe=work/'ffmpeg';exe.write_bytes(binary);exe.chmod(0o700)
    def run(*args):
        return subprocess.run([str(exe),'-nostdin','-v','error','-y',*map(str,args)],check=True,timeout=30,stdout=subprocess.PIPE).stdout
    run('-version')
    fixture=ROOT/'src/test/resources/h264-aac.mp4'
    run('-i',fixture,'-vf','fps=4,scale=960:540,pad=960:540,setsar=1','-an','-frames:v','4',work/'frame_%02d.png')
    assert len(list(work.glob('frame_*.png')))==4
    run('-i',fixture,'-vn','-af','apad','-t','1','-ar','48000','-ac','2','-c:a','libvorbis',work/'audio.ogg')
    pcm=run('-i',work/'audio.ogg','-f','s16le','pipe:1')
    assert len(pcm)>96000 and any(abs(int.from_bytes(pcm[i:i+2],'little',signed=True))>500 for i in range(0,len(pcm)-1,2))
    print(name+': embedded FFmpeg runs; H.264 frames and audible AAC-to-Vorbis audio verified')
