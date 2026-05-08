"""SMLM analysis pipeline — GUI-friendly version of analyze_smlm.py.

All print() calls are replaced with log_fn(msg) so the GUI can stream output.
No folder-name parsing; datasets are identified by user-supplied names.
"""
from __future__ import annotations

import json
import re
import warnings
from pathlib import Path
from typing import Callable

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import locan as lc
from .models import AnalysisResult, ColumnConfig, DatasetEntry, PlotConfig, QcParams


LogFn = Callable[[str], None]

_FALLBACK_INITIAL_SIGMA_PX = 1.6
_COLLINEAR_ANGLE_DEG = 30.0

# Maps raw ThunderSTORM CSV column names to locan's canonical names.
# Applied after loading so the rest of the pipeline always sees consistent names
# regardless of whether the file was opened in ThunderSTORM or Generic mode.
_THUNDERSTORM_ALIASES: dict[str, str] = {
    "x [nm]": "position_x",
    "y [nm]": "position_y",
    "uncertainty_xy [nm]": "uncertainty_x",
    "sigma [nm]": "psf_sigma",
    "intensity [photon]": "intensity",
    "offset [photon]": "offset",
    "bkgstd [photon]": "background_sigma",
}

_SPOT_COLORS = ["#F44336", "#4CAF50", "#2196F3"]

_DATASET_COLORS = [
    "#2196F3", "#FF9800", "#F44336", "#4CAF50", "#9C27B0",
    "#00BCD4", "#FF5722", "#8BC34A", "#E91E63", "#607D8B",
]


# ---------------------------------------------------------------------------
# QC thresholds from protocol file
# ---------------------------------------------------------------------------

