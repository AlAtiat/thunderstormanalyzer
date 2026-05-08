"""ThunderSTORM Analyzer — BeeWare/Toga GUI application."""
from __future__ import annotations

import asyncio
import os
import shutil
import sys
import tempfile
import threading
import warnings
from pathlib import Path

import toga
from toga.style.pack import COLUMN, ROW, Pack

from .models import ColumnConfig, DatasetEntry, PlotConfig, QcParams
from .pipeline import qc_from_protocol, _load_protocol
from .runner import AnalysisRunner


# ---------------------------------------------------------------------------
# Dataset row widget
# ---------------------------------------------------------------------------

class DatasetRow(toga.Box):
    """One row in the dataset list: name + file browsers + editable QC fields."""

    def __init__(self, app: "ThunderSTORMAnalyzer", on_remove: callable) -> None:
        super().__init__(style=Pack(direction=COLUMN, margin=8,
                                    background_color="#ffffff"))
        self._app = app
        self._csv_path: Path | None = None
        self._protocol_path: Path | None = None
        self._dialog_open: bool = False
        self._csv_format: str = "thunderstorm"



        
        
        lbl_style = Pack(width=130, color="#212121", margin_right=4)
        val_style = Pack(flex=1, color="#444444")

        # Name row
        name_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        name_row.add(toga.Label("Name:", style=Pack(width=90, color="#212121", margin_right=4)))
        self.name_input = toga.TextInput(value="Dataset", style=Pack(flex=1))
        name_row.add(self.name_input)

        # CSV row
        csv_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        csv_row.add(toga.Label("CSV:", style=Pack(width=90, color="#212121", margin_right=4)))
        self.csv_label = toga.Label("(none)", style=val_style)
        csv_row.add(self.csv_label)
        csv_row.add(toga.Button("Browse…", on_press=self._browse_csv,
                                style=Pack(margin_left=6)))

        # Protocol row
        proto_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        proto_row.add(toga.Label("Protocol:", style=Pack(width=90, color="#212121", margin_right=4)))
        self.proto_label = toga.Label("(optional)", style=val_style)
        proto_row.add(self.proto_label)
        proto_row.add(toga.Button("Browse…", on_press=self._browse_protocol,
                                  style=Pack(margin_left=6)))

        # Camera / Optics section
        optics_header = toga.Label("Camera / Optics", style=Pack(
            color="#1565C0", font_size=10, margin_top=6, margin_bottom=4))

        optics_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        optics_row.add(toga.Label("Camera pixel (nm):", style=lbl_style))
        self.qc_camera_px = toga.TextInput(value="6500", style=Pack(width=65),
                                            on_change=self._update_pixel_size_label)
        optics_row.add(self.qc_camera_px)
        optics_row.add(toga.Label("  Magnification (×):", style=lbl_style))
        self.qc_magnification = toga.TextInput(value="100", style=Pack(width=65),
                                               on_change=self._update_pixel_size_label)
        optics_row.add(self.qc_magnification)

        px_label_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        self.effective_px_label = toga.Label("Effective pixel size: 65.00 nm/px",  # 6500 nm / 100×
                                              style=Pack(color="#1565C0", font_size=9, flex=1))
        px_label_row.add(self.effective_px_label)

        # QC parameters
        qc_header = toga.Label("QC Parameters", style=Pack(
            color="#1565C0", font_size=10, margin_top=6, margin_bottom=4))

        qc_row1 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row1.add(toga.Label("Pixel size (nm):", style=lbl_style))
        self.qc_pixel_size = toga.TextInput(value="65.0", style=Pack(width=65))
        qc_row1.add(self.qc_pixel_size)
        qc_row1.add(toga.Label("  Max sigma (nm):", style=lbl_style))
        self.qc_max_sigma = toga.TextInput(value="768.0", style=Pack(width=65))
        qc_row1.add(self.qc_max_sigma)

        qc_row2 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row2.add(toga.Label("Max unc (nm):", style=lbl_style))
        self.qc_max_unc = toga.TextInput(value="40.0", style=Pack(width=65))
        qc_row2.add(self.qc_max_unc)
        qc_row2.add(toga.Label("  Min intensity:", style=lbl_style))
        self.qc_min_intensity = toga.TextInput(value="1000.0", style=Pack(width=65))
        qc_row2.add(self.qc_min_intensity)

        qc_row3 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row3.add(toga.Label("NND target (nm):", style=lbl_style))
        self.qc_nnd_target = toga.TextInput(value="80.0", style=Pack(width=65))
        qc_row3.add(self.qc_nnd_target)
        qc_row3.add(toga.Label("  NND tol (nm):", style=lbl_style))
        self.qc_nnd_tol = toga.TextInput(value="20.0", style=Pack(width=65))
        qc_row3.add(self.qc_nnd_tol)

        qc_row4 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row4.add(toga.Label("Spacing (nm):", style=lbl_style))
        self.qc_spacing = toga.TextInput(value="80.0", style=Pack(width=65))
        qc_row4.add(self.qc_spacing)
        qc_row4.add(toga.Label("  Spacing tol (nm):", style=lbl_style))
        self.qc_spacing_tol = toga.TextInput(value="20.0", style=Pack(width=65))
        qc_row4.add(self.qc_spacing_tol)

        remove_row = toga.Box(style=Pack(direction=ROW, margin_top=6))
        remove_row.add(toga.Button("✕ Remove dataset",
                                   on_press=lambda w: on_remove(self)))

        divider = toga.Label("─" * 80, style=Pack(color="#cccccc",
                                                    margin_top=8, margin_bottom=2))

        self.add(name_row)
        self.add(csv_row)
        self.add(proto_row)
        self.add(optics_header)
        self.add(optics_row)
        self.add(px_label_row)
        self.add(qc_header)
        self.add(qc_row1)
        self.add(qc_row2)
        self.add(qc_row3)
        self.add(qc_row4)
        self.add(remove_row)
        self.add(divider)

    # ── Pixel size label ──────────────────────────────────────────────────────

    def _update_pixel_size_label(self, widget=None) -> None:
        try:
            nm = float(self.qc_camera_px.value) / float(self.qc_magnification.value)
        except (ValueError, ZeroDivisionError):
            self.effective_px_label.text = "Effective pixel size: — nm/px"
            return
        self.qc_pixel_size.value = f"{nm:.2f}"
        self.effective_px_label.text = f"Effective pixel size: {nm:.2f} nm/px"

    # ── File browsers ─────────────────────────────────────────────────────────

    async def _browse_csv(self, widget: toga.Widget) -> None:
        if self._dialog_open:
            return
        self._dialog_open = True
        try:
            result = await self._app.main_window.dialog(
                toga.OpenFileDialog(title="Select localizations CSV",
                                    file_types=["csv"]))
        finally:
            self._dialog_open = False
        if result is None:
            return
        self._csv_path = Path(str(result))
        self.csv_label.text = self._csv_path.name
        self.csv_label.style.color = "#212121"
        headers = self._read_locan_headers(self._csv_path)
        self._csv_format = "thunderstorm"
        if headers:
            self._app._update_col_pickers(headers)

    @staticmethod
    def _read_locan_headers(path: Path) -> list[str]:
        try:
            import locan as lc
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                locs = lc.load_thunderstorm_file(str(path))
            return list(locs.data.columns)
        except Exception:
            return []

    async def _browse_protocol(self, widget: toga.Widget) -> None:
        if self._dialog_open:
            return
        self._dialog_open = True
        try:
            result = await self._app.main_window.dialog(
                toga.OpenFileDialog(title="Select table-protocol.txt",
                                    file_types=["txt", "json"]))
        finally:
            self._dialog_open = False
        if result is not None:
            self._protocol_path = Path(str(result))
            self.proto_label.text = self._protocol_path.name
            self.proto_label.style.color = "#212121"
            self._autofill_qc_from_protocol()

    # ── Protocol auto-fill ────────────────────────────────────────────────────

    def _autofill_qc_from_protocol(self) -> None:
        if self._protocol_path is None:
            return
        protocol = _load_protocol(self._protocol_path)
        if not protocol:
            return
        qc = qc_from_protocol(protocol)
        # Camera pixel: protocol pixelSize is the effective pixel, so set camera=pixel, mag=1
        self.qc_camera_px.value = f"{qc.pixel_size_nm:.1f}"
        self.qc_magnification.value = "1"
        self.qc_pixel_size.value = f"{qc.pixel_size_nm:.1f}"
        self.qc_max_sigma.value = f"{qc.max_sigma_nm:.1f}"
        self.qc_max_unc.value = f"{qc.max_uncertainty_nm:.1f}"
        self.qc_min_intensity.value = f"{qc.min_intensity:.1f}"
        self.effective_px_label.text = f"Effective pixel size: {qc.pixel_size_nm:.2f} nm/px (from protocol)"

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _float(self, widget: toga.TextInput, default: float) -> float:
        try:
            return float(widget.value)
        except (ValueError, TypeError):
            return default

    def to_entry(self) -> DatasetEntry:
        d = QcParams()
        try:
            cam_nm = float(self.qc_camera_px.value)
            mag = float(self.qc_magnification.value)
            pixel_nm = cam_nm / mag
        except (ValueError, ZeroDivisionError):
            pixel_nm = self._float(self.qc_pixel_size, d.pixel_size_nm)
            cam_nm = d.camera_pixel_size_nm
            mag = d.magnification
        qc = QcParams(
            pixel_size_nm=pixel_nm,
            camera_pixel_size_nm=cam_nm,
            magnification=mag,
            max_sigma_nm=self._float(self.qc_max_sigma, d.max_sigma_nm),
            max_uncertainty_nm=self._float(self.qc_max_unc, d.max_uncertainty_nm),
            min_intensity=self._float(self.qc_min_intensity, d.min_intensity),
            nnd_target_nm=self._float(self.qc_nnd_target, d.nnd_target_nm),
            nnd_tolerance_nm=self._float(self.qc_nnd_tol, d.nnd_tolerance_nm),
            dna_origami_spacing_nm=self._float(self.qc_spacing, d.dna_origami_spacing_nm),
            spacing_tol_nm=self._float(self.qc_spacing_tol, d.spacing_tol_nm),
        )
        return DatasetEntry(
            name=self.name_input.value.strip() or "Unnamed",
            csv_path=self._csv_path,
            protocol_path=self._protocol_path,
            qc=qc,
            csv_format=self._csv_format,
        )

    @property
    def has_csv(self) -> bool:
        return self._csv_path is not None


