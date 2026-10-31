"""Mod icon: microphone with curved sound waves in a chat bubble, on a solid background.
Pixel art on a 32x32 grid, every pixel placed explicitly; saved at 512 (Modrinth) and 128 (in-game)
with nearest-neighbour scaling. icon-32.png is the 1:1 grid, meant for hand edits in a pixel editor."""
from PIL import Image

S = 32
BG = (46, 125, 82)       # solid green background
BUBBLE = (22, 24, 28)    # chat-bubble black
MIC = (240, 240, 240)
GRILLE = (160, 164, 176)
WAVE = (124, 216, 152)

img = Image.new("RGB", (S, S), BG)
px = img.load()


DY = 1  # nudges mic and waves down to sit centred in the bubble


def put(points, color, mirror=False, dy=0):
    for x, y in points:
        y += dy
        px[x, y] = color
        if mirror:
            px[S - 1 - x, y] = color


def rect(x0, y0, x1, y1, color, dy=0):
    put([(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)], color, dy=dy)


# chat bubble: almost the whole tile, corners rounded by 2 px, tail under the left third
rect(1, 1, 30, 25, BUBBLE)
for x, y in [(1, 1), (2, 1), (1, 2), (30, 1), (29, 1), (30, 2), (1, 25), (2, 25), (1, 24), (30, 25), (29, 25), (30, 24)]:
    px[x, y] = BG
rect(7, 26, 11, 26, BUBBLE)
rect(7, 27, 10, 27, BUBBLE)
rect(7, 28, 9, 28, BUBBLE)
rect(7, 29, 8, 29, BUBBLE)

# capsule: 8 px wide (x 12..19), y 3..14, rounded ends
rect(12, 4, 19, 13, MIC, dy=DY)
rect(13, 3, 18, 3, MIC, dy=DY)
rect(13, 14, 18, 14, MIC, dy=DY)
for y in (6, 8, 10):
    rect(13, y, 18, y, GRILLE, dy=DY)

# holder: a U around the lower half of the capsule, one pixel away from it
put([(10, 9), (10, 10), (10, 11), (10, 12), (11, 13), (11, 14), (12, 15), (13, 16)], MIC, mirror=True, dy=DY)
rect(14, 16, 17, 16, MIC, dy=DY)

# stand: straight, 2 px wide, and a flat base
rect(15, 17, 16, 19, MIC, dy=DY)
rect(11, 20, 20, 21, MIC, dy=DY)

# sound waves: two curved arcs on each side, 2 px thick, mirrored
inner = [(7, 4), (6, 5), (5, 6), (5, 7), (5, 8), (5, 9), (5, 10), (5, 11), (6, 12), (7, 13)]
outer = [(4, 2), (3, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8), (2, 9), (2, 10), (2, 11), (2, 12), (3, 13), (4, 14)]
for arc in (inner, outer):
    put(arc, WAVE, mirror=True, dy=DY)
    put([(x + 1, y) for x, y in arc], WAVE, mirror=True, dy=DY)

img.save("modrinth/icon-32.png")
img.resize((512, 512), Image.NEAREST).save("modrinth/icon.png")
img.resize((128, 128), Image.NEAREST).save("src/main/resources/assets/speech-to-chat/icon.png")
print("ok")
