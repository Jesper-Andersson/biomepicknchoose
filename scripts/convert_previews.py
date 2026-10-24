"""Converts the shipped biome preview PNGs to JPEG, which the mod loads to keep the jar small.

Copy new captures from <run>/config/biomepicknchoose/biome_previews into the biome_preview folder, then run
`python scripts/convert_previews.py`. Each PNG is replaced by a .jpg. Needs Pillow (pip install Pillow).
"""
import pathlib

from PIL import Image

PREVIEWS = pathlib.Path(__file__).resolve().parent.parent / 'common/src/main/resources/assets/biomepicknchoose/textures/gui/biome_preview'
QUALITY = 85

converted = 0
for png in sorted(PREVIEWS.rglob('*.png')):
    with Image.open(png) as image:
        # The pictures are opaque, so the alpha channel some have carries nothing
        image.convert('RGB').save(png.with_suffix('.jpg'), 'JPEG', quality=QUALITY, optimize=True)
    png.unlink()
    converted += 1
print(f'Converted {converted} pictures')
