#!/usr/bin/env bash
# Makes small sample files of every supported type for the emulator test.
# usage: make_samples.sh <out_dir>
set -euo pipefail
OUT=$1
mkdir -p "$OUT"
cd "$OUT"
F="ffmpeg -hide_banner -loglevel error -y"
$F -f lavfi -i testsrc2=size=1920x1080:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 20 \
   -c:v libx264 -preset veryfast -crf 16 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest "Holiday video.mp4"
$F -f lavfi -i testsrc=size=1280x720:rate=25 -f lavfi -i sine=frequency=660:sample_rate=44100 -t 12 \
   -c:v libx264 -preset veryfast -crf 14 -pix_fmt yuv420p -c:a aac -b:a 160k -shortest "lesson.mkv"
$F -f lavfi -i mandelbrot=size=640x480:rate=25 -f lavfi -i sine=frequency=300 -t 8 \
   -c:v mpeg4 -q:v 2 -c:a libmp3lame -b:a 192k -shortest "old clip.avi"
$F -f lavfi -i "sine=frequency=500:sample_rate=44100,aformat=channel_layouts=stereo" -t 40 "voice memo.wav"
$F -f lavfi -i "sine=frequency=250:sample_rate=44100,aformat=channel_layouts=stereo" -t 60 -c:a libmp3lame -b:a 256k "song.mp3"
python3 - <<'PY'
import io, random, zipfile
from PIL import Image, ImageDraw, ImageFilter
random.seed(1)
def photo(w, h):
    im = Image.effect_noise((w // 4, h // 4), 60).convert("RGB").resize((w, h), Image.BICUBIC)
    d = ImageDraw.Draw(im)
    for _ in range(120):
        x, y = random.randrange(w), random.randrange(h)
        r = random.randrange(20, 300)
        d.ellipse((x - r, y - r, x + r, y + r), fill=tuple(random.randrange(256) for _ in range(3)))
    return im.filter(ImageFilter.GaussianBlur(1.2))
p = photo(4000, 3000); p.save("camera photo.jpg", quality=95)
g = Image.new("RGBA", (1600, 1200), (0, 0, 0, 0)); d = ImageDraw.Draw(g)
for i in range(30):
    d.rectangle((i * 25, i * 18, 1600 - i * 20, 1200 - i * 15), outline=(30 + i * 3, 70, 240 - i * 2, 255), width=6)
g.save("diagram.png")
photo(2400, 1600).save("sticker.webp", quality=95)
pages = [photo(2480, 3508) for _ in range(3)]
pages[0].save("scanned document.pdf", save_all=True, append_images=pages[1:], resolution=300, quality=95)
buf = io.BytesIO(); photo(3000, 2000).save(buf, "PNG"); png = buf.getvalue()
ct = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      '<Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>'
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
drels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
         '<Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/></Relationships>')
doc = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
       'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
       'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">'
       '<w:body><w:p><w:r><w:t>Report with a picture</w:t></w:r></w:p><w:p><w:r><w:drawing><wp:inline><wp:extent cx="5486400" cy="3657600"/>'
       '<wp:docPr id="1" name="Picture 1"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>'
       '<pic:nvPicPr><pic:cNvPr id="0" name="image1.png"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rId5"/>'
       '<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="5486400" cy="3657600"/></a:xfrm>'
       '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p></w:body></w:document>')
with zipfile.ZipFile("report.docx", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", ct); z.writestr("_rels/.rels", rels)
    z.writestr("word/document.xml", doc); z.writestr("word/_rels/document.xml.rels", drels)
    z.writestr("word/media/image1.png", png)
PY
ls -l
