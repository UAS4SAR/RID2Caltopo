"""Visual-only thermal derivatives; DJI radiometric originals remain untouched."""
from pathlib import Path

from PIL import Image

from .nodeodm import Cancelled


def visual_inputs(files, cancelled):
    """Avoid treating an unqualified M4TD R-JPEG as calibrated temperature data.

    Use the embedded 8-bit displayed image with its GPS and a 53 mm equivalent
    thermal-camera prior. Strip private thermal/XMP data only from engine copies.
    This does not convert raw thermal samples or produce temperature measurements.
    """
    directory = Path(files[0]).parent.parent / "thermal-visual-inputs"
    directory.mkdir(exist_ok=True)
    results = []
    for original in files:
        if cancelled.is_set():
            raise Cancelled()
        target = directory / Path(original).name
        with Image.open(original) as image:
            source = image.getexif()
            exif = Image.Exif()
            exif[271], exif[272] = "R2C", "M4TD IR visual"
            if 306 in source:
                exif[306] = source[306]
            if 0x8825 in source:
                exif[0x8825] = source.get_ifd(0x8825)
            exif[0x8769] = {41989:53}
            # A consistent rendered palette is required across a flight.
            image.convert("RGB").save(target, "JPEG", quality=98, exif=exif)
        results.append(target)
    return results
