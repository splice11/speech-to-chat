"""Scales the hand-edited 32x32 icon (modrinth/icon-32.png, GIMP source icon-32.xcf) to the
512 px Modrinth icon and the 128 px in-game icon, nearest-neighbour so the pixels stay crisp."""
from PIL import Image

im = Image.open("modrinth/icon-32.png").convert("RGBA")
im.resize((512, 512), Image.NEAREST).save("modrinth/icon.png")
im.resize((128, 128), Image.NEAREST).save("src/main/resources/assets/speech-to-chat/icon.png")
