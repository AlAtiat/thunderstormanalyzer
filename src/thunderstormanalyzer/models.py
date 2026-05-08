from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class ColumnConfig:
    x_col: str = "position_x"
    y_col: str = "position_y"
    uncertainty_col: str = "uncertainty_x"
    intensity_col: str = "intensity"
    sigma_col: str = "psf_sigma"
    frame_col: str = "frame"
    bkgstd_pattern: str = "background_sigma"


@dataclass
class PlotConfig:
    histograms: bool = True
    superres_render: bool = True
    nnd_plots: bool = True
    intensity_vs_time: bool = True
    blinking_showcase: bool = True
    comparison_plots: bool = True


@dataclass
class QcParams:
    pixel_size_nm: float = 160.0
    camera_pixel_size_nm: float = 6500.0  # physical camera pixel size in nm
    magnification: float = 100.0          # objective lens magnification
    max_sigma_nm: float = 768.0
    max_uncertainty_nm: float = 40.0
    min_intensity: float = 1000.0
    nnd_target_nm: float = 80.0
    nnd_tolerance_nm: float = 20.0
    dna_origami_spacing_nm: float = 80.0
    spacing_tol_nm: float = 20.0
    n_spots: int = 3
    max_triplets: int = 10


@dataclass
class DatasetEntry:
    name: str
    csv_path: Path
    protocol_path: Path | None = None
    qc: QcParams = field(default_factory=QcParams)
    csv_format: str = "thunderstorm"     # "thunderstorm" | "generic"


@dataclass
class AnalysisResult:
    name: str
    n_raw: int
    n_filtered: int
    mean_uncertainty: float
    median_uncertainty: float
    std_uncertainty: float
    mean_sigma: float
    median_sigma: float
    mean_intensity: float
    median_intensity: float
    mean_bkgstd: float
    nnd_mean: float
    nnd_median: float
    n_3sigma: int = 0
    nnd_target_count: int = 0
    nnd_target_mean: float = float("nan")
    nnd_target_median: float = float("nan")
    blinking_top_score: float = 0.0
    blinking_spots_found: int = 0
    locs_per_frame: list[int] = field(default_factory=list)
    output_dir: Path | None = None
    viewer_html_path: Path | None = None
    error: str | None = None