def _load_protocol(protocol_path: Path | None) -> dict:
    if protocol_path is None or not protocol_path.exists():
        return {}
    try:
        return json.loads(protocol_path.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return {}


def _parse_postprocessing_filter(options: str) -> dict[str, float]:
    """Extract numeric thresholds from ThunderSTORM postProcessing filter strings.

    e.g. 'formula=[uncertainty_xy < 30]' → {'uncertainty': 30.0}
         'formula=[intensity > 500]'      → {'intensity': 500.0}
    """
    result: dict[str, float] = {}
    for m in re.finditer(r'(\w+)\s*[<>]\s*([\d.]+)', options):
        key, val = m.group(1).lower(), float(m.group(2))
        if "uncertainty" in key:
            result["uncertainty"] = val
        elif "intensity" in key:
            result["intensity"] = val
    return result


def qc_from_protocol(protocol: dict) -> QcParams:
    """Derive QcParams defaults from a ThunderSTORM protocol dict."""
    defaults = QcParams()
    cam = protocol.get("cameraSettings", {})
    est = protocol.get("analysisEstimator", {})
    pixel_size_nm = float(cam.get("pixelSize", defaults.pixel_size_nm))
    initial_sigma_px = float(est.get("initialSigma", _FALLBACK_INITIAL_SIGMA_PX))
    max_sigma_nm = initial_sigma_px * pixel_size_nm * 3.0
    max_uncertainty_nm = pixel_size_nm / 4.0
    min_intensity = defaults.min_intensity

    for step in protocol.get("postProcessing", []):
        if step.get("name") == "Filter":
            parsed = _parse_postprocessing_filter(step.get("options", ""))
            if "uncertainty" in parsed:
                max_uncertainty_nm = parsed["uncertainty"]
            if "intensity" in parsed:
                min_intensity = parsed["intensity"]

    return QcParams(
        pixel_size_nm=pixel_size_nm,
        camera_pixel_size_nm=pixel_size_nm,  # protocol pixel IS effective pixel
        magnification=1.0,
        max_sigma_nm=max_sigma_nm,
        max_uncertainty_nm=max_uncertainty_nm,
        min_intensity=min_intensity,
        nnd_target_nm=defaults.nnd_target_nm,
        nnd_tolerance_nm=defaults.nnd_tolerance_nm,
        dna_origami_spacing_nm=defaults.dna_origami_spacing_nm,
        spacing_tol_nm=defaults.spacing_tol_nm,
    )


# ---------------------------------------------------------------------------
# Plotting helpers
# ---------------------------------------------------------------------------

def _plot_histogram(series: pd.Series, xlabel: str, ylabel: str,
                    path: Path, title: str,
                    annot_stats: pd.Series | None = None) -> None:
    finite = series.replace([float("inf"), float("-inf")], float("nan")).dropna()
    if len(finite) == 0:
        return
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(finite, bins=60, color="steelblue", edgecolor="white", linewidth=0.3)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    if annot_stats is not None:
        s = annot_stats.replace([float("inf"), float("-inf")], float("nan")).dropna()
        if len(s):
            mn = float(s.mean())
            md = float(s.median())
            sd = float(s.std())
            ax.text(0.97, 0.03,
                    f"n={len(s)}\nmean={mn:.2f}\nmedian={md:.2f}\nstd={sd:.2f}",
                    transform=ax.transAxes, ha="right", va="bottom", fontsize=7,
                    bbox=dict(boxstyle="round", facecolor="white", alpha=0.8))
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def _plot_locs_per_frame(counts: list[int], path: Path, title: str) -> None:
    fig, ax = plt.subplots(figsize=(8, 3))
    ax.plot(counts, linewidth=0.8, color="steelblue")
    ax.set_xlabel("Frame")
    ax.set_ylabel("Localizations")
    ax.set_title(title)
    if counts:
        peak = max(counts)
        ax.text(0.97, 0.97, f"peak={peak} locs/frame",
                transform=ax.transAxes, ha="right", va="top", fontsize=7,
                bbox=dict(boxstyle="round", facecolor="white", alpha=0.8))
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def _add_scalebar(ax: plt.Axes, scale_nm: float = 1000.0,
                  color: str = "white") -> None:
    """Draw an in-plot scale bar in the lower-left corner (axes-fraction coords)."""
    xlim = ax.get_xlim()
    ylim = ax.get_ylim()
    x_span = xlim[1] - xlim[0]
    y_span = ylim[1] - ylim[0]
    # Position: 5% from left edge, 6% from bottom
    x0 = xlim[0] + 0.05 * x_span
    x1 = x0 + scale_nm
    y = ylim[0] + 0.06 * y_span
    ax.plot([x0, x1], [y, y], color=color, linewidth=3.0, solid_capstyle="butt", zorder=10)
    # Tick marks at each end
    tick_h = y_span * 0.012
    for xv in (x0, x1):
        ax.plot([xv, xv], [y - tick_h, y + tick_h], color=color, linewidth=2.0, zorder=10)
    label = f"{scale_nm:.0f} nm" if scale_nm < 1000 else f"{scale_nm / 1000:.0f} µm"
    ax.text((x0 + x1) / 2, y + y_span * 0.025, label,
            color=color, fontsize=9, ha="center", va="bottom", zorder=10,
            fontweight="bold")


def _render_with_fallback(lc, locdata, path: Path, title: str, log_fn: LogFn,
                          pixel_size_nm: float = 100.0) -> None:
    """Render super-res image, retrying at coarser bins if OOM."""
    Trafo = lc.Trafo
    # Pick a round scale-bar length: aim for ~15% of the field width
    for bin_size in (20, 40, 80):
        try:
            fig, ax = plt.subplots(figsize=(10, 10), facecolor="black")
            ax.set_facecolor("black")
            lc.render_2d(locdata, bin_size=bin_size, rescale=Trafo.EQUALIZE,
                         cmap="cet_fire", cbar=False, ax=ax)
            ax.set_title(title, color="white", fontsize=10, pad=6)
            ax.set_xlabel("X position (nm)", color="white", fontsize=9)
            ax.set_ylabel("Y position (nm)", color="white", fontsize=9)
            ax.tick_params(colors="white")
            for spine in ax.spines.values():
                spine.set_edgecolor("white")
            # Scale bar: choose a round length ≈15% of the x-span
            x_span = ax.get_xlim()[1] - ax.get_xlim()[0]
            raw = x_span * 0.15
            magnitude = 10 ** np.floor(np.log10(raw))
            scale_nm = round(raw / magnitude) * magnitude
            _add_scalebar(ax, scale_nm=scale_nm)
            fig.savefig(path, dpi=150, bbox_inches="tight", facecolor="black")
            plt.close(fig)
            return
        except MemoryError:
            plt.close("all")
        except Exception as e:
            if "allocate" in str(e).lower():
                plt.close("all")
            else:
                plt.close("all")
                raise
    log_fn(f"  [WARN] render skipped (OOM at all bin sizes): {path.name}")


# ---------------------------------------------------------------------------
# NND fitting
# ---------------------------------------------------------------------------

def _fit_nnd_peak(distances: np.ndarray) -> dict:
    """KDE peak detection + Gaussian fit around the dominant NND peak."""
    from scipy.stats import gaussian_kde
    from scipy.optimize import curve_fit

    if len(distances) < 20:
        return {"peak_nm": float(np.median(distances)), "sigma_nm": float("nan"), "fit_ok": False}

    kde = gaussian_kde(distances, bw_method="silverman")
    x_eval = np.linspace(distances.min(), min(distances.max(), 600.0), 600)
    density = kde(x_eval)
    peak_idx = int(np.argmax(density))
    peak_x = float(x_eval[peak_idx])

    window = (distances >= peak_x * 0.4) & (distances <= peak_x * 2.2)
    x_fit = distances[window]
    if len(x_fit) < 10:
        return {"peak_nm": peak_x, "sigma_nm": float("nan"), "fit_ok": False,
                "kde_x": x_eval, "kde_y": density}

    hist_x = np.linspace(x_fit.min(), x_fit.max(), 100)
    hist_y = kde(hist_x)

    def _gauss(x: np.ndarray, amp: float, mu: float, sig: float) -> np.ndarray:
        return amp * np.exp(-0.5 * ((x - mu) / sig) ** 2)

    try:
        p0 = [float(hist_y.max()), peak_x, peak_x * 0.2]
        popt, _ = curve_fit(_gauss, hist_x, hist_y, p0=p0, maxfev=3000,
                            bounds=([0, 0, 1], [np.inf, 1000, 500]))
        return {"peak_nm": float(popt[1]), "sigma_nm": float(abs(popt[2])),
                "fit_ok": True, "amp": float(popt[0]),
                "kde_x": x_eval, "kde_y": density,
                "fit_x": hist_x, "fit_y": _gauss(hist_x, *popt)}
    except Exception:
        return {"peak_nm": peak_x, "sigma_nm": float("nan"), "fit_ok": False,
                "kde_x": x_eval, "kde_y": density}


# ---------------------------------------------------------------------------
# Blinking analysis helpers
# ---------------------------------------------------------------------------

def _count_on_off_cycles(frames: np.ndarray, gap_threshold: int = 2) -> int:
    if len(frames) == 0:
        return 0
    sorted_f = np.sort(frames)
    gaps = np.diff(sorted_f)
    return int(np.sum(gaps > gap_threshold)) + 1


def _score_cluster(spot_df: pd.DataFrame, frame_col: str = "frame",
                   intensity_col: str = "intensity") -> float:
    frames = spot_df[frame_col].values
    n_unique_frames = len(np.unique(frames))
    n_cycles = _count_on_off_cycles(frames)
    if n_cycles < 2 or n_unique_frames < 5:
        return 0.0
    per_frame_intensity = spot_df.groupby(frame_col)[intensity_col].mean()
    cv = per_frame_intensity.std() / (per_frame_intensity.mean() + 1e-9)
    consistency = 1.0 / (cv + 1e-6)
    return float(n_cycles * n_unique_frames * consistency)


def _are_collinear(cx: list[float], cy: list[float],
                   angle_tol_deg: float = _COLLINEAR_ANGLE_DEG) -> bool:
    v1 = np.array([cx[1] - cx[0], cy[1] - cy[0]])
    v2 = np.array([cx[2] - cx[0], cy[2] - cy[0]])
    norm1 = np.linalg.norm(v1)
    norm2 = np.linalg.norm(v2)
    if norm1 < 1e-6 or norm2 < 1e-6:
        return False
    cos_angle = np.clip(np.dot(v1, v2) / (norm1 * norm2), -1.0, 1.0)
    angle_deg = np.degrees(np.arccos(abs(cos_angle)))
    return angle_deg <= angle_tol_deg



def _order_triplet(spots: list[dict]) -> list[dict]:
    """Order three spots along their principal axis (left → right)."""
    pts = np.array([[s["cx"], s["cy"]] for s in spots])
    center = pts.mean(axis=0)
    pts_c = pts - center
    _, _, vt = np.linalg.svd(pts_c)
    axis = vt[0]
    projections = pts_c @ axis
    order = np.argsort(projections)
    return [spots[i] for i in order]



def _find_all_blinking_triplets(
    df_3sigma: pd.DataFrame,
    n_spots: int = 3,
    spacing_nm: float = 80.0,
    spacing_tol_nm: float = 10.0,
    pixel_size_nm: float = 80.0,
    uncertainty_col: str = "uncertainty_x",
    x_col: str = "position_x",
    y_col: str = "position_y",
    frame_col: str = "frame",
    intensity_col: str = "intensity",
    max_triplets: int = 10,
    log_fn: LogFn | None = None,
) -> tuple[list[list[dict]], str]:
    """Detect all valid collinear triplets of blinking clusters.

    Returns (all_triplets_sorted_by_score, diagnostic_message).
    Each triplet is a list of 3 spot dicts ordered along their principal axis.
    """
    from scipy.spatial import cKDTree

    def _log(msg: str) -> None:
        if log_fn:
            log_fn(msg)

    _log(f"  [INFO] Using configured spacing={spacing_nm:.1f} nm ± {spacing_tol_nm:.1f} nm")

    # --- DBSCAN spot clustering ---
    # eps = 2× user's pixel_size_nm — large enough to group repeat-localizations of one
    # emitter, capped at spacing/2.2 so adjacent spots never merge into one cluster.
    spot_eps = min(pixel_size_nm * 2.0, spacing_nm / 2.2)
    spot_eps = max(spot_eps, 10.0)
    _log(f"  [INFO] Origami eps={spot_eps:.1f} nm  "
         f"(2× pixel_size={pixel_size_nm:.1f} nm, cap=spacing/2.2={spacing_nm/2.2:.1f} nm)")

    # Use locan's cluster_dbscan — properly separates noise from clusters per locan guide.
    # Pass only x/y columns so locan clusters in 2D regardless of what other columns exist
    # (prevents locan from treating uncertainty_x or similar as a z-coordinate).
    xy_only = df_3sigma[[x_col, y_col]].copy()
    locs_for_cluster = lc.LocData.from_dataframe(dataframe=xy_only)
    noise_locs, cluster_collection = lc.cluster_dbscan(
        locs_for_cluster, eps=spot_eps, min_samples=3
    )
    refs = cluster_collection.references if cluster_collection.references else []
    n_dbscan_clusters = len(refs)
    n_noise = len(noise_locs) if noise_locs else 0
    _log(f"  [INFO] locan DBSCAN: {n_dbscan_clusters} clusters, {n_noise} noise pts "
         f"(eps={spot_eps:.1f} nm, min_samples=3)")

    # --- Candidate collection ---
    # ref.data has only x/y (we passed xy_only to locan), so look up full rows
    # from df_3sigma using the ref's locdata_id index.
    max_spread_nm = spot_eps * 1.5
    candidates: list[dict] = []
    n_rejected_diffuse = 0

    for ref in refs:
        # locdata_id is the original row index into df_3sigma (preserved by locan)
        idx = ref.data["locdata_id"].values if "locdata_id" in ref.data.columns else ref.data.index.values
        spot_df = df_3sigma.iloc[idx].reset_index(drop=True)

        cx_vals = spot_df[x_col].values
        cy_vals = spot_df[y_col].values
        if len(cx_vals) == 0:
            continue

        spread_x = float(np.std(cx_vals)) if len(cx_vals) > 1 else 0.0
        spread_y = float(np.std(cy_vals)) if len(cy_vals) > 1 else 0.0
        rms_spread = float(np.hypot(spread_x, spread_y))
        if rms_spread > max_spread_nm:
            n_rejected_diffuse += 1
            continue

        # Data-driven circle radius: 2× median uncertainty of this cluster's locs
        if uncertainty_col in spot_df.columns:
            unc_cl = spot_df[uncertainty_col].dropna().values
            cluster_unc = float(np.median(unc_cl)) if len(unc_cl) > 0 else rms_spread
        else:
            cluster_unc = rms_spread
        vis_radius = max(cluster_unc * 2.0, 15.0)

        sc = _score_cluster(spot_df, frame_col=frame_col, intensity_col=intensity_col)
        candidates.append({
            "label": int(id(ref)),
            "score": sc,
            "df": spot_df,
            "cx": float(np.mean(cx_vals)),
            "cy": float(np.mean(cy_vals)),
            "spread": rms_spread,
            "vis_radius": vis_radius,
            "x_min": float(cx_vals.min()),
            "x_max": float(cx_vals.max()),
            "y_min": float(cy_vals.min()),
            "y_max": float(cy_vals.max()),
        })

    n_scored = sum(1 for c in candidates if c["score"] > 0)
    _log(f"  [INFO] Candidates: {len(candidates)} compact (spread ≤ {max_spread_nm:.1f} nm); "
         f"{n_rejected_diffuse} diffuse rejected; "
         f"{n_scored} blinking-qualified (score > 0)")

    # Only clusters with genuine blinking enter the geometry search
    scored_candidates = [c for c in candidates if c["score"] > 0]
    n_unscored = len(candidates) - len(scored_candidates)
    _log(f"  [INFO] Geometry search: {len(scored_candidates)} blinking-qualified clusters; "
         f"{n_unscored} zero-scored excluded")

    # Diagnostic: NN distances among scored candidates only
    if len(scored_candidates) >= 2:
        _centers_nn = np.array([[c["cx"], c["cy"]] for c in scored_candidates])
        _tree_nn = cKDTree(_centers_nn)
        _dists_nn, _ = _tree_nn.query(_centers_nn, k=min(2, len(scored_candidates)))
        _nn_dists = _dists_nn[:, min(1, _dists_nn.shape[1] - 1)]
        _log(f"  [INFO] Scored-cluster NN distances: "
             f"min={_nn_dists.min():.1f} nm, "
             f"median={float(np.median(_nn_dists)):.1f} nm, "
             f"max={_nn_dists.max():.1f} nm  "
             f"(searching pairs at {spacing_nm:.0f}±{spacing_tol_nm:.0f} nm)")

    if len(scored_candidates) < n_spots:
        msg = (f"Origami detection failed: only {len(scored_candidates)} blinking-qualified "
               f"clusters (need ≥{n_spots}). "
               f"{n_unscored} clusters found but rejected for poor blinking quality.")
        _log(f"  [WARN] {msg}")
        return [], msg

    lo = spacing_nm - spacing_tol_nm
    hi = spacing_nm + spacing_tol_nm
    all_centers = np.array([[c["cx"], c["cy"]] for c in scored_candidates])
    tree = cKDTree(all_centers)

    # --- Find all valid pairs at the right spacing ---
    pairs_idx = tree.query_pairs(hi)
    valid_pairs = []
    for (i, j) in pairs_idx:
        dij = np.hypot(scored_candidates[i]["cx"] - scored_candidates[j]["cx"],
                       scored_candidates[i]["cy"] - scored_candidates[j]["cy"])
        if lo <= dij <= hi:
            valid_pairs.append((i, j, dij))

    _log(f"  [INFO] Valid pairs at {spacing_nm:.0f}±{spacing_tol_nm:.0f} nm: {len(valid_pairs)} found")

    if not valid_pairs:
        msg = (f"Origami detection failed: no blinking-qualified cluster pairs found at spacing "
               f"{spacing_nm:.0f}±{spacing_tol_nm:.0f} nm. "
               f"Try adjusting dna_origami_spacing_nm or spacing_tol_nm.")
        _log(f"  [WARN] {msg}")
        return [], msg

    # --- Find all valid collinear triplets ---
    seen_triplet_sets: set[frozenset[int]] = set()
    all_triplets: list[tuple[float, list[dict]]] = []
    n_collinear_checked = 0

    for (i, j, _dij) in valid_pairs:
        for anchor_idx, other_idx in [(i, j), (j, i)]:
            ax_, ay_ = scored_candidates[anchor_idx]["cx"], scored_candidates[anchor_idx]["cy"]
            ox, oy = scored_candidates[other_idx]["cx"], scored_candidates[other_idx]["cy"]
            dx, dy = ox - ax_, oy - ay_
            norm = np.hypot(dx, dy)
            if norm < 1e-6:
                continue
            ux, uy = dx / norm, dy / norm
            tx = ox + ux * _dij
            ty = oy + uy * _dij
            nearby = tree.query_ball_point([tx, ty], r=spacing_tol_nm * 2.5)
            for k in nearby:
                if k == i or k == j:
                    continue
                key = frozenset({anchor_idx, other_idx, k})
                if key in seen_triplet_sets:
                    continue
                djk = np.hypot(ox - scored_candidates[k]["cx"], oy - scored_candidates[k]["cy"])
                if not (_dij * 0.70 <= djk <= _dij * 1.30):
                    continue
                n_collinear_checked += 1
                cx_pts = [ax_, ox, scored_candidates[k]["cx"]]
                cy_pts = [ay_, oy, scored_candidates[k]["cy"]]
                if not _are_collinear(cx_pts, cy_pts):
                    continue
                seen_triplet_sets.add(key)
                triplet_raw = [scored_candidates[anchor_idx], scored_candidates[other_idx], scored_candidates[k]]
                triplet_score = sum(s["score"] for s in triplet_raw)
                all_triplets.append((triplet_score, triplet_raw))

    _log(f"  [INFO] Collinear candidates checked: {n_collinear_checked}  "
         f"→ triplets found: {len(all_triplets)}")

    if not all_triplets:
        msg = (f"Origami detection failed: {len(valid_pairs)} valid pairs found but no collinear "
               f"triplets passed geometry check (angle tol={_COLLINEAR_ANGLE_DEG}°). "
               f"Try increasing spacing_tol_nm or collinear angle tolerance.")
        _log(f"  [WARN] {msg}")
        return [], msg

    # Sort by score descending, cap at max_triplets
    all_triplets.sort(key=lambda t: t[0], reverse=True)
    all_triplets = all_triplets[:max_triplets]
    _log(f"  [INFO] Returning top {len(all_triplets)} triplets by score")

    # Order each triplet along its principal axis and convert to output format
    result: list[list[dict]] = []
    for _score, raw_triplet in all_triplets:
        ordered = _order_triplet(raw_triplet)
        result.append([
            {
                "label": s["label"],
                "score": s["score"],
                "df": s["df"],
                "x_center": s["cx"],
                "y_center": s["cy"],
                "spread": s.get("spread", 20.0),
                "vis_radius": s.get("vis_radius", 20.0),
                "x_min": s["x_min"],
                "x_max": s["x_max"],
                "y_min": s["y_min"],
                "y_max": s["y_max"],
            }
            for s in ordered
        ])

    msg = f"Found {len(result)} collinear origami triplet(s); top score={all_triplets[0][0]:.1f}"
    return result, msg


def _find_best_blinking_spots(df_3sigma: pd.DataFrame, n_spots: int = 3,
                               spacing_nm: float = 80.0,
                               spacing_tol_nm: float = 10.0,
                               pixel_size_nm: float = 80.0,
                               uncertainty_col: str = "uncertainty_x",
                               x_col: str = "position_x",
                               y_col: str = "position_y",
                               frame_col: str = "frame",
                               intensity_col: str = "intensity") -> list[dict]:
    """Legacy wrapper — returns only the top-scoring triplet (flat list of 3 spots)."""
    triplets, _ = _find_all_blinking_triplets(
        df_3sigma, n_spots=n_spots, spacing_nm=spacing_nm,
        spacing_tol_nm=spacing_tol_nm, pixel_size_nm=pixel_size_nm,
        uncertainty_col=uncertainty_col,
        x_col=x_col, y_col=y_col, frame_col=frame_col, intensity_col=intensity_col,
    )
    return triplets[0] if triplets else []


# ---------------------------------------------------------------------------
# Per-triplet viewer PNGs
# ---------------------------------------------------------------------------

def _write_interactive_viewer(
    all_triplets: list[list[dict]],
    df_3sigma: pd.DataFrame,
    out_dir: Path,
    dataset_name: str,
    frame_col: str = "frame",
    intensity_col: str = "intensity",
    log_fn: LogFn | None = None,
) -> Path | None:
    """Render one PNG per triplet, then bundle them into a self-contained HTML carousel.

    The HTML file (blinking_interactive.html) embeds each triplet PNG as base64 so it
    works offline and can be loaded by toga.WebView via a file:// URL.
    Returns the path to the HTML file, or None if no triplets exist.
    """
    import base64
    import io
    import matplotlib.patches as mpatches
    import matplotlib.gridspec as gridspec

    def _log(msg: str) -> None:
        if log_fn:
            log_fn(msg)

    if not all_triplets:
        return None

    frame_min = int(df_3sigma[frame_col].min())
    frame_max = int(df_3sigma[frame_col].max())
    all_frames = np.arange(frame_min, frame_max + 1)

    locs_3sigma_for_render = lc.LocData.from_dataframe(dataframe=df_3sigma)
    b64_images: list[str] = []

    for t_idx, triplet in enumerate(all_triplets):
        # --- Build blinking traces ---
        traces = []
        for sp in triplet:
            grp = sp["df"].groupby(frame_col)[intensity_col].mean()
            trace = pd.Series(0.0, index=all_frames, dtype=float)
            trace[grp.index] = grp.values
            traces.append(trace)

        try:
            fig = plt.figure(figsize=(16, 12), facecolor="black")
            gs = gridspec.GridSpec(
                2, 2, figure=fig,
                height_ratios=[3, 1], width_ratios=[3, 2],
                hspace=0.08, wspace=0.05,
                left=0.04, right=0.97, top=0.96, bottom=0.05,
            )
            ax_main  = fig.add_subplot(gs[0, 0])
            ax_zoom  = fig.add_subplot(gs[0, 1])
            ax_combo = fig.add_subplot(gs[1, :])

            # Full super-res image (left panel) — no circles
            ax_main.set_facecolor("black")
            lc.render_2d(locs_3sigma_for_render, bin_size=20,
                         rescale=lc.Trafo.EQUALIZE,
                         cmap="cet_fire", cbar=False, ax=ax_main)
            ax_main.set_title(f"{dataset_name} — origami triplet {t_idx + 1}",
                              color="white", fontsize=11, pad=6)
            ax_main.tick_params(colors="white")
            for spine in ax_main.spines.values():
                spine.set_edgecolor("white")

            # Bounding box around triplet spots
            pad_nm = 200.0
            bx0 = min(sp["x_min"] for sp in triplet) - pad_nm
            bx1 = max(sp["x_max"] for sp in triplet) + pad_nm
            by0 = min(sp["y_min"] for sp in triplet) - pad_nm
            by1 = max(sp["y_max"] for sp in triplet) + pad_nm

            rect = mpatches.Rectangle(
                (bx0, by0), bx1 - bx0, by1 - by0,
                linewidth=1.2, edgecolor="white", facecolor="none", zorder=5,
            )
            ax_main.add_patch(rect)

            # Dashed connectors from box corners to zoom panel
            for box_corner, zoom_corner in [
                ((bx1, by1), (bx0, by1)),
                ((bx1, by0), (bx0, by0)),
            ]:
                con = mpatches.ConnectionPatch(
                    xyA=box_corner, coordsA=ax_main.transData,
                    xyB=zoom_corner, coordsB=ax_zoom.transData,
                    color="white", linewidth=1.2, linestyle="--", alpha=0.7,
                    zorder=10,
                )
                fig.add_artist(con)

            # Zoom panel with coloured circles on spots
            ax_zoom.set_facecolor("black")
            lc.render_2d(locs_3sigma_for_render, bin_size=20,
                         rescale=lc.Trafo.EQUALIZE,
                         cmap="cet_fire", cbar=False, ax=ax_zoom)
            ax_zoom.set_xlim(bx0, bx1)
            ax_zoom.set_ylim(by0, by1)

            for sp, color in zip(triplet, _SPOT_COLORS):
                r_nm = max(sp.get("vis_radius", 30.0), 15.0)
                cx = sp.get("x_center", sp.get("cx", 0.0))
                cy = sp.get("y_center", sp.get("cy", 0.0))
                circle = mpatches.Circle(
                    (cx, cy), radius=r_nm,
                    facecolor="none", edgecolor=color, alpha=0.9,
                    linewidth=2.0, zorder=6,
                )
                ax_zoom.add_patch(circle)

            # 100 nm scale bar
            bar_len_nm = 100.0
            bar_x0 = bx0 + pad_nm * 0.15
            bar_y   = by0 + pad_nm * 0.25
            ax_zoom.plot([bar_x0, bar_x0 + bar_len_nm], [bar_y, bar_y],
                         color="white", linewidth=2.5, zorder=8,
                         solid_capstyle="butt")
            ax_zoom.text(bar_x0 + bar_len_nm / 2, bar_y + pad_nm * 0.12,
                         "100 nm", color="white", fontsize=8,
                         ha="center", zorder=8)
            ax_zoom.set_title("Magnified triplet", color="white", fontsize=10, pad=4)
            ax_zoom.tick_params(colors="white", labelsize=7)
            for spine in ax_zoom.spines.values():
                spine.set_edgecolor("white")

            # Blinking traces
            n_spots = len(triplet)
            ax_combo.set_facecolor("#0a0a0a")
            for i, (sp, color, trace) in enumerate(
                zip(triplet, _SPOT_COLORS[:n_spots], traces)
            ):
                peak   = trace.max() or 1.0
                norm   = trace / peak
                offset = (n_spots - 1 - i) * 0.3
                ax_combo.fill_between(trace.index, norm + offset, offset,
                                      color=color, alpha=0.35, linewidth=0)
                n_cyc = _count_on_off_cycles(sp["df"][frame_col].values)
                ax_combo.plot(trace.index, norm + offset, color=color,
                              linewidth=0.8,
                              label=f"Dot #{i + 1}  ({n_cyc} cycles)")
            ax_combo.set_xlabel("Frame", color="white", fontsize=10)
            ax_combo.set_ylabel("Norm. intensity + offset", color="white", fontsize=10)
            ax_combo.set_title(f"Blinking traces — triplet {t_idx + 1}",
                               color="white", fontsize=10)
            ax_combo.tick_params(colors="white")
            for spine in ax_combo.spines.values():
                spine.set_edgecolor("#444444")
            ax_combo.legend(fontsize=8, facecolor="#222222",
                            edgecolor="#555555", labelcolor="white")

            buf = io.BytesIO()
            fig.savefig(buf, format="png", dpi=120, bbox_inches="tight",
                        facecolor="black")
            plt.close(fig)
            b64_images.append(base64.b64encode(buf.getvalue()).decode("ascii"))
        except Exception as exc:
            _log(f"  [WARN] Triplet {t_idx + 1} render failed: {exc}")
            plt.close("all")

    if not b64_images:
        return None

    # Build self-contained HTML carousel — no CDN, works offline
    imgs_js = ",\n".join(f'"{b}"' for b in b64_images)
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<style>
  body {{
    margin: 0; padding: 0;
    background: #111; color: #eee;
    font-family: monospace; font-size: 13px;
    display: flex; flex-direction: column; align-items: center;
    height: 100vh; box-sizing: border-box;
  }}
  #nav {{
    display: flex; align-items: center; gap: 16px;
    padding: 8px 12px; background: #1e1e1e;
    width: 100%; box-sizing: border-box;
  }}
  button {{
    background: #333; color: #eee; border: 1px solid #555;
    padding: 4px 14px; cursor: pointer; border-radius: 4px; font-size: 13px;
  }}
  button:disabled {{ opacity: 0.35; cursor: default; }}
  #counter {{ flex: 1; text-align: center; }}
  #triplet-img {{
    max-width: 100%; max-height: calc(100vh - 50px);
    object-fit: contain; display: block;
  }}
