"""Frames the staged emulator screenshots as 1920x1080 images in the app's own look."""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = 'C:/Users/Jan/Desktop/Welle'
SHOTS = os.path.dirname(os.path.abspath(__file__)) + '/shots'
FONTS = ROOT + '/app/src/main/assets/fonts/'
OUT = ROOT + '/docs/promo'
W, H = 1920, 1080
MARGIN = 96
AMBER = (255, 178, 36)

NIGHT = dict(bg=(10, 10, 11), ink=(243, 239, 230), sub=(168, 164, 156), dim=(110, 107, 101),
             line=(44, 44, 48), label=AMBER, glow=(255, 178, 36, 46), shadow=(0, 0, 0, 0))
DAY = dict(bg=(241, 238, 231), ink=(23, 21, 15), sub=(92, 88, 80), dim=(128, 123, 113),
           line=(205, 200, 189), label=(23, 21, 15), glow=(255, 178, 36, 40), shadow=(40, 30, 10, 70))

IMAGES = [
    ('welle-1-player', 'promo_night.png', NIGHT, 'DAB+ and web radio for your car',
     'A radio app made for Android head units, old ones included.'),
    ('welle-2-day', 'promo_day.png', DAY, 'Day and night',
     'Switch by hand, at sunset, or with the system theme.'),
    ('welle-3-stations', 'promo_stations.png', NIGHT, 'Every station one tap away',
     'Search, ensembles and 60 presets shared by DAB+ and web radio.'),
    ('welle-4-scan', 'promo_scan.png', NIGHT, 'Scan the whole band',
     'Pause, continue, and keep your favourites when the list is replaced.'),
    ('welle-5-webradio', 'promo_web_b.png', NIGHT, 'Web radio built in',
     'Station search, playlists and streams in MP3, AAC and HLS.'),
]
FOOTER = 'DAB+ VIA USB TUNER   \u00b7   WEB RADIO   \u00b7   ANDROID 4.1 AND UP   \u00b7   NO ACCOUNT, NO ADS'
TAG = 'DAB+ & WEB RADIO'
SEP = chr(0xB7)

# German captions: same screenshots, files get the suffix -de.
GERMAN = {
    'welle-1-player': ('DAB+ und Webradio fürs Auto',
                       'Eine Radio-App für Android-Autoradios, auch für alte Geräte.'),
    'welle-2-day': ('Tag und Nacht',
                    'Umschalten von Hand, bei Sonnenuntergang oder mit dem System-Design.'),
    'welle-3-stations': ('Jeder Sender einen Tipp entfernt',
                         'Suche, Ensembles und 60 Speicherplätze für DAB+ und Webradio gemeinsam.'),
    'welle-4-scan': ('Suchlauf über das ganze Band',
                     'Anhalten, fortsetzen und Favoriten behalten, wenn die Liste ersetzt wird.'),
    'welle-5-webradio': ('Webradio eingebaut',
                         'Sendersuche, Playlists und Streams in MP3, AAC und HLS.'),
}
FOOTER_DE = ('   ' + SEP + '   ').join(['DAB+ ÜBER USB-TUNER', 'WEBRADIO', 'AB ANDROID 4.1', 'OHNE KONTO, OHNE WERBUNG'])
TAG_DE = 'DAB+ & WEBRADIO'


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


def tracked(draw, x, y, text, f, fill, gap):
    """Letter-spaced text, as the app sets its small caps labels. Returns the end x."""
    for ch in text:
        draw.text((x, y), ch, font=f, fill=fill)
        x += draw.textlength(ch, font=f) + gap
    return x


def rounded_mask(size, radius, scale=4):
    big = Image.new('L', (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.size[0] - 1, big.size[1] - 1), radius * scale, fill=255)
    return big.resize(size, Image.LANCZOS)


def compose(name, shot, t, headline, sub, footer=FOOTER, tag=TAG):
    img = Image.new('RGBA', (W, H), t['bg'] + (255,))

    # A soft amber light behind the headline, the only decoration.
    light = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(light).ellipse((-420, -520, 980, 420), fill=t['glow'])
    img = Image.alpha_composite(img, light.filter(ImageFilter.GaussianBlur(190)))

    d = ImageDraw.Draw(img)
    mono = font('IBMPlexMono-Medium.ttf', 24)
    x = tracked(d, MARGIN, 78, 'WELLE', mono, t['label'], 5)
    tracked(d, x + 8, 78, SEP + ' ' + tag, mono, t['dim'], 3)
    d.text((MARGIN - 4, 116), headline, font=font('BarlowCondensed-SemiBold.ttf', 116), fill=t['ink'])
    d.text((MARGIN, 258), sub, font=font('Barlow-Medium.ttf', 36), fill=t['sub'])

    # The screenshot without the emulator's navigation bar (the head unit shows the app fullscreen).
    screen = Image.open(SHOTS + '/' + shot).convert('RGB').crop((0, 0, 1920, 672))
    sw = W - 2 * MARGIN
    sh = round(672 * sw / 1920)
    screen = screen.resize((sw, sh), Image.LANCZOS)
    sx, sy = MARGIN, 348
    mask = rounded_mask((sw, sh), 22)

    halo = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    if t['shadow'][3]:
        hd.rounded_rectangle((sx + 6, sy + 26, sx + sw - 6, sy + sh + 30), 30, fill=t['shadow'])
    else:
        hd.rounded_rectangle((sx + 40, sy + 60, sx + sw - 40, sy + sh + 16), 30, fill=(255, 178, 36, 34))
    img = Image.alpha_composite(img, halo.filter(ImageFilter.GaussianBlur(46)))

    img.paste(screen, (sx, sy), mask)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((sx - 1, sy - 1, sx + sw, sy + sh), 23, outline=t['line'], width=2)

    # Footer: one line of facts over the dial ruler from the player screen.
    tracked(d, MARGIN, 990, footer, font('IBMPlexMono-Regular.ttf', 21), t['dim'], 2)
    for i, tx in enumerate(range(MARGIN, W - MARGIN + 1, 24)):
        major = i % 6 == 0
        d.line((tx, 1040, tx, 1058 if major else 1048), fill=t['dim'] if major else t['line'], width=2)

    os.makedirs(OUT, exist_ok=True)
    path = '%s/%s.png' % (OUT, name)
    img.convert('RGB').save(path, optimize=True)
    print(path, os.path.getsize(path) // 1024, 'KB')


for name, shot, theme, headline, line in IMAGES:
    compose(name, shot, theme, headline, line)
    compose(name + '-de', shot, theme, GERMAN[name][0], GERMAN[name][1], FOOTER_DE, TAG_DE)
