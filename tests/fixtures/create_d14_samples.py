"""Generate self-authored OCR inputs. Requires Pillow, reportlab and pypdf."""
from pathlib import Path
from io import BytesIO
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from pypdf import PdfReader
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--font', required=True, help='Path to a locally installed Chinese font')
args = parser.parse_args()
output = Path(__file__).resolve().parent
lines = [
    '学习资料整理测试样本',
    '主动回忆：合上课本，尝试复述知识要点。',
    '间隔复习：将复习安排在不同日期。',
    'Learning notes: retrieval practice and spaced review.',
    'Sample ID: D14-2026-001; 12 + 8 = 20.',
    '以上文字为软件测试自编内容，不作为论文资料来源。',
]
image = Image.new('RGB', (1500, 800), 'white')
draw = ImageDraw.Draw(image)
font = ImageFont.truetype(args.font, 42)
for index, line in enumerate(lines):
    draw.text((65, 65 + index * 105), line, font=font, fill='black')
image.save(output / 'd14-ocr-clear.png')
image.filter(ImageFilter.GaussianBlur(3)).save(output / 'd14-ocr-blurred.png')
pdf = output / 'd14-ocr-scan.pdf'
document = canvas.Canvas(str(pdf), pagesize=(842, 595), invariant=1)
document.setTitle('D14 self-authored OCR input - image-only page')
stream = BytesIO()
image.save(stream, format='PNG')
stream.seek(0)
document.drawImage(ImageReader(stream), 20, 75, width=802, height=428)
document.showPage()
document.save()
assert PdfReader(pdf).pages[0].extract_text().strip() == ''
(output / 'd14-ocr-expected.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('Created self-authored clear/blurred images, image-only PDF and transcription; no OCR performed.')
