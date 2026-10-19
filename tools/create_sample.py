"""Generate a deterministic PDF for manual QA. Requires reportlab + pillow."""
from pathlib import Path
from io import BytesIO
from PIL import Image, ImageDraw
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import ImageReader

OUT = Path(__file__).resolve().parent.parent / 'sample' / 'Engineering_Test.pdf'
w, h = A4
c = canvas.Canvas(str(OUT), pagesize=A4)
c.setTitle('PDF Architect - Engineering Test Document')
c.setAuthor('PDF Architect Prototype')
navy, blue, orange = [HexColor(color) for color in ('#243B54', '#39688B', '#D67C30')]


def head(title, sub, number):
    c.setFillColor(navy)
    c.rect(0, h-86, w, 86, fill=1, stroke=0)
    c.setFillColor(HexColor('#FFFFFF'))
    c.setFont('Helvetica-Bold', 18)
    c.drawString(48, h-38, title)
    c.setFont('Helvetica', 10)
    c.drawString(48, h-59, sub)
    c.setFillColor(HexColor('#566879'))
    c.setFont('Helvetica', 9)
    c.drawString(48, 30, 'PDF ARCHITECT | Test file - not a real construction drawing')
    c.drawRightString(w-48, 30, f'Page {number} of 3')


head('Construction drawing test', 'Use S Pen / calibrate / measure / export', 1)
c.setStrokeColor(blue)
c.setLineWidth(1.2)
c.rect(68, 230, 460, 420, stroke=1, fill=0)
c.rect(112, 283, 370, 280, stroke=1, fill=0)
c.setLineWidth(4)
for ax, ay, bx, by in [(112,283,112,563), (112,563,482,563),
                       (482,563,482,283), (482,283,112,283)]:
    c.line(ax, ay, bx, by)
c.setLineWidth(1)
c.line(297, 283, 297, 563)
c.line(112, 416, 482, 416)
c.setFillColor(navy)
c.setFont('Helvetica', 11)
c.drawString(96, 631, 'Calibration: known length 5.00 m between blue endpoints')
c.setStrokeColor(HexColor('#2387CC'))
c.setLineWidth(3)
c.line(128, 595, 428, 595)
c.circle(128, 595, 4, fill=1, stroke=0)
c.circle(428, 595, 4, fill=1, stroke=0)
c.setFillColor(blue)
c.setFont('Helvetica-Bold', 13)
c.drawCentredString(278, 605, '5.00 m')
c.setStrokeColor(orange)
c.setLineWidth(3)
c.line(135, 245, 285, 245)
c.setFillColor(orange)
c.setFont('Helvetica', 11)
c.drawString(310, 242, '2.50 m check line')
c.setFillColor(navy)
c.setFont('Helvetica', 11)
c.drawString(92, 190, 'Calibrate using the 300pt blue line and enter 5.0 m.')
c.drawString(92, 171, 'Then measure the 150pt orange line: expected 2.50 m.')
c.showPage()

head('Text and annotation test', 'Add stamps, highlights and color-coded notes', 2)
c.setFillColor(navy)
c.setFont('Helvetica-Bold', 15)
c.drawString(54, 665, 'Inspection notes')
items = [
    ('A-01', 'Inspect cast-in-place concrete surface.'),
    ('A-02', 'Confirm wall openings before pouring.'),
    ('A-03', 'Verify quantities against specification.'),
    ('A-04', 'Add a handwritten signature using the S Pen.'),
    ('A-05', 'Place a photo and export a flattened PDF copy.'),
]
for i, (label, desc) in enumerate(items):
    y = 619 - i * 65
    c.setStrokeColor(HexColor('#CFDAE2'))
    c.rect(52, y-31, w-104, 52, stroke=1, fill=0)
    c.setFont('Helvetica-Bold', 11)
    c.setFillColor(blue)
    c.drawString(65, y, label)
    c.setFont('Helvetica', 10)
    c.setFillColor(navy)
    c.drawString(112, y, desc)
c.setFillColor(HexColor('#6E8391'))
c.setFont('Helvetica', 11)
c.drawString(54, 221, 'Blank space for handwritten comments:')
c.setStrokeColor(HexColor('#CFDAE2'))
c.rect(52, 68, w-104, 144, stroke=1, fill=0)
c.showPage()

# Third page is a bitmap, not a vector drawing, to exercise raster PDF handling.
im = Image.new('RGB', (1200, 1580), 'white')
d = ImageDraw.Draw(im)
for yy in range(180, 1500, 80):
    d.line((115, yy, 1080, yy), fill=(220, 225, 229), width=2)
d.rectangle((128, 270, 1040, 1170), outline=(80, 95, 105), width=7)
for n in range(6):
    x, y = 155 + n*150, 310+n*150
    d.line((x,295,x,1150), fill=(155,165,180), width=3)
    d.line((148,y,1018,y), fill=(155,165,180), width=3)
stream = BytesIO()
im.save(stream, format='JPEG', quality=76)
head('Raster scan stress test', 'Large bitmap with no selectable PDF text', 3)
c.drawImage(ImageReader(BytesIO(stream.getvalue())), 50, 88,
            width=w-100, height=640)
c.showPage()
c.save()
print(OUT)
