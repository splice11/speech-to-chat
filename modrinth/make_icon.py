"""Draws the mod icon: pixel-art microphone in a chat bubble, 32x32 scaled to 512 (nearest)."""
from PIL import Image, ImageDraw

S = 32
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# background tile, Minecraft-ish deep green with a darker rim
d.rectangle([0, 0, 31, 31], fill=(37, 64, 52, 255))
d.rectangle([1, 1, 30, 30], fill=(52, 92, 72, 255))
for x in range(2, 30, 3):  # subtle texture
    for y in range((x // 3) % 2 + 2, 30, 4):
        img.putpixel((x, y), (58, 101, 79, 255))

# chat bubble (chat-line black with transparency look) and its tail
bubble = (24, 24, 28, 255)
d.rectangle([4, 5, 27, 21], fill=bubble)
d.rectangle([5, 4, 26, 22], fill=bubble)
d.polygon([(8, 22), (8, 26), (12, 22)], fill=bubble)

# microphone: capsule, grille lines, holder arc, stand
white, grey = (240, 240, 240, 255), (170, 170, 178, 255)
d.rectangle([14, 7, 17, 14], fill=white)
d.rectangle([15, 6, 16, 6], fill=white)
for y in (9, 11, 13):
    d.line([14, y, 17, y], fill=grey)
d.point([(12, 12), (12, 13), (19, 12), (19, 13), (13, 15), (18, 15)], fill=white)
d.line([14, 16, 17, 16], fill=white)
d.line([15, 17, 16, 18], fill=white)
d.line([13, 19, 18, 19], fill=white)

# sound waves
for x, y0, y1 in ((9, 9, 13), (7, 8, 14)):
    d.line([x, y0, x, y1], fill=(130, 200, 150, 255))
    d.line([31 - x, y0, 31 - x, y1], fill=(130, 200, 150, 255))

img.resize((512, 512), Image.NEAREST).save("modrinth/icon.png")
img.resize((128, 128), Image.NEAREST).save("src/main/resources/assets/speech-to-text-accessibility/icon.png")
print("ok")
