#!/usr/bin/env python3
"""Build portable LGPL FFmpeg executables; all downloaded inputs are checksum pinned."""
import gzip,hashlib,json,os,platform,shutil,struct,subprocess,sys,tarfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
LOCK=ROOT/'third-party/ffmpeg/sources.lock.json'
CACHE=ROOT/'.deps/ffmpeg';RESOURCES=CACHE/'resources';NATIVE=RESOURCES/'native/ffmpeg'
PLATFORMS={'linux-x86_64':('x86_64-linux-musl','x86_64',62),'linux-aarch64':('aarch64-linux-musl','aarch64',183)}
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def artifact(info):
    path=CACHE/'archives'/info['url'].rsplit('/',1)[1];path.parent.mkdir(parents=True,exist_ok=True)
    if not path.exists() or digest(path)!=info['sha256']:
        print('Downloading verified source/tool:',path.name,flush=True)
        data=urllib.request.urlopen(info['url'],timeout=120).read()
        if hashlib.sha256(data).hexdigest()!=info['sha256']:raise RuntimeError('Checksum mismatch: '+path.name)
        path.write_bytes(data)
    return path

def unpack(archive,destination):
    if destination.exists():return
    destination.mkdir(parents=True)
    with tarfile.open(archive) as tar:tar.extractall(destination,filter='data')

def run(command,cwd,env,log):
    with log.open('ab') as out:
        result=subprocess.run([str(x) for x in command],cwd=cwd,env=env,stdout=out,stderr=subprocess.STDOUT)
    if result.returncode:raise RuntimeError('Build failed; inspect '+str(log))

def static_elf(path,machine):
    data=path.read_bytes()
    if data[:6]!=b'\x7fELF\x02\x01' or struct.unpack_from('<H',data,18)[0]!=machine:raise RuntimeError('Wrong ELF architecture')
    offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
    if any(struct.unpack_from('<I',data,offset+i*size)[0]==3 for i in range(count)):raise RuntimeError('Bundled FFmpeg must be fully static, with no external ELF loader')