</style>
</head>
<body>
<div id="nav">
  <button id="prev" onclick="show(current-1)">&#8592; Prev</button>
  <span id="counter"></span>
  <button id="next" onclick="show(current+1)">Next &#8594;</button>
</div>
<img id="triplet-img" src="" alt="triplet">
<script>
  const imgs = [
{imgs_js}
  ];
  let current = 0;
  function show(i) {{
    if (i < 0 || i >= imgs.length) return;
    current = i;
    document.getElementById('triplet-img').src = 'data:image/png;base64,' + imgs[i];
    document.getElementById('counter').textContent = 'Triplet ' + (i + 1) + ' / ' + imgs.length;
    document.getElementById('prev').disabled = i === 0;
    document.getElementById('next').disabled = i === imgs.length - 1;
  }}
  show(0);
</script>
</body>
</html>"""

    html_path = out_dir / "blinking_interactive.html"
    html_path.write_text(html, encoding="utf-8")
    _log(f"  [INFO] {len(b64_images)} triplet(s) written to blinking_interactive.html")
    return html_path


def _plot_blinking_showcase(lc, locs_3sigma: object, spots: list[dict],
                             df_3sigma: pd.DataFrame,
                             out_dir: Path, dataset_name: str,
                             frame_col: str = "frame",
                             intensity_col: str = "intensity") -> None:
    import matplotlib.patches as mpatches
    import matplotlib.gridspec as gridspec

    n_spots = len(spots)

    frame_min = int(df_3sigma[frame_col].min())
    frame_max = int(df_3sigma[frame_col].max())
    all_frames = np.arange(frame_min, frame_max + 1)

    traces = []
    for sp in spots:
        grp = sp["df"].groupby(frame_col)[intensity_col].mean()
        trace = pd.Series(0.0, index=all_frames, dtype=float)
        trace[grp.index] = grp.values
        traces.append(trace)

    fig = plt.figure(figsize=(16, 12), facecolor="black")
    gs = gridspec.GridSpec(
        2, 2, figure=fig,
        height_ratios=[3, 1], width_ratios=[3, 2],
        hspace=0.08, wspace=0.05,
        left=0.04, right=0.97, top=0.96, bottom=0.05,
    )
    ax_main = fig.add_subplot(gs[0, 0])
    ax_zoom = fig.add_subplot(gs[0, 1])
    ax_combo = fig.add_subplot(gs[1, :])

    Trafo = lc.Trafo
    ax_main.set_facecolor("black")
    lc.render_2d(locs_3sigma, bin_size=20, rescale=Trafo.EQUALIZE,
                 cmap="cet_fire", cbar=False, ax=ax_main)
    ax_main.set_title(f"{dataset_name} — DNA origami triplet",
                      color="white", fontsize=11, pad=6)
    ax_main.tick_params(colors="white")
    for spine in ax_main.spines.values():
        spine.set_edgecolor("white")

    pad_nm = 200.0
    bx0 = min(sp["x_min"] for sp in spots) - pad_nm
    bx1 = max(sp["x_max"] for sp in spots) + pad_nm
    by0 = min(sp["y_min"] for sp in spots) - pad_nm
    by1 = max(sp["y_max"] for sp in spots) + pad_nm

    rect = mpatches.Rectangle(
        (bx0, by0), bx1 - bx0, by1 - by0,
        linewidth=1, edgecolor="white", facecolor="none", zorder=5,
    )
    ax_main.add_patch(rect)

    for box_corner, zoom_corner in [
        ((bx1, by1), (bx0, by1)),
        ((bx1, by0), (bx0, by0)),
    ]:
        con = mpatches.ConnectionPatch(
            xyA=box_corner, coordsA=ax_main.transData,
            xyB=zoom_corner, coordsB=ax_zoom.transData,
            color="white", linewidth=1.2, linestyle="--", alpha=0.7, zorder=10,
        )
        fig.add_artist(con)

    ax_zoom.set_facecolor("black")
    lc.render_2d(locs_3sigma, bin_size=20, rescale=Trafo.EQUALIZE,
                 cmap="cet_fire", cbar=False, ax=ax_zoom)
    ax_zoom.set_xlim(bx0, bx1)
    ax_zoom.set_ylim(by0, by1)

    for sp, color in zip(spots, _SPOT_COLORS[:n_spots]):
        r_nm = max(sp.get("vis_radius", 30.0), 15.0)
        circle = mpatches.Circle(
            (sp["x_center"], sp["y_center"]), radius=r_nm,
            facecolor="none", edgecolor=color, alpha=0.9,
            linewidth=2.0, zorder=6,
        )
        ax_zoom.add_patch(circle)

    bar_len_nm = 100.0
    bar_x0 = bx0 + pad_nm * 0.15
    bar_y = by0 + pad_nm * 0.25
    ax_zoom.plot([bar_x0, bar_x0 + bar_len_nm], [bar_y, bar_y],
                 color="white", linewidth=2.5, zorder=8, solid_capstyle="butt")
    ax_zoom.text(bar_x0 + bar_len_nm / 2, bar_y + pad_nm * 0.12,
                 "100 nm", color="white", fontsize=8, ha="center", zorder=8)

    ax_zoom.set_title("Magnified triplet", color="white", fontsize=10, pad=4)
    ax_zoom.tick_params(colors="white", labelsize=7)
    for spine in ax_zoom.spines.values():
        spine.set_edgecolor("white")

    ax_combo.set_facecolor("#0a0a0a")
    for i, (sp, color, trace) in enumerate(zip(spots, _SPOT_COLORS[:n_spots], traces)):
        peak = trace.max() or 1.0
        norm_trace = trace / peak
        offset = (n_spots - 1 - i) * 0.3
        ax_combo.fill_between(trace.index, norm_trace + offset, offset,
                               color=color, alpha=0.35, linewidth=0)
        ax_combo.plot(trace.index, norm_trace + offset, color=color, linewidth=0.8,
                      label=(f"Dot #{i+1}  "
                             f"({_count_on_off_cycles(sp['df']['frame'].values)} cycles)"))
    ax_combo.set_xlabel("Frame", color="white", fontsize=10)
    ax_combo.set_ylabel("Norm. intensity + vertical offset", color="white", fontsize=10)
    ax_combo.set_title("Blinking traces — all 3 dots", color="white", fontsize=10)
    ax_combo.tick_params(colors="white")
    for spine in ax_combo.spines.values():
        spine.set_edgecolor("#444444")
    ax_combo.legend(fontsize=8, facecolor="#222222",
                    edgecolor="#555555", labelcolor="white")

    fig.savefig(out_dir / "blinking_showcase.png", dpi=200,
                bbox_inches="tight", facecolor="black")
    plt.close(fig)


# ---------------------------------------------------------------------------
# Per-dataset analysis
# ---------------------------------------------------------------------------

def analyze_entry(entry: DatasetEntry, output_dir: Path,
                  log_fn: LogFn,
                  col_config: ColumnConfig | None = None,
                  plot_config: PlotConfig | None = None) -> AnalysisResult:
    """Run the full per-dataset analysis. Calls log_fn() for progress messages."""
    if col_config is None:
        col_config = ColumnConfig()
    if plot_config is None:
        plot_config = PlotConfig()

    qc = entry.qc
    c = col_config

    safe_name = "".join(c if c.isalnum() or c in "-_ " else "_" for c in entry.name).strip()
    out_dir = output_dir / safe_name / "results"
    out_dir.mkdir(parents=True, exist_ok=True)

    log_fn(f"  [{entry.name}] QC: pixel={qc.pixel_size_nm:.0f} nm  "
           f"max_sigma={qc.max_sigma_nm:.0f} nm  "
           f"max_unc={qc.max_uncertainty_nm:.0f} nm  "
           f"min_intensity={qc.min_intensity:.0f} ph  "
           f"nnd_target={qc.nnd_target_nm:.0f}±{qc.nnd_tolerance_nm:.0f} nm")

    if entry.csv_format == "generic":
        log_fn(f"  [{entry.name}] Generic CSV mode — units assumed to match column picker selections")
        raw_df = pd.read_csv(str(entry.csv_path))
    else:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            locs_obj = lc.load_thunderstorm_file(str(entry.csv_path))
        raw_df = locs_obj.data
    # Normalize any raw ThunderSTORM header names to locan canonical names.
    # This is a no-op when locan already renamed them; it only fires in generic mode.
    raw_df = raw_df.rename(columns={
        k: v for k, v in _THUNDERSTORM_ALIASES.items() if k in raw_df.columns
    })
    n_raw = len(raw_df)

    mask = (
        (raw_df[c.intensity_col] > qc.min_intensity) &
        (raw_df[c.uncertainty_col] < qc.max_uncertainty_nm) &
        (raw_df[c.sigma_col] < qc.max_sigma_nm)
    )
    filtered_df = raw_df[mask].reset_index(drop=True)
    locs = lc.LocData.from_dataframe(dataframe=filtered_df)
    n_filtered = len(locs)
    del raw_df

    if n_filtered == 0:
        log_fn(f"  [WARN] {entry.name}: all localisations removed by QC filter")
        return AnalysisResult(
            name=entry.name, n_raw=n_raw, n_filtered=0,
            mean_uncertainty=float("nan"), median_uncertainty=float("nan"),
            std_uncertainty=float("nan"), mean_sigma=float("nan"),
            median_sigma=float("nan"), mean_intensity=float("nan"),
            median_intensity=float("nan"), mean_bkgstd=float("nan"),
            nnd_mean=float("nan"), nnd_median=float("nan"),
            output_dir=out_dir,
            error="All localizations removed by QC filter",
        )

    df = locs.data

    unc = df[c.uncertainty_col].dropna()
    sigma = df[c.sigma_col].dropna()
    intensity = df[c.intensity_col].dropna()
    bkgstd_cols = [col for col in df.columns if c.bkgstd_pattern in col.lower()]
    bkgstd = df[bkgstd_cols[0]].dropna() if bkgstd_cols else pd.Series(dtype=float)

    protocol = _load_protocol(entry.protocol_path)
    cam = protocol.get("cameraSettings", {})
    est = protocol.get("analysisEstimator", {})
    det = protocol.get("analysisDetector", {})
    filt = protocol.get("analysisFilter", {})

    stats: dict = {
        "name": entry.name,
        "protocol": {
            "thunderstorm_version": protocol.get("version", "unknown"),
            "source_image": protocol.get("imageInfo", {}).get("title", ""),
            "camera": {
                "pixel_size_nm": cam.get("pixelSize"),
                "offset_ADU": cam.get("offset"),
                "gain": cam.get("gain"),
                "photons_per_ADU": cam.get("photons2ADU"),
                "quantum_efficiency": cam.get("quantumEfficiency"),
                "em_gain": cam.get("isEmGain"),
            },
            "filter": {"name": filt.get("name"), "scale": filt.get("scale"),
                       "order": filt.get("order")},
            "detector": {"name": det.get("name"), "threshold": det.get("threshold"),
                         "watershed": det.get("useWatershed")},
            "estimator": {"name": est.get("name"),
                          "fitting_radius_px": est.get("fittingRadius"),
                          "method": est.get("method"),
                          "initial_sigma_px": est.get("initialSigma")},
        },
        "acquisition": {
            "camera_pixel_size_nm": qc.camera_pixel_size_nm,
            "magnification": qc.magnification,
            "effective_pixel_size_nm": qc.pixel_size_nm,
            "csv_format": entry.csv_format,
        },
        "qc_thresholds_applied": {
            "min_intensity_photon": qc.min_intensity,
            "max_uncertainty_nm": qc.max_uncertainty_nm,
            "max_sigma_nm": qc.max_sigma_nm,
            "nnd_target_nm": qc.nnd_target_nm,
            "nnd_tolerance_nm": qc.nnd_tolerance_nm,
        },
        "n_raw": n_raw,
        "n_filtered": n_filtered,
        "uncertainty_mean": float(unc.mean()) if len(unc) else float("nan"),
        "uncertainty_median": float(unc.median()) if len(unc) else float("nan"),
        "uncertainty_std": float(unc.std()) if len(unc) else float("nan"),
        "sigma_mean": float(sigma.mean()) if len(sigma) else float("nan"),
        "sigma_median": float(sigma.median()) if len(sigma) else float("nan"),
        "intensity_mean": float(intensity.mean()) if len(intensity) else float("nan"),
        "intensity_median": float(intensity.median()) if len(intensity) else float("nan"),
        "bkgstd_mean": float(bkgstd.mean()) if len(bkgstd) else float("nan"),
        "n_3sigma_photon_filtered": 0,
        "intensity_3sigma_lo": float("nan"),
        "intensity_3sigma_hi": float("nan"),
        "nnd_target_count": 0,
        "nnd_target_mean": float("nan"),
        "nnd_target_median": float("nan"),
    }

    if plot_config.histograms:
        _plot_histogram(unc, "Localization uncertainty (nm)", "Count",
                        out_dir / "uncertainty_hist.png", entry.name,
                        annot_stats=unc)
        _plot_histogram(intensity, "Intensity (photon)", "Count",
                        out_dir / "intensity_hist.png", entry.name,
                        annot_stats=intensity)
        _plot_histogram(sigma, "PSF sigma (nm)", "Count",
                        out_dir / "sigma_hist.png", entry.name,
                        annot_stats=sigma)

    locs_per_frame: list[int] = []
    if c.frame_col in df.columns:
        locs_per_frame = df.groupby(c.frame_col).size().tolist()
        if plot_config.intensity_vs_time:
            _plot_locs_per_frame(locs_per_frame, out_dir / "locs_per_frame.png", entry.name)

    intensity_mean = float(intensity.mean())
    intensity_std = float(intensity.std())
    lo3 = intensity_mean - 3.0 * intensity_std
    hi3 = intensity_mean + 3.0 * intensity_std
    mask_3sigma = (df[c.intensity_col] >= lo3) & (df[c.intensity_col] <= hi3)
    df_3sigma = df[mask_3sigma].reset_index(drop=True)
    locs_3sigma = lc.LocData.from_dataframe(dataframe=df_3sigma)
    n_3sigma = len(df_3sigma)

    stats["n_3sigma_photon_filtered"] = n_3sigma
    stats["intensity_3sigma_lo"] = lo3
    stats["intensity_3sigma_hi"] = hi3

    pd.DataFrame({"uncertainty_nm": unc.values}).to_csv(out_dir / "uncertainty_values.csv", index=False)
    pd.DataFrame({"intensity_ph": intensity.values}).to_csv(out_dir / "intensity_values.csv", index=False)
    pd.DataFrame({"sigma_nm": sigma.values}).to_csv(out_dir / "sigma_values.csv", index=False)

    if plot_config.histograms:
        _plot_histogram(df_3sigma[c.intensity_col], "Intensity (photon)", "Count",
                        out_dir / "intensity_hist_3sigma.png", f"{entry.name} — 3σ subset",
                        annot_stats=df_3sigma[c.intensity_col])
        _plot_histogram(df_3sigma[c.uncertainty_col], "Localization uncertainty (nm)", "Count",
                        out_dir / "uncertainty_hist_3sigma.png", f"{entry.name} — 3σ subset",
                        annot_stats=df_3sigma[c.uncertainty_col])
        _plot_histogram(df_3sigma[c.sigma_col], "PSF sigma (nm)", "Count",
                        out_dir / "sigma_hist_3sigma.png", f"{entry.name} — 3σ subset",
                        annot_stats=df_3sigma[c.sigma_col])

    if plot_config.superres_render:
        _render_with_fallback(lc, locs, out_dir / "superres_render.png", entry.name, log_fn)
        _render_with_fallback(lc, locs_3sigma, out_dir / "superres_render_clean.png",
                              f"{entry.name} — 3σ clean", log_fn)

    nnd_mean = float("nan")
    nnd_median = float("nan")
    nnd_target_count = 0
    nnd_target_mean = float("nan")
    nnd_target_median = float("nan")
    if plot_config.nnd_plots:
        try:
            nn = lc.NearestNeighborDistances()
            nn.compute(locs)
            distances = nn.results["nn_distance"].dropna()
            nnd_mean = float(distances.mean())
            nnd_median = float(distances.median())
            stats["nnd_mean"] = nnd_mean
            stats["nnd_median"] = nnd_median

            nnd_fit = _fit_nnd_peak(distances.values)
            peak_nm = nnd_fit["peak_nm"]
            sigma_nm = nnd_fit["sigma_nm"]
            if nnd_fit.get("fit_ok") and abs(peak_nm - qc.nnd_target_nm) / max(qc.nnd_target_nm, 1) > 0.20:
                log_fn(f"  [INFO] Auto-detected NND peak at {peak_nm:.1f} nm; "
                       f"consider updating nnd_target_nm (currently {qc.nnd_target_nm:.1f} nm)")

            fig, ax = plt.subplots(figsize=(8, 5))
            ax.hist(distances, bins=60, color="#26A69A", edgecolor="white",
                    linewidth=0.3, density=True, alpha=0.75, label="NND histogram")
            if "kde_x" in nnd_fit:
                ax.plot(nnd_fit["kde_x"], nnd_fit["kde_y"],
                        color="#80CBC4", linewidth=1.2, linestyle="-", label="KDE")
            if nnd_fit.get("fit_ok") and "fit_x" in nnd_fit:
                fit_lbl = (f"Gaussian fit\npeak={peak_nm:.1f} nm"
                           + (f" ± {sigma_nm:.1f}" if not np.isnan(sigma_nm) else ""))
                ax.plot(nnd_fit["fit_x"], nnd_fit["fit_y"],
                        color="#FF7043", linewidth=2.0, linestyle="-", label=fit_lbl)
            ax.axvline(nnd_median, color="#EF5350", linewidth=1.5, linestyle="--",
                       label=f"Median  {nnd_median:.1f} nm")
            ax.axvline(nnd_mean,   color="#FFA726", linewidth=1.5, linestyle=":",
                       label=f"Mean  {nnd_mean:.1f} nm")
            ax.set_xlabel("Nearest-neighbour distance (nm)")
            ax.set_ylabel("Density")
            ax.set_title(f"NND — {entry.name}  (n={len(distances):,})", wrap=True)
            ax.legend(fontsize=7, loc="upper right", framealpha=0.85)
            fig.tight_layout()
            fig.savefig(out_dir / "nnd_distribution.png", dpi=150)
            plt.close(fig)

            pd.DataFrame({"distance_nm": distances.values}).to_csv(
                out_dir / "nnd_distances.csv", index=False)

            lo_nm = qc.nnd_target_nm - qc.nnd_tolerance_nm
            hi_nm = qc.nnd_target_nm + qc.nnd_tolerance_nm
            mask_target = (distances >= lo_nm) & (distances <= hi_nm)
            distances_target = distances[mask_target]
            nnd_target_count = int(mask_target.sum())
            nnd_target_mean = float(distances_target.mean()) if nnd_target_count > 0 else float("nan")
            nnd_target_median = float(distances_target.median()) if nnd_target_count > 0 else float("nan")
            stats["nnd_target_count"] = nnd_target_count
            stats["nnd_target_mean"] = nnd_target_mean
            stats["nnd_target_median"] = nnd_target_median

            if nnd_target_count > 0:
                fig, ax = plt.subplots(figsize=(8, 5))
                ax.hist(distances_target, bins=40, color="#AB47BC", edgecolor="white",
                        linewidth=0.3, density=True)
                ax.axvline(nnd_target_median, color="#EF5350", linewidth=1.5, linestyle="--",
                           label=f"Median  {nnd_target_median:.1f} nm")
                ax.axvline(qc.nnd_target_nm,  color="#FFFFFF", linewidth=1.0, linestyle=":",
                           label=f"Target  {qc.nnd_target_nm:.0f} nm")
                ax.set_xlabel("Nearest-neighbour distance (nm)")
                ax.set_ylabel("Density")
                ax.set_title(
                    f"NND {lo_nm:.0f}–{hi_nm:.0f} nm subset — {entry.name}"
                    f"  (n={nnd_target_count:,})",
                    wrap=True,
                )
                ax.legend(fontsize=8, loc="upper right", framealpha=0.85)
                fig.tight_layout()
                fig.savefig(out_dir / "nnd_target_subset.png", dpi=150)
                plt.close(fig)
                pd.DataFrame({"distance_nm": distances_target.values}).to_csv(
                    out_dir / "nnd_target_subset.csv", index=False)
        except Exception as e:
            log_fn(f"  [WARN] NND failed for {entry.name}: {e}")

    if plot_config.intensity_vs_time and c.frame_col in df.columns:
        try:
            grp = df.groupby(c.frame_col)
            frames_arr = np.array(list(grp.groups.keys()), dtype=int)
            mean_intensity_per_frame = grp[c.intensity_col].mean().values
            n_locs_per_frame_arr = grp.size().values

            fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
            ax1.plot(frames_arr, n_locs_per_frame_arr, linewidth=0.8, color="#4FC3F7")
            ax1.set_ylabel("Localizations / frame")
            ax1.set_title(f"Blinking trace — {entry.name}")
            ax2.plot(frames_arr, mean_intensity_per_frame / 1e3, linewidth=0.8,
                     color="#FFB74D")
            ax2.set_ylabel("Mean intensity (×10³ photon)")
            ax2.set_xlabel("Frame")
            fig.tight_layout()
            fig.savefig(out_dir / "intensity_vs_time.png", dpi=150)
            plt.close(fig)

            pd.DataFrame({
                c.frame_col: frames_arr,
                "n_locs": n_locs_per_frame_arr,
                "mean_intensity": mean_intensity_per_frame,
            }).to_csv(out_dir / "intensity_vs_time.csv", index=False)
        except Exception as e:
            log_fn(f"  [WARN] intensity-vs-time failed for {entry.name}: {e}")

    if plot_config.histograms:
        try:
            unc_vals = unc.values
            fig, ax = plt.subplots(figsize=(8, 5))
            ax.hist(unc_vals, bins=80, color="#7E57C2", edgecolor="white",
                    linewidth=0.3, density=True)
            med = float(np.median(unc_vals))
            mn = float(np.mean(unc_vals))
            p90 = float(np.percentile(unc_vals, 90))
            ax.axvline(med, color="#EF5350", linewidth=1.5, linestyle="--",
                       label=f"Median  {med:.1f} nm")
            ax.axvline(mn,  color="#FFA726", linewidth=1.5, linestyle=":",
                       label=f"Mean  {mn:.1f} nm")
            ax.axvline(p90, color="#66BB6A", linewidth=1.2, linestyle="-.",
                       label=f"90th pct  {p90:.1f} nm")
            ax.legend(fontsize=8, loc="upper right", framealpha=0.85)
            ax.set_xlabel("Localization uncertainty (nm)")
            ax.set_ylabel("Density")
            ax.set_title(
                f"Uncertainty histogram — {entry.name}  (n={len(unc_vals):,})",
                wrap=True,
            )
            fig.tight_layout()
            fig.savefig(out_dir / "uncertainty_histogram.png", dpi=150)
            plt.close(fig)
        except Exception as e:
            log_fn(f"  [WARN] uncertainty histogram failed for {entry.name}: {e}")

    blinking_top_score = 0.0
    blinking_spots_found = 0
    if plot_config.blinking_showcase and c.frame_col in df_3sigma.columns and c.x_col in df_3sigma.columns:
        try:
            all_triplets, detection_msg = _find_all_blinking_triplets(
                df_3sigma, n_spots=qc.n_spots,
                spacing_nm=qc.dna_origami_spacing_nm,
                spacing_tol_nm=qc.spacing_tol_nm,
                pixel_size_nm=qc.pixel_size_nm,
                uncertainty_col=c.uncertainty_col,
                x_col=c.x_col, y_col=c.y_col,
                frame_col=c.frame_col, intensity_col=c.intensity_col,
                max_triplets=qc.max_triplets,
                log_fn=log_fn,
            )
            blinking_spots_found = len(all_triplets)
            if all_triplets:
                top_triplet = all_triplets[0]
                blinking_top_score = sum(s["score"] for s in top_triplet)
                _plot_blinking_showcase(lc, locs_3sigma, top_triplet, df_3sigma,
                                        out_dir, entry.name,
                                        frame_col=c.frame_col,
                                        intensity_col=c.intensity_col)
                viewer_html = _write_interactive_viewer(
                    all_triplets, df_3sigma, out_dir, entry.name,
                    frame_col=c.frame_col, intensity_col=c.intensity_col,
                    log_fn=log_fn,
                )
                stats["viewer_html_path"] = str(viewer_html) if viewer_html else None
            else:
                log_fn(f"  [WARN] {detection_msg}")
            stats["blinking_spots_found"] = blinking_spots_found
            stats["blinking_top_score"] = blinking_top_score
        except Exception as e:
            log_fn(f"  [WARN] blinking showcase failed for {entry.name}: {e}")

    (out_dir / "stats.json").write_text(json.dumps(stats, indent=2), encoding="utf-8")

    return AnalysisResult(
        name=entry.name,
        n_raw=n_raw,
        n_filtered=n_filtered,
        mean_uncertainty=stats.get("uncertainty_mean", float("nan")),
        median_uncertainty=stats.get("uncertainty_median", float("nan")),
        std_uncertainty=stats.get("uncertainty_std", float("nan")),
        mean_sigma=stats.get("sigma_mean", float("nan")),
        median_sigma=stats.get("sigma_median", float("nan")),
        mean_intensity=stats.get("intensity_mean", float("nan")),
        median_intensity=stats.get("intensity_median", float("nan")),
        mean_bkgstd=stats.get("bkgstd_mean", float("nan")),
        nnd_mean=nnd_mean,
        nnd_median=nnd_median,
        n_3sigma=n_3sigma,
        nnd_target_count=nnd_target_count,
        nnd_target_mean=nnd_target_mean,
        nnd_target_median=nnd_target_median,
        blinking_top_score=blinking_top_score,
        blinking_spots_found=blinking_spots_found,
        locs_per_frame=locs_per_frame,
        output_dir=out_dir,
        viewer_html_path=Path(stats["viewer_html_path"]) if stats.get("viewer_html_path") else None,
    )


# ---------------------------------------------------------------------------
# Cross-dataset comparison plots
# ---------------------------------------------------------------------------

def generate_comparison(results: list[AnalysisResult],
                         output_dir: Path, log_fn: LogFn) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)

    if not results:
        log_fn("[WARN] No results to compare.")
        return

    df = pd.DataFrame([
        {
            "name": r.name,
            "n_filtered": r.n_filtered,
            "mean_uncertainty": r.mean_uncertainty,
            "median_uncertainty": r.median_uncertainty,
            "mean_sigma": r.mean_sigma,
            "median_sigma": r.median_sigma,
            "mean_intensity": r.mean_intensity,
            "median_intensity": r.median_intensity,
            "mean_bkgstd": r.mean_bkgstd,
            "nnd_mean": r.nnd_mean,
            "nnd_median": r.nnd_median,
            "n_3sigma": r.n_3sigma,
            "nnd_target_count": r.nnd_target_count,
            "nnd_target_mean": r.nnd_target_mean,
            "nnd_target_median": r.nnd_target_median,
        }
        for r in results
    ])

    df.to_csv(output_dir / "summary_table.csv", index=False)
    log_fn(f"[INFO] Summary table saved to {output_dir / 'summary_table.csv'}")

    names = df["name"].tolist()
    colors = [_DATASET_COLORS[i % len(_DATASET_COLORS)] for i in range(len(names))]

    metrics = [
        ("n_filtered", "Localizations (QC-filtered)", "Localizations per dataset"),
        ("mean_uncertainty", "Mean localization uncertainty (nm)", "Uncertainty per dataset"),
        ("median_intensity", "Median intensity (photon)", "Intensity per dataset"),
        ("mean_sigma", "Mean PSF sigma (nm)", "PSF Sigma per dataset"),
        ("nnd_mean", "Mean NND (nm)", "Nearest-Neighbour Distance per dataset"),
        ("n_3sigma", "Locs surviving 3σ photon filter", "3σ Filtered Locs per dataset"),
        ("nnd_target_mean", "Mean NND target subset (nm)", "NND Target Subset per dataset"),
    ]

    for metric, ylabel, title in metrics:
        try:
            fig, ax = plt.subplots(figsize=(max(6, len(names) * 1.2), 5))
            vals = df[metric].tolist()
            bars = ax.bar(range(len(names)), vals, color=colors, edgecolor="black",
                          linewidth=0.5)
            for bar, val in zip(bars, vals):
                if not (isinstance(val, float) and (val != val)):
                    ax.text(bar.get_x() + bar.get_width() / 2,
                            bar.get_height() * 1.01, f"{val:.1f}",
                            ha="center", va="bottom", fontsize=8)
            ax.set_xticks(range(len(names)))
            ax.set_xticklabels(names, rotation=30, ha="right", fontsize=9)
            ax.set_ylabel(ylabel)
            ax.set_title(title)
            fig.tight_layout()
            fig.savefig(output_dir / f"{metric}_comparison.png", dpi=150)
            plt.close(fig)
        except Exception:
            plt.close("all")

    try:
        fig, ax = plt.subplots(figsize=(10, 4))
        for r, color in zip(results, colors):
            if r.locs_per_frame:
                ax.plot(r.locs_per_frame, color=color, linewidth=0.7, alpha=0.8,
                        label=r.name)
        ax.set_xlabel("Frame")
        ax.set_ylabel("Localizations per frame")
        ax.set_title("Localizations per Frame")
        ax.legend(fontsize=8)
        fig.tight_layout()
        fig.savefig(output_dir / "locs_per_frame_overlay.png", dpi=150)
        plt.close(fig)
    except Exception:
        plt.close("all")

    try:
        fig, ax = plt.subplots(figsize=(7, 5))
        for r, color in zip(results, colors):
            if r.output_dir is None:
                continue
            nnd_file = r.output_dir / "nnd_distances.csv"
            if not nnd_file.exists():
                continue
            dists = pd.read_csv(nnd_file)["distance_nm"].dropna().sort_values()
            cdf = np.arange(1, len(dists) + 1) / len(dists)
            ax.plot(dists, cdf, color=color, linewidth=1.0, alpha=0.8, label=r.name)
        ax.set_xlabel("Nearest-neighbour distance (nm)")
        ax.set_ylabel("CDF")
        ax.set_title("NND CDF per Dataset")
        ax.legend(fontsize=8)
        fig.tight_layout()
        fig.savefig(output_dir / "nnd_cdf_overlay.png", dpi=150)
        plt.close(fig)
    except Exception:
        plt.close("all")

    _boxplot_configs = [
        ("nnd_distances.csv", "distance_nm", "NND (nm)", "NND distribution per dataset", "nnd_boxplot.png"),
        ("uncertainty_values.csv", "uncertainty_nm", "Localization uncertainty (nm)",
         "Uncertainty distribution per dataset", "uncertainty_boxplot.png"),
        ("intensity_values.csv", "intensity_ph", "Intensity (photon)",
         "Intensity distribution per dataset", "intensity_boxplot.png"),
        ("sigma_values.csv", "sigma_nm", "PSF sigma (nm)",
         "PSF sigma distribution per dataset", "sigma_boxplot.png"),
    ]

    for csv_name, col_name, ylabel, title, out_name in _boxplot_configs:
        try:
            box_data = []
            box_labels = []
            for r in results:
                if r.output_dir is None:
                    continue
                csv_path = r.output_dir / csv_name
                if not csv_path.exists():
                    continue
                vals = pd.read_csv(csv_path)[col_name].dropna().values
                if len(vals) == 0:
                    continue
                box_data.append(vals)
                box_labels.append(r.name)
            if not box_data:
                continue
            fig, ax = plt.subplots(figsize=(max(6, len(box_data) * 1.5), 5))
            bp = ax.boxplot(box_data, tick_labels=box_labels, notch=False,
                            showfliers=False, showmeans=True, patch_artist=True,
                            medianprops=dict(color="#EF5350", linewidth=2),
                            meanprops=dict(marker="D", markerfacecolor="#FFA726",
                                           markeredgecolor="black", markersize=6))
            for patch, color in zip(bp["boxes"], colors):
                patch.set_facecolor(color)
                patch.set_alpha(0.7)
            ax.set_ylabel(ylabel)
            ax.set_title(title)
            ax.tick_params(axis="x", rotation=30)
            fig.tight_layout()
            fig.savefig(output_dir / out_name, dpi=150)
            plt.close(fig)
        except Exception:
            plt.close("all")

    log_fn(f"[INFO] Comparison plots saved to {output_dir}")