# ---------------------------------------------------------------------------
# Main application
# ---------------------------------------------------------------------------

class ThunderSTORMAnalyzer(toga.App):
    def startup(self) -> None:
        # ── Splash content — logo only on matching background ─────────────────
        splash_box = toga.Box(style=Pack(direction=COLUMN, align_items="center",
                                          background_color="#f5f5f5", flex=1))
        try:
            icon_path = str(self.paths.app / "resources" / "icons" / "thunderstormanalyzer.png")
            img = toga.Image(icon_path)
            splash_box.add(toga.ImageView(img, style=Pack(width=200, height=200)))
        except Exception:
            pass

        self.main_window = toga.MainWindow(title="ThunderSTORM Analyzer",
                                            size=(1200, 760))
        self.main_window.content = splash_box

        # Remove window chrome for splash so only the logo floats on screen.
        # This uses the WinForms backend directly — guarded so it is a no-op on
        # other platforms or if the backend changes.
        try:
            import System.Windows.Forms as WinForms  # type: ignore[import]
            native = self.main_window._impl.native
            native.FormBorderStyle = getattr(WinForms.FormBorderStyle, "None")
            native.ShowInTaskbar = False
            native.TopMost = True
        except Exception:
            pass

        self.main_window.show()

        async def _load_ui():
            await asyncio.sleep(2.0)

            # Restore standard window chrome before swapping to real UI.
            try:
                import System.Windows.Forms as WinForms  # type: ignore[import]
                native = self.main_window._impl.native
                native.FormBorderStyle = WinForms.FormBorderStyle.Sizable
                native.ShowInTaskbar = True
                native.TopMost = False
            except Exception:
                pass

            # Silence the toga_winforms mouse-wheel bug on the log MultilineTextInput.
            # The WinForms backend calls self.container.native in on_mouse_wheel but
            # container may not have .native when the widget is inside a ScrollContainer.
            import sys as _sys
            if _sys.platform == "win32":
                try:
                    from toga_winforms.widgets.multilinetextinput import (
                        MultilineTextInput as _MLTI,
                    )
                    _orig_wheel = _MLTI.on_mouse_wheel
                    def _safe_wheel(self_inner, sender, event):
                        try:
                            _orig_wheel(self_inner, sender, event)
                        except AttributeError:
                            pass
                    _MLTI.on_mouse_wheel = _safe_wheel
                except Exception:
                    pass

            # ── State ─────────────────────────────────────────────────────────
            self._runner = AnalysisRunner()
            self._output_dir: Path | None = None
            self._last_results: list = []
            self._dataset_rows: list[DatasetRow] = []
            self._col_config = ColumnConfig()
            self._plot_config = PlotConfig()
            self._n_spots: int = 3
            self._max_triplets: int = 10
            self._min_blink_cycles = 2
            self._min_blink_frames = 5
            self._blink_gap_frames = 2
            self._dbscan_min_samples = 3
            self._render_bin_size_nm = 20
            self._collinear_angle_deg = 30.0

            # ── Sidebar ───────────────────────────────────────────────────────
            sidebar = toga.Box(style=Pack(direction=COLUMN, margin=12, width=200,
                                           background_color="#e8eaf6"))

            sidebar.add(toga.Label("ThunderSTORM\nAnalyzer",
                                   style=Pack(color="#1a237e", font_size=14,
                                              margin_bottom=16, text_align="center")))

            self.add_btn = toga.Button("+ Add Dataset", on_press=self._add_dataset,
                                       style=Pack(margin_bottom=8))
            sidebar.add(self.add_btn)

            self.run_btn = toga.Button("▶ Run Analysis", on_press=self._run_analysis,
                                       style=Pack(margin_bottom=8))
            sidebar.add(self.run_btn)

            sidebar.add(toga.Button("Clear All", on_press=self._clear_all,
                                    style=Pack(margin_bottom=16)))

            self.export_btn = toga.Button("Export Results as ZIP…",
                                          on_press=self._export_zip,
                                          style=Pack(margin_bottom=8))
            self.export_btn.enabled = False
            sidebar.add(self.export_btn)

            self.open_results_btn = toga.Button("Open Results Folder",
                                                on_press=self._open_results,
                                                style=Pack(margin_bottom=8))
            self.open_results_btn.enabled = False
            sidebar.add(self.open_results_btn)

            self.status_label = toga.Label("Status: Idle",
                                            style=Pack(color="#2e7d32", margin_top=8,
                                                       font_size=9))
            sidebar.add(self.status_label)

            self.progress_bar = toga.ProgressBar(
                max=None,
                style=Pack(margin_top=4, margin_bottom=4),
            )
            sidebar.add(self.progress_bar)

            # ── Dataset list ──────────────────────────────────────────────────
            self.dataset_container = toga.Box(style=Pack(direction=COLUMN,
                                                          background_color="#fafafa"))
            self._empty_label = toga.Label("Click '+ Add Dataset' to begin.",
                                            style=Pack(color="#9e9e9e", margin=16))
            self.dataset_container.add(self._empty_label)

            dataset_scroll = toga.ScrollContainer(
                content=self.dataset_container,
                style=Pack(flex=1, background_color="#fafafa"),
            )

            self.log_area = toga.MultilineTextInput(
                readonly=True, value="",
                style=Pack(height=160, font_family="monospace", font_size=9,
                           background_color="#fafafa", color="#333333"),
            )

            datasets_tab = toga.Box(style=Pack(direction=COLUMN, flex=1))
            datasets_tab.add(dataset_scroll)
            datasets_tab.add(self.log_area)

            # ── Results tab ───────────────────────────────────────────────────
            self.results_container = toga.Box(
                style=Pack(direction=COLUMN, background_color="#fafafa"))
            self.results_container.add(toga.Label(
                "Run an analysis to see results here.",
                style=Pack(color="#9e9e9e", margin=16)))

            results_scroll = toga.ScrollContainer(
                content=self.results_container,
                style=Pack(flex=1, background_color="#fafafa"),
            )

            # ── Configuration tab ─────────────────────────────────────────────
            config_scroll = toga.ScrollContainer(
                content=self._build_config_tab(),
                style=Pack(flex=1, background_color="#fafafa"),
            )

            # ── Tabs ──────────────────────────────────────────────────────────
            self.tabs = toga.OptionContainer(
                content=[
                    toga.OptionItem("Datasets", datasets_tab),
                    toga.OptionItem("Results", results_scroll),
                    toga.OptionItem("Configuration", config_scroll),
                ],
                style=Pack(flex=1),
            )

            main_box = toga.Box(style=Pack(direction=ROW, flex=1,
                                            background_color="#f5f5f5"))
            main_box.add(sidebar)
            main_box.add(self.tabs)

            self.main_window.content = main_box

        asyncio.ensure_future(_load_ui())

    # ── Configuration tab builder ─────────────────────────────────────────────

    # Column role definitions used by both _build_config_tab and _update_col_pickers
    _COL_DEFS = [
        ("X position column:", "x_col", "position_x"),
        ("Y position column:", "y_col", "position_y"),
        ("Uncertainty column:", "uncertainty_col", "uncertainty_x"),
        ("Intensity column:", "intensity_col", "intensity"),
        ("PSF sigma column:", "sigma_col", "psf_sigma"),
        ("Frame column:", "frame_col", "frame"),
        ("Background σ pattern:", "bkgstd_pattern", "background_sigma"),
    ]

    def _build_config_tab(self) -> toga.Box:
        box = toga.Box(style=Pack(direction=COLUMN, margin=16,
                                   background_color="#fafafa"))
        lbl = Pack(width=200, color="#212121", margin_right=8)

        box.add(toga.Label("Column Mapping",
                            style=Pack(color="#1a237e", font_size=12, margin_bottom=8)))
        box.add(toga.Label(
            "Load a CSV to auto-populate dropdowns, or type column names directly.",
            style=Pack(color="#546e7a", font_size=9, margin_bottom=12)))

        self._col_inputs: dict[str, toga.Widget] = {}
        self._col_rows: dict[str, toga.Box] = {}
        for label_text, attr, default in self._COL_DEFS:
            row = toga.Box(style=Pack(direction=ROW, margin_bottom=6))
            row.add(toga.Label(label_text, style=lbl))
            inp = toga.TextInput(value=default, style=Pack(width=220))
            self._col_inputs[attr] = inp
            row.add(inp)
            self._col_rows[attr] = row
            box.add(row)

        self._col_picker_box = box  # keep reference so _update_col_pickers can swap widgets

        box.add(toga.Label("─" * 80, style=Pack(color="#cccccc", margin_top=8, margin_bottom=8)))

        nspots_row = toga.Box(style=Pack(direction=ROW, margin_bottom=8))
        nspots_row.add(toga.Label("Spots per structure (DNA origami):",
                                   style=Pack(width=280, color="#212121", margin_right=8)))
        self._n_spots_input = toga.TextInput(value="3", style=Pack(width=60))
        nspots_row.add(self._n_spots_input)
        box.add(nspots_row)

        max_triplets_row = toga.Box(style=Pack(direction=ROW, margin_bottom=8))
        max_triplets_row.add(toga.Label("Max triplet images to show:",
                                        style=Pack(width=280, color="#212121", margin_right=8)))
        self._max_triplets_input = toga.TextInput(value="10", style=Pack(width=60))
        max_triplets_row.add(self._max_triplets_input)
        box.add(max_triplets_row)

        box.add(toga.Label("─" * 80, style=Pack(color="#cccccc", margin_top=4, margin_bottom=8)))
        box.add(toga.Label("Advanced Detection Parameters",
                            style=Pack(color="#1a237e", font_size=12, margin_bottom=4)))
        box.add(toga.Label(
            "Blinking detection thresholds — tune for your camera frame rate and sample density.",
            style=Pack(color="#546e7a", font_size=9, margin_bottom=10)))

        adv_lbl = Pack(width=200, color="#212121", margin_right=8)
        adv_inp = Pack(width=60)

        adv_row1 = toga.Box(style=Pack(direction=ROW, margin_bottom=6))
        adv_row1.add(toga.Label("Min on/off cycles:", style=adv_lbl))
        self._min_blink_cycles_input = toga.TextInput(value="2", style=adv_inp)
        adv_row1.add(self._min_blink_cycles_input)
        adv_row1.add(toga.Label("  Min active frames:", style=adv_lbl))
        self._min_blink_frames_input = toga.TextInput(value="5", style=adv_inp)
        adv_row1.add(self._min_blink_frames_input)
        box.add(adv_row1)

        adv_row2 = toga.Box(style=Pack(direction=ROW, margin_bottom=6))
        adv_row2.add(toga.Label("Blink gap (frames):", style=adv_lbl))
        self._blink_gap_frames_input = toga.TextInput(value="2", style=adv_inp)
        adv_row2.add(self._blink_gap_frames_input)
        adv_row2.add(toga.Label("  Min locs per cluster:", style=adv_lbl))
        self._dbscan_min_samples_input = toga.TextInput(value="3", style=adv_inp)
        adv_row2.add(self._dbscan_min_samples_input)
        box.add(adv_row2)

        adv_row3 = toga.Box(style=Pack(direction=ROW, margin_bottom=6))
        adv_row3.add(toga.Label("Render bin size (nm):", style=adv_lbl))
        self._render_bin_size_input = toga.TextInput(value="20", style=adv_inp)
        adv_row3.add(self._render_bin_size_input)
        adv_row3.add(toga.Label("  Collinear angle (°):", style=adv_lbl))
        self._collinear_angle_input = toga.TextInput(value="30.0", style=adv_inp)
        adv_row3.add(self._collinear_angle_input)
        box.add(adv_row3)

        box.add(toga.Label("─" * 80, style=Pack(color="#cccccc", margin_top=4, margin_bottom=8)))
        box.add(toga.Label("Plot Selection",
                            style=Pack(color="#1a237e", font_size=12, margin_bottom=8)))
        box.add(toga.Label(
            "Disable plot types to speed up analysis or skip unwanted outputs.",
            style=Pack(color="#546e7a", font_size=9, margin_bottom=12)))

        plots = [
            ("Histograms (uncertainty, intensity, sigma)", "histograms"),
            ("Super-resolution renders", "superres_render"),
            ("NND distribution + target subset", "nnd_plots"),
            ("Intensity vs time", "intensity_vs_time"),
            ("Blinking showcase (DNA origami structure search)", "blinking_showcase"),
            ("Comparison plots (cross-dataset bar charts + box plots)", "comparison_plots"),
        ]
        self._plot_switches: dict[str, toga.Switch] = {}
        for label_text, attr in plots:
            row = toga.Box(style=Pack(direction=ROW, margin_bottom=6))
            sw = toga.Switch(label_text, value=True, style=Pack(flex=1))
            self._plot_switches[attr] = sw
            row.add(sw)
            box.add(row)

        return box

    def _update_col_pickers(self, headers: list[str]) -> None:
        """Replace TextInput col fields with Selection dropdowns populated from CSV headers."""
        for _, attr, default in self._COL_DEFS:
            row_box = self._col_rows[attr]
            old_widget = self._col_inputs[attr]
            # Pass 1: exact match on the canonical default name (e.g. "position_x")
            best = next((h for h in headers if h == default), None)
            # Pass 2: prefix substring fallback (e.g. "position" in "position_x")
            if best is None:
                prefix = default.split("_")[0].lower()
                best = next((h for h in headers if prefix in h.lower()), headers[0])
            sel = toga.Selection(items=headers, value=best, style=Pack(width=220))
            self._col_inputs[attr] = sel
            row_box.remove(old_widget)
            row_box.add(sel)

    def _read_config_tab(self) -> None:
        """Sync UI state from the Configuration tab into self._col_config, _plot_config, _n_spots."""
        def _col_val(attr: str, fallback: str) -> str:
            v = self._col_inputs[attr].value
            return (v.strip() if isinstance(v, str) else str(v).strip()) or fallback

        self._col_config = ColumnConfig(
            x_col=_col_val("x_col", "position_x"),
            y_col=_col_val("y_col", "position_y"),
            uncertainty_col=_col_val("uncertainty_col", "uncertainty_x"),
            intensity_col=_col_val("intensity_col", "intensity"),
            sigma_col=_col_val("sigma_col", "psf_sigma"),
            frame_col=_col_val("frame_col", "frame"),
            bkgstd_pattern=_col_val("bkgstd_pattern", "background_sigma"),
        )
        self._plot_config = PlotConfig(
            histograms=self._plot_switches["histograms"].value,
            superres_render=self._plot_switches["superres_render"].value,
            nnd_plots=self._plot_switches["nnd_plots"].value,
            intensity_vs_time=self._plot_switches["intensity_vs_time"].value,
            blinking_showcase=self._plot_switches["blinking_showcase"].value,
            comparison_plots=self._plot_switches["comparison_plots"].value,
        )
        try:
            self._n_spots = int(self._n_spots_input.value)
        except (ValueError, TypeError):
            self._n_spots = 3
        try:
            self._max_triplets = max(1, int(self._max_triplets_input.value))
        except (ValueError, TypeError):
            self._max_triplets = 10
        try:
            self._min_blink_cycles = max(1, int(self._min_blink_cycles_input.value))
        except (ValueError, TypeError):
            self._min_blink_cycles = 2
        try:
            self._min_blink_frames = max(1, int(self._min_blink_frames_input.value))
        except (ValueError, TypeError):
            self._min_blink_frames = 5
        try:
            self._blink_gap_frames = max(1, int(self._blink_gap_frames_input.value))
        except (ValueError, TypeError):
            self._blink_gap_frames = 2
        try:
            self._dbscan_min_samples = max(1, int(self._dbscan_min_samples_input.value))
        except (ValueError, TypeError):
            self._dbscan_min_samples = 3
        try:
            self._render_bin_size_nm = max(1, int(self._render_bin_size_input.value))
        except (ValueError, TypeError):
            self._render_bin_size_nm = 20
        try:
            self._collinear_angle_deg = float(self._collinear_angle_input.value)
        except (ValueError, TypeError):
            self._collinear_angle_deg = 30.0

    # ── Dataset management ────────────────────────────────────────────────────

    def _add_dataset(self, widget: toga.Widget) -> None:
        if self._empty_label in self.dataset_container.children:
            self.dataset_container.remove(self._empty_label)
        row = DatasetRow(self, on_remove=self._remove_dataset)
        row.name_input.value = f"Dataset {len(self._dataset_rows) + 1}"
        self._dataset_rows.append(row)
        self.dataset_container.add(row)

    def _remove_dataset(self, row: DatasetRow) -> None:
        self._dataset_rows.remove(row)
        self.dataset_container.remove(row)
        if not self._dataset_rows:
            self.dataset_container.add(self._empty_label)

    def _clear_all(self, widget: toga.Widget) -> None:
        for row in list(self._dataset_rows):
            self.dataset_container.remove(row)
        self._dataset_rows.clear()
        self.dataset_container.add(self._empty_label)
        self.log_area.value = ""
        self.status_label.text = "Status: Idle"
        self.open_results_btn.enabled = False
        self.export_btn.enabled = False
        self._last_results = []
        if self._output_dir and self._output_dir.exists():
            shutil.rmtree(self._output_dir, ignore_errors=True)
        self._output_dir = None

    # ── Run ───────────────────────────────────────────────────────────────────

    async def _run_analysis(self, widget: toga.Widget) -> None:
        if self._runner.running:
            return

        if not self._dataset_rows:
            await self.main_window.dialog(
                toga.ErrorDialog("No datasets",
                                 "Add at least one dataset before running."))
            return

        missing_csv = [r for r in self._dataset_rows if not r.has_csv]
        if missing_csv:
            names = ", ".join(r.name_input.value for r in missing_csv)
            await self.main_window.dialog(
                toga.ErrorDialog("Missing CSV",
                                 f"Please select a CSV file for: {names}"))
            return

        # Fresh temp dir for this run
        if self._output_dir and self._output_dir.exists():
            shutil.rmtree(self._output_dir, ignore_errors=True)
        self._output_dir = Path(tempfile.mkdtemp(prefix="thunderstorm_"))

        self._read_config_tab()
        datasets = [r.to_entry() for r in self._dataset_rows]
        for entry in datasets:
            entry.qc.n_spots = self._n_spots
            entry.qc.max_triplets = self._max_triplets
            entry.qc.min_blink_cycles = self._min_blink_cycles
            entry.qc.min_blink_frames = self._min_blink_frames
            entry.qc.blink_gap_frames = self._blink_gap_frames
            entry.qc.dbscan_min_samples = self._dbscan_min_samples
            entry.qc.render_bin_size_nm = self._render_bin_size_nm
            entry.qc.collinear_angle_deg = self._collinear_angle_deg

        self.run_btn.enabled = False
        self.add_btn.enabled = False
        self.open_results_btn.enabled = False
        self.export_btn.enabled = False
        self.status_label.text = "Status: Running…"
        self.log_area.value = ""
        self.progress_bar.start()

        loop = asyncio.get_event_loop()

        def on_log(msg: str) -> None:
            async def _append() -> None:
                self.log_area.value += msg + "\n"
            loop.call_soon_threadsafe(asyncio.ensure_future, _append())

        def on_done(results) -> None:
            async def _finish() -> None:
                self._last_results = results
                self.run_btn.enabled = True
                self.add_btn.enabled = True
                self.open_results_btn.enabled = True
                self.export_btn.enabled = True
                self.progress_bar.stop()
                ok = sum(1 for r in results if not r.error)
                self.status_label.text = f"Status: Done ({ok}/{len(results)} OK)"
                self._populate_results_tab(results)
                self.tabs.current_tab = self.tabs.content[1]
            loop.call_soon_threadsafe(asyncio.ensure_future, _finish())

        self._runner.run(
            datasets=datasets,
            output_dir=self._output_dir,
            on_log=on_log,
            on_done=on_done,
            col_config=self._col_config,
            plot_config=self._plot_config,
        )

    # ── Results tab ───────────────────────────────────────────────────────────

    def _populate_results_tab(self, results) -> None:
        for child in list(self.results_container.children):
            self.results_container.remove(child)

        if not results:
            self.results_container.add(toga.Label(
                "No results.", style=Pack(color="#9e9e9e", margin=16)))
            return

        comp_dir = self._output_dir / "comparison" if self._output_dir else None

        result_tabs = toga.OptionContainer(style=Pack(flex=1))
        for r in results:
            result_tabs.content.append(toga.OptionItem(r.name, self._make_dataset_tabs(r)))
        result_tabs.content.append(toga.OptionItem(
            "Comparison", self._make_comparison_tabs(results, comp_dir)))

        self.results_container.add(result_tabs)

    def _make_dataset_tabs(self, r) -> toga.OptionContainer:
        tabs = toga.OptionContainer(style=Pack(flex=1))
        tabs.content.append(toga.OptionItem("Stats", self._make_stats_box(r)))
        tabs.content.append(toga.OptionItem("Images", self._make_images_box(r)))
        tabs.content.append(toga.OptionItem("Plots", self._make_plots_box(r)))
        if r.viewer_html_path and r.viewer_html_path.exists():
            tabs.content.append(toga.OptionItem(
                "Origami Viewer", self._make_origami_viewer_box(r)))
        return tabs

    def _make_origami_viewer_box(self, r) -> toga.Box:
        """Render the origami triplet carousel inside a toga WebView."""
        outer = toga.Box(style=Pack(direction=COLUMN, flex=1))
        html = r.viewer_html_path.read_text(encoding="utf-8")
        wv = toga.WebView(style=Pack(flex=1))
        wv.set_content("http://localhost/", html)
        outer.add(wv)
        return outer

    def _make_stats_box(self, r) -> toga.ScrollContainer:
        import math

        box = toga.Box(style=Pack(direction=COLUMN, margin=12,
                                   background_color="#ffffff"))

        def fmt(v) -> str:
            if isinstance(v, float) and math.isnan(v):
                return "N/A"
            if isinstance(v, float):
                return f"{v:.2f}"
            return str(v)

        color = "#c62828" if r.error else "#1565C0"
        box.add(toga.Label(("⚠ " if r.error else "") + r.name,
                            style=Pack(color=color, font_size=12, margin_bottom=8)))

        if r.error:
            box.add(toga.Label(r.error, style=Pack(color="#c62828", margin_bottom=6)))

        stat_rows = [
            ("Raw localizations", fmt(r.n_raw)),
            ("QC-filtered locs", fmt(r.n_filtered)),
            ("3σ-filtered locs", fmt(r.n_3sigma)),
            ("Mean uncertainty (nm)", fmt(r.mean_uncertainty)),
            ("Median uncertainty (nm)", fmt(r.median_uncertainty)),
            ("Std uncertainty (nm)", fmt(r.std_uncertainty)),
            ("Mean PSF sigma (nm)", fmt(r.mean_sigma)),
            ("Median PSF sigma (nm)", fmt(r.median_sigma)),
            ("Mean intensity (ph)", fmt(r.mean_intensity)),
            ("Median intensity (ph)", fmt(r.median_intensity)),
            ("Mean background σ", fmt(r.mean_bkgstd)),
            ("NND mean (nm)", fmt(r.nnd_mean)),
            ("NND median (nm)", fmt(r.nnd_median)),
            ("NND target count", fmt(r.nnd_target_count)),
            ("NND target mean (nm)", fmt(r.nnd_target_mean)),
            ("NND target median (nm)", fmt(r.nnd_target_median)),
            ("Blinking spots found", fmt(r.blinking_spots_found)),
            ("Blinking top score", fmt(r.blinking_top_score)),
        ]
        for metric, val in stat_rows:
            row_box = toga.Box(style=Pack(direction=ROW, margin_bottom=3))
            row_box.add(toga.Label(metric + ":", style=Pack(width=230, color="#424242", font_size=9)))
            row_box.add(toga.Label(val, style=Pack(color="#212121", font_size=9)))
            box.add(row_box)

        return toga.ScrollContainer(content=box, style=Pack(flex=1))

    def _make_images_box(self, r) -> toga.Box:
        """SR renders and blinking showcase."""
        image_files = [
            ("superres_render.png",
             lambda res: f"Super-resolution render | n_locs={res.n_filtered}"),
            ("superres_render_clean.png",
             lambda res: f"Super-resolution render (3σ clean) | n_locs={res.n_3sigma}"),
            ("superres_render_3sigma.png",
             lambda res: f"Super-resolution render (3σ) | n_locs={res.n_3sigma}"),
            ("superres_render_clean_3sigma.png",
             lambda res: f"Super-resolution render (3σ clean) | n_locs={res.n_3sigma}"),
            ("blinking_showcase.png",
             lambda res: f"Blinking showcase | spots found={res.blinking_spots_found}"),
        ]
        return self._make_scrollable_image_tab(r, image_files, "No images available.")

    def _make_plots_box(self, r) -> toga.Box:
        """Statistical charts: histograms, NND, intensity vs time, locs per frame."""
        plot_files = [
            ("uncertainty_histogram.png",
             lambda res: f"Uncertainty | median={res.median_uncertainty:.1f} nm, mean={res.mean_uncertainty:.1f} nm, n={res.n_filtered}"),
            ("intensity_hist.png",
             lambda res: f"Intensity | mean={res.mean_intensity:.1f} ph, median={res.median_intensity:.1f} ph"),
            ("sigma_hist.png",
             lambda res: f"PSF sigma | mean={res.mean_sigma:.1f} nm, median={res.median_sigma:.1f} nm"),
            ("nnd_distribution.png",
             lambda res: f"NND | mean={res.nnd_mean:.1f} nm, median={res.nnd_median:.1f} nm"),
            ("nnd_target_subset.png",
             lambda res: f"NND target subset | n={res.nnd_target_count}, mean={res.nnd_target_mean:.1f} nm"),
            ("intensity_vs_time.png",
             lambda _: "Intensity vs time"),
            ("locs_per_frame.png",
             lambda _: "Localizations per frame"),
            ("intensity_hist_3sigma.png",
             lambda res: f"Intensity 3σ subset | n={res.n_3sigma}"),
            ("sigma_hist_3sigma.png",
             lambda res: f"PSF sigma 3σ subset | n={res.n_3sigma}"),
        ]
        return self._make_scrollable_image_tab(r, plot_files, "No plots available.")

    def _make_scrollable_image_tab(self, r, file_captions: list, empty_msg: str) -> toga.Box:
        """Shared helper: render a list of (filename, caption_fn) into a scroll container."""
        outer = toga.Box(style=Pack(direction=COLUMN, flex=1))

        if r.output_dir:
            def _make_export_handler(d: Path, n: str):
                async def _handler(widget: toga.Widget) -> None:
                    await self._export_dataset_zip(d, n)
                return _handler

            export_btn = toga.Button(
                "Export all plots as ZIP…",
                on_press=_make_export_handler(r.output_dir, r.name),
                style=Pack(margin=8))
            outer.add(export_btn)

        scroll_box = toga.Box(style=Pack(direction=COLUMN, margin=8))
        has_any = False
        if r.output_dir:
            for fname, caption_fn in file_captions:
                path = r.output_dir / fname
                if not path.exists():
                    continue
                try:
                    img = toga.Image(str(path))
                    iw, ih = img.size
                    display_w = 920
                    display_h = int(ih * display_w / iw) if iw > 0 else 600
                    iv = toga.ImageView(img, style=Pack(width=display_w,
                                                        height=display_h,
                                                        margin_bottom=4))
                    scroll_box.add(iv)
                except Exception:
                    pass
                try:
                    caption = caption_fn(r)
                except Exception:
                    caption = fname
                scroll_box.add(toga.Label(caption, style=Pack(
                    color="#546e7a", font_size=9, margin_bottom=12)))
                has_any = True

        if not has_any:
            scroll_box.add(toga.Label(empty_msg,
                                      style=Pack(color="#9e9e9e", margin=16)))

        outer.add(toga.ScrollContainer(content=scroll_box, style=Pack(flex=1)))
        return outer

    def _make_comparison_tabs(self, results, comp_dir: Path | None) -> toga.OptionContainer:
        tabs = toga.OptionContainer(style=Pack(flex=1))
        tabs.content.append(toga.OptionItem("Stats", self._make_comparison_stats_box(results)))
        tabs.content.append(toga.OptionItem("Images", self._make_comparison_images_box(comp_dir)))
        return tabs

    def _make_comparison_stats_box(self, results) -> toga.ScrollContainer:
        import math

        box = toga.Box(style=Pack(direction=COLUMN, margin=12, background_color="#ffffff"))
        box.add(toga.Label("Cross-dataset comparison",
                            style=Pack(color="#1a237e", font_size=12, margin_bottom=10)))

        def fmt(v) -> str:
            if isinstance(v, float) and math.isnan(v):
                return "N/A"
            if isinstance(v, float):
                return f"{v:.2f}"
            return str(v)

        headers = ["Dataset", "n_filtered", "unc_mean", "unc_median", "NND_mean", "NND_med", "intensity_med"]
        hdr_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        widths = [180, 90, 90, 90, 90, 90, 110]
        for h, w in zip(headers, widths):
            hdr_row.add(toga.Label(h, style=Pack(width=w, color="#1565C0",
                                                  font_size=9, font_weight="bold")))
        box.add(hdr_row)

        for r in results:
            row_box = toga.Box(style=Pack(direction=ROW, margin_bottom=3))
            vals = [r.name, fmt(r.n_filtered), fmt(r.mean_uncertainty),
                    fmt(r.median_uncertainty), fmt(r.nnd_mean),
                    fmt(r.nnd_median), fmt(r.median_intensity)]
            for v, w in zip(vals, widths):
                row_box.add(toga.Label(str(v), style=Pack(width=w, color="#212121", font_size=9)))
            box.add(row_box)

        return toga.ScrollContainer(content=box, style=Pack(flex=1))

    def _make_comparison_images_box(self, comp_dir: Path | None) -> toga.Box:
        outer = toga.Box(style=Pack(direction=COLUMN, flex=1))
        scroll_box = toga.Box(style=Pack(direction=COLUMN, margin=8))

        comp_plots = [
            ("n_filtered_comparison.png", "Localizations per dataset"),
            ("mean_uncertainty_comparison.png", "Mean localization uncertainty (nm)"),
            ("median_intensity_comparison.png", "Median intensity (photon)"),
            ("mean_sigma_comparison.png", "Mean PSF sigma (nm)"),
            ("nnd_mean_comparison.png", "Mean NND (nm)"),
            ("nnd_target_mean_comparison.png", "NND target subset mean (nm)"),
            ("locs_per_frame_overlay.png", "Localizations per frame overlay"),
            ("nnd_cdf_overlay.png", "NND CDF overlay"),
            ("nnd_boxplot.png", "NND distribution — box plot"),
            ("uncertainty_boxplot.png", "Uncertainty distribution — box plot"),
            ("intensity_boxplot.png", "Intensity distribution — box plot"),
            ("sigma_boxplot.png", "PSF sigma distribution — box plot"),
        ]

        has_any = False
        if comp_dir and comp_dir.exists():
            for fname, caption in comp_plots:
                path = comp_dir / fname
                if not path.exists():
                    continue
                try:
                    img = toga.Image(str(path))
                    iw, ih = img.size
                    display_w = 920
                    display_h = int(ih * display_w / iw) if iw > 0 else 520
                    iv = toga.ImageView(img, style=Pack(width=display_w,
                                                        height=display_h,
                                                        margin_bottom=4))
                    scroll_box.add(iv)
                except Exception:
                    pass
                scroll_box.add(toga.Label(caption, style=Pack(
                    color="#546e7a", font_size=9, margin_bottom=12)))
                has_any = True

        if not has_any:
            scroll_box.add(toga.Label(
                "No comparison plots found. Run analysis with multiple datasets.",
                style=Pack(color="#9e9e9e", margin=16)))

        outer.add(toga.ScrollContainer(content=scroll_box, style=Pack(flex=1)))
        return outer

    # ── File / dir openers ────────────────────────────────────────────────────

    def _open_file(self, path: Path) -> None:
        if sys.platform == "win32":
            os.startfile(str(path))
        elif sys.platform == "darwin":
            import subprocess
            subprocess.Popen(["open", str(path)], close_fds=True, start_new_session=True)
        else:
            import subprocess
            subprocess.Popen(["xdg-open", str(path)], close_fds=True, start_new_session=True)

    def _open_dir(self, path: Path) -> None:
        if path and path.exists():
            if sys.platform == "win32":
                os.startfile(str(path))
            elif sys.platform == "darwin":
                import subprocess
                subprocess.Popen(["open", str(path)], close_fds=True, start_new_session=True)
            else:
                import subprocess
                subprocess.Popen(["xdg-open", str(path)], close_fds=True, start_new_session=True)

    def _open_results(self, widget: toga.Widget) -> None:
        self._open_dir(self._output_dir)

    # ── ZIP export ────────────────────────────────────────────────────────────

    async def _export_dataset_zip(self, dataset_dir: Path, name: str) -> None:
        save_path = await self.main_window.dialog(
            toga.SaveFileDialog(
                title=f"Export {name} plots as ZIP",
                suggested_filename=f"{name}_plots.zip",
                file_types=["zip"],
            ))
        if save_path is None:
            return
        base = str(Path(str(save_path)).with_suffix(""))
        src = dataset_dir
        self.status_label.text = "Status: Zipping…"
        loop = asyncio.get_event_loop()

        def _zip_worker() -> None:
            try:
                shutil.make_archive(base, "zip", src)
                async def _done() -> None:
                    self.status_label.text = "Status: ZIP exported"
                loop.call_soon_threadsafe(asyncio.ensure_future, _done())
            except Exception as exc:
                err_msg = str(exc)
                async def _err() -> None:
                    self.status_label.text = "Status: ZIP failed"
                    await self.main_window.dialog(toga.ErrorDialog("ZIP error", err_msg))
                loop.call_soon_threadsafe(asyncio.ensure_future, _err())

        threading.Thread(target=_zip_worker, daemon=True).start()

    async def _export_zip(self, widget: toga.Widget) -> None:
        if not self._output_dir or not self._output_dir.exists():
            await self.main_window.dialog(
                toga.ErrorDialog("Nothing to export", "Run an analysis first."))
            return

        save_path = await self.main_window.dialog(
            toga.SaveFileDialog(
                title="Export results as ZIP",
                suggested_filename="thunderstorm_results.zip",
                file_types=["zip"],
            ))
        if save_path is None:
            return

        save_path = Path(str(save_path))
        base = str(save_path.with_suffix(""))
        src = self._output_dir
        self.status_label.text = "Status: Zipping…"
        self.export_btn.enabled = False

        loop = asyncio.get_event_loop()

        def _zip_worker() -> None:
            try:
                shutil.make_archive(base, "zip", src)
                async def _done() -> None:
                    self.status_label.text = "Status: ZIP exported"
                    self.export_btn.enabled = True
                loop.call_soon_threadsafe(asyncio.ensure_future, _done())
            except Exception as exc:
                err_msg = str(exc)
                async def _err() -> None:
                    self.status_label.text = "Status: ZIP failed"
                    self.export_btn.enabled = True
                    await self.main_window.dialog(
                        toga.ErrorDialog("ZIP error", err_msg))
                loop.call_soon_threadsafe(asyncio.ensure_future, _err())

        threading.Thread(target=_zip_worker, daemon=True).start()


def main() -> ThunderSTORMAnalyzer:
    return ThunderSTORMAnalyzer(
        "ThunderSTORM Analyzer",
        "com.thunderstormanalyzer.thunderstormanalyzer",
        icon="resources/icons/thunderstormanalyzer",
    )