def main():
    lock=json.loads(LOCK.read_text())
    key=hashlib.sha256(LOCK.read_bytes()+Path(__file__).read_bytes()).hexdigest()
    manifest=NATIVE/'manifest.properties'
    if (CACHE/'build-key').exists() and (CACHE/'build-key').read_text()==key and manifest.exists():
        p=dict(line.split('=',1) for line in manifest.read_text().splitlines() if '=' in line)
        if all((NATIVE/(name+'.gz')).exists() and digest(NATIVE/(name+'.gz'))==p[name+'.gzip.sha256'] for name in PLATFORMS):
            if '--path' in sys.argv:print(CACHE/'bin/linux-x86_64/ffmpeg')
            return
    if platform.system()!='Linux' or platform.machine() not in ('x86_64','amd64'):raise SystemExit('Native build host must be Linux x86_64 (Zig cross-compiles both Linux targets).')
    archives={name:artifact(info) for name,info in lock.items() if isinstance(info,dict)}
    sources={}
    for name,archive in archives.items():
        dest=CACHE/'sources'/name;unpack(archive,dest)
        sources[name]=next(p for p in dest.iterdir() if p.is_dir())
    zig=sources['zig']/'zig';NATIVE.mkdir(parents=True,exist_ok=True)
    jobs='4'
    for name,(target,arch,machine) in PLATFORMS.items():
        output=CACHE/'bin'/name/'ffmpeg';output.parent.mkdir(parents=True,exist_ok=True)
        work=ROOT/'build/ffmpeg-native'/name;work.mkdir(parents=True,exist_ok=True)
        prefix=work/'prefix';prefix.mkdir(exist_ok=True)
        log=work/'build.log';log.write_text('')
        env=os.environ.copy();env.update({'CC':str(zig)+' cc -target '+target,'AR':str(zig)+' ar','RANLIB':str(zig)+' ranlib',
            'CFLAGS':'-O2','PKG_CONFIG_LIBDIR':str(prefix/'lib/pkgconfig'),'PKG_CONFIG_PATH':str(prefix/'lib/pkgconfig'),
            'ZIG_GLOBAL_CACHE_DIR':str(CACHE/'zig-cache')})
        print('Building static FFmpeg for',name,flush=True)
        for dep in ['ogg','vorbis','zlib']:
            dest=work/dep
            if dest.exists():shutil.rmtree(dest)
            shutil.copytree(sources[dep],dest)
            if dep=='zlib':args=['./configure','--static','--prefix='+str(prefix)]
            else:args=['./configure','--host='+target,'--prefix='+str(prefix),'--disable-shared','--enable-static']
            run(args,dest,env,log);run(['make','-j'+jobs],dest,env,log);run(['make','install'],dest,env,log)
        dest=work/'ffmpeg'
        if dest.exists():shutil.rmtree(dest)
        shutil.copytree(sources['ffmpeg'],dest)
        args=['./configure','--prefix='+str(prefix),'--enable-cross-compile','--target-os=linux','--arch='+arch,
            '--cc='+env['CC'],'--ar='+env['AR'],'--ranlib='+env['RANLIB'],'--disable-stripping',
            '--extra-cflags=-I'+str(prefix/'include'),'--extra-ldflags=-static -s -L'+str(prefix/'lib'),
            '--pkg-config=pkg-config','--pkg-config-flags=--static','--disable-autodetect','--disable-everything',
            '--disable-gpl','--disable-nonfree','--disable-shared','--enable-static','--disable-debug','--disable-doc',
            '--disable-network','--disable-asm','--disable-ffplay','--disable-ffprobe','--enable-ffmpeg','--enable-avfilter',
            '--enable-swscale','--enable-swresample','--enable-libvorbis','--enable-zlib',
            '--enable-protocol=file,pipe','--enable-demuxer=mov,matroska,avi,ogg,mp3,flac,wav,aac,image2',
            '--enable-decoder=h264,hevc,vp8,vp9,mpeg4,mjpeg,aac,mp3,vorbis,opus,flac,pcm_s16le,pcm_s24le,pcm_f32le,pcm_f64le,pcm_u8,wrapped_avframe,png',
            '--enable-parser=h264,hevc,vp8,vp9,mpeg4video,mjpeg,aac,mpegaudio,vorbis,opus',
            '--enable-encoder=png,libvorbis,mpeg4,aac,pcm_s16le','--enable-muxer=image2,ogg,null,mov,mp4,pcm_s16le',
            '--enable-indev=lavfi','--enable-filter=fps,scale,pad,setsar,apad,anull,anullsrc,aformat,aresample,format,trim,setpts,atrim,asetpts,color,sine']
        run(args,dest,env,log);run(['make','-j'+jobs],dest,env,log)
        shutil.copy2(dest/'ffmpeg',output);output.chmod(0o755);static_elf(output,machine)
        if name=='linux-x86_64':
            check=subprocess.check_output([str(output),'-L'],stderr=subprocess.STDOUT,text=True)
            if 'GNU Lesser General Public' not in check:raise RuntimeError('Expected LGPL build')
        (NATIVE/(name+'.gz')).write_bytes(gzip.compress(output.read_bytes(),compresslevel=9,mtime=0))
    lines=['version='+lock['ffmpeg']['version']]
    for name in PLATFORMS:
        p=CACHE/'bin'/name/'ffmpeg';lines += [name+'.sha256='+digest(p),name+'.size='+str(p.stat().st_size),name+'.gzip.sha256='+digest(NATIVE/(name+'.gz'))]
    manifest.write_text('\n'.join(lines)+'\n')
    licenses=ROOT/'third-party/ffmpeg/licenses';licenses.mkdir(exist_ok=True)
    for dep,filename in [('ffmpeg','COPYING.LGPLv2.1'),('ogg','COPYING'),('vorbis','COPYING'),('zlib','README')]:
        shutil.copy2(sources[dep]/filename,licenses/(dep+'-'+filename))
    (CACHE/'build-key').write_text(key)
    print('Bundled native resources ready:',sum(p.stat().st_size for p in NATIVE.glob('*.gz')),'compressed bytes',flush=True)
    if '--path' in sys.argv:print(CACHE/'bin/linux-x86_64/ffmpeg')
if __name__=='__main__':main()
