"""Column-name mapping for ThunderSTORM CSV files — dependency-free.

Kept in its own tiny module (no numpy/pandas/locan) so the GUI can map raw CSV
header names to canonical pipeline names when reading just the header line of a
file, without pulling in the heavy scientific stack.
"""
from __future__ import annotations

# Maps raw ThunderSTORM CSV column names to locan's canonical names.
# Applied after loading so the rest of the pipeline always sees consistent names
# regardless of whether the file was opened in ThunderSTORM or Generic mode.
THUNDERSTORM_ALIASES: dict[str, str] = {
    "x [nm]": "position_x",
    "y [nm]": "position_y",
    "uncertainty_xy [nm]": "uncertainty_x",
    "sigma [nm]": "psf_sigma",
    "intensity [photon]": "intensity",
    "offset [photon]": "offset",
    "bkgstd [photon]": "background_sigma",
}


def canonical_headers(raw_headers: list[str]) -> list[str]:
    """Map raw ThunderSTORM header names to canonical names (passthrough if unknown)."""
    return [THUNDERSTORM_ALIASES.get(h.strip(), h.strip()) for h in raw_headers]
