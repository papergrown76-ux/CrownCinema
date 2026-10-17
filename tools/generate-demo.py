#!/usr/bin/env python3
"""Optional: redraw the original neutral demo. Requires Pillow and FFmpeg."""
from pathlib import Path
import io,json,subprocess,tempfile,zipfile
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
def main():
    output=ROOT/'src/main/resources/cinema';output.mkdir(exist_ok=True)
    providers=[{'type':'space','advances':{'\ue000':-1,'\ue001':-80}}];files={}
    for batch in range(5):
        for row in range(3):
            atlas=Image.new('RGBA',(1920,2880));chars=[]
            for y in range(12):
                line=''
                for x in range(8):
                    frame=batch*24+(y*8+x)//4;column=(y*8+x)%4
                    image=Image.new('RGB',(960,540),(12,18,28));draw=ImageDraw.Draw(image)
                    draw.rounded_rectangle((70,70,890,470),radius=25,outline=(70,170,220),width=6)
                    draw.text((290,210),'CROWN CINEMA',fill=(235,245,255),font_size=48)
                    draw.text((295,285),'ADD YOUR OWN VIDEO',fill=(100,190,230),font_size=28)
                    draw.rectangle((150,390,150+int(660*(frame+1)/120),400),fill=(70,170,220))
                    atlas.paste(image.crop((column*240,row*180,(column+1)*240,(row+1)*180)),(x*240,y*240))
                    line+=chr(0xe100+frame*12+row*4+column)
                chars.append(line)
            name=f'font/demo_{batch}_{row}.png';buffer=io.BytesIO();atlas.save(buffer,'PNG',optimize=True)
            files['assets/crowncinema/textures/'+name]=buffer.getvalue()
            providers.append({'type':'bitmap','file':'crowncinema:'+name,'height':20,'ascent':18-row*15,'chars':chars})
    files['pack.mcmeta']=json.dumps({'pack':{'pack_format':22,'supported_formats':{'min_inclusive':22,'max_inclusive':97},'min_format':22,'max_format':[97,1],'description':'Crown Cinema | Neutral demo'}}).encode()
    files['assets/crowncinema/font/movie.json']=json.dumps({'providers':providers}).encode()
    files['assets/crowncinema/sounds.json']=b'{"video":{"sounds":[{"name":"crowncinema:video","stream":false}]}}'
    with tempfile.TemporaryDirectory() as directory:
        audio=Path(directory)/'silent.ogg'
        subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i','anullsrc=r=48000:cl=stereo','-t','6','-c:a','libvorbis',str(audio)],check=True)
        files['assets/crowncinema/sounds/video.ogg']=audio.read_bytes()
    with zipfile.ZipFile(output/'crown-cinema.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,data)
    (output/'sequence.properties').write_text('frames=120\nfps=20\nfirst-glyph=57600\ncolumns=4\nrows=3\ntracking-glyph=57344\nrow-reset-glyph=57345\n')
if __name__=='__main__':main()
