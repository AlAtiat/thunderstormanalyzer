"""ThunderSTORM Origami Analyzer — BeeWare/Toga GUI application."""
from __future__ import annotations

import asyncio
import os
import shutil
import sys
import tempfile
import threading
from pathlib import Path

import toga
from toga.style.pack import COLUMN, ROW, Pack

from .models import ColumnConfig, DatasetEntry, PlotConfig, QcParams
from .runner import AnalysisRunner

# NOTE: `.pipeline` (which imports locan/matplotlib/numpy/pandas) is intentionally
# NOT imported at module top — that would block app startup for several seconds.
# It is imported lazily where needed (protocol auto-fill below) and inside the
# background worker thread in runner.py.


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
        # Guard: while we write the auto bin into qc_bin_size, its on_change must not
        # re-trigger the recompute (would recurse). Set True around the programmatic write.
        self._syncing_bin: bool = False



        
        
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

        # Optics fields stacked 2×2: (Camera px, Objective mag) over (Visualization mag, Bin size).
        optics_row1 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        optics_row1.add(toga.Label("Camera pixel size (nm):", style=lbl_style))
        self.qc_camera_px = toga.TextInput(value="6500", style=Pack(width=65),
                                            on_change=self._update_pixel_size_label)
        optics_row1.add(self.qc_camera_px)
        # Objective magnification (physical) → sets the effective pixel size.
        optics_row1.add(toga.Label("  Objective mag (×):", style=lbl_style))
        self.qc_magnification = toga.TextInput(value="100", style=Pack(width=65),
                                               on_change=self._update_pixel_size_label)
        optics_row1.add(self.qc_magnification)

        optics_row2 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        # Visualization magnification (render up-sampling) → feeds the SR bin size.
        optics_row2.add(toga.Label("Visualization mag (×):", style=lbl_style))
        self.qc_visualization_mag = toga.TextInput(value="5", style=Pack(width=65),
                                                    on_change=self._update_pixel_size_label)
        optics_row2.add(self.qc_visualization_mag)
        # SR render bin: shows the live auto value (effective px ÷ visualization mag).
        # Its on_change only refreshes the summary label from whatever is typed — it never
        # recomputes/overwrites the field, so a manual value is not clobbered. The other
        # optics fields (camera/obj/vis) drive the auto-recompute that DOES write this field.
        optics_row2.add(toga.Label("  Bin size (nm):", style=lbl_style))
        self.qc_bin_size = toga.TextInput(value="", style=Pack(width=65),
                                          on_change=self._update_bin_label_from_field)
        optics_row2.add(self.qc_bin_size)

        px_label_row = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        # effective pixel = camera ÷ objective mag;  render bin = effective ÷ visualization mag
        self.effective_px_label = toga.Label(
            "Effective pixel: 65.00 nm/px   ·   Render bin: 13.0 nm",
            style=Pack(color="#1565C0", font_size=9, flex=1))
        px_label_row.add(self.effective_px_label)

        # QC parameters
        qc_header = toga.Label("QC Parameters", style=Pack(
            color="#1565C0", font_size=10, margin_top=6, margin_bottom=4))

        qc_row1 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row1.add(toga.Label("Max sigma (nm):", style=lbl_style))
        self.qc_max_sigma = toga.TextInput(value="768.0", style=Pack(width=65))
        qc_row1.add(self.qc_max_sigma)

        qc_row2 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row2.add(toga.Label("Max unc (nm):", style=lbl_style))
        self.qc_max_unc = toga.TextInput(value="40.0", style=Pack(width=65))
        qc_row2.add(self.qc_max_unc)
        qc_row2.add(toga.Label("  Min intensity:", style=lbl_style))
        self.qc_min_intensity = toga.TextInput(value="1000.0", style=Pack(width=65))
        qc_row2.add(self.qc_min_intensity)

        # One spacing value drives BOTH the structure geometry and the NND-target band.
        qc_row4 = toga.Box(style=Pack(direction=ROW, margin_bottom=4))
        qc_row4.add(toga.Label("Spacing / NND (nm):", style=lbl_style))
        self.qc_spacing = toga.TextInput(value="80.0", style=Pack(width=65),
                                         on_change=self._update_pixel_size_label)
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
        self.add(optics_row1)
        self.add(optics_row2)
        self.add(px_label_row)
        self.add(qc_header)
        self.add(qc_row1)
        self.add(qc_row2)
        self.add(qc_row4)
        self.add(remove_row)
        self.add(divider)

        # Pre-fill the bin field with the live auto value before any user edit.
        self._update_pixel_size_label()

    # ── Pixel size label ──────────────────────────────────────────────────────

    def _update_pixel_size_label(self, widget=None) -> None:
        # Re-entrancy guard: writing the auto bin below triggers qc_bin_size.on_change,
        # which calls this method again — ignore that programmatic echo.
        if self._syncing_bin:
            return
        try:
            eff = float(self.qc_camera_px.value) / float(self.qc_magnification.value)
            bin_nm = self._auto_bin(eff, self.qc_visualization_mag.value)
            eff_text = f"Effective pixel: {eff:.2f} nm/px"
        except (ValueError, ZeroDivisionError):
            eff = None
            bin_nm = None
            eff_text = "Effective pixel: — nm/px"

        def _set() -> None:
            # "Always reflect inputs": overwrite the bin field with the fresh auto value
            # (under the guard so this write doesn't recurse). A typed value is therefore
            # transient — it is replaced on the next optics/spacing change, by design.
            if bin_nm is not None:
                self._syncing_bin = True
                try:
                    self.qc_bin_size.value = f"{bin_nm:.1f}"
                finally:
                    self._syncing_bin = False
                self.effective_px_label.text = f"{eff_text}   ·   Render bin: {bin_nm:.1f} nm"
            else:
                self.effective_px_label.text = f"{eff_text}   ·   Render bin: — nm"
        # Setting widgets relayouts the window, which would scroll the dataset list back
        # to the top — keep the user's scroll position.
        self._app._keep_scroll(_set)

    def _update_bin_label_from_field(self, widget=None) -> None:
        """Refresh the summary label from the bin field's current (possibly manual) value.

        Label-only: never writes ``qc_bin_size`` (so a typed value is not clobbered). No-op
        during the guarded auto-write, which sets the label itself in
        ``_update_pixel_size_label``.
        """
        if self._syncing_bin:
            return
        try:
            eff = float(self.qc_camera_px.value) / float(self.qc_magnification.value)
            eff_text = f"Effective pixel: {eff:.2f} nm/px"
            bin_nm = self._current_bin(eff)
            text = f"{eff_text}   ·   Render bin: {bin_nm:.1f} nm"
        except (ValueError, ZeroDivisionError):
            text = "Effective pixel: — nm/px   ·   Render bin: — nm"
        self._app._keep_scroll(
            lambda: setattr(self.effective_px_label, "text", text))

    @staticmethod
    def _auto_bin(effective_px_nm: float, vis_mag_value) -> float:
        """Auto SR render bin size = effective pixel ÷ visualization magnification.

        This is ThunderSTORM's standard render bin. For coarser/smoother dots lower the
        visualization magnification (a smaller vis mag → larger bin). Floored at 1 nm to
        avoid absurdly fine/slow renders; falls back to 20 nm when the magnification is
        missing/non-positive or the result is not a finite positive number.
        """
        try:
            vis = float(vis_mag_value)
            bin_nm = effective_px_nm / vis
            if vis > 0 and bin_nm > 0 and bin_nm != float("inf"):
                return max(1.0, bin_nm)
        except (ValueError, TypeError, ZeroDivisionError):
            pass
        return 20.0

    def _current_bin(self, effective_px_nm: float) -> float:
        """The bin size to run with: the value shown in the field, else the auto value.

        The field normally mirrors ``_auto_bin``; this also honours a manual edit that
        hasn't been overwritten by a later optics change yet.
        """
        raw = (self.qc_bin_size.value or "").strip()
        if raw:
            try:
                val = float(raw)
                if val > 0:
                    return val
            except ValueError:
                pass
        return self._auto_bin(effective_px_nm, self.qc_visualization_mag.value)

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
        # Read only the header row (off the UI thread) — fast, no locan, no full parse.
        headers = await asyncio.to_thread(self._read_csv_headers, self._csv_path)
        self._csv_format = "thunderstorm"
        if headers:
            self._app._update_col_pickers(headers)
        # Auto-fill Max sigma / Max uncertainty from the data's 95th percentile (off the
        # UI thread, mirroring the Fiji plugin). Fields stay editable; they populate a
        # moment after the dialog closes so browsing stays responsive on large files.
        p95_sigma, p95_unc = await asyncio.to_thread(
            self._scan_sigma_unc_p95, self._csv_path)
        self._apply_p95(p95_sigma, p95_unc)

    def _apply_p95(self, p95_sigma: float | None, p95_unc: float | None) -> None:
        """Set Max sigma / Max uncertainty from the CSV's 95th percentiles (editable).

        Only overwrites a field when its percentile is a positive number, so a missing
        column or empty data leaves the existing value (protocol/default) untouched.
        """
        def _set() -> None:
            if p95_sigma and p95_sigma > 0:
                self.qc_max_sigma.value = f"{p95_sigma:.1f}"
            if p95_unc and p95_unc > 0:
                self.qc_max_unc.value = f"{p95_unc:.1f}"
        self._app._keep_scroll(_set)

    @staticmethod
    def _scan_sigma_unc_p95(path: Path) -> tuple[float | None, float | None]:
        """Return the 95th percentile of the CSV's sigma and uncertainty columns.

        Stdlib ``csv`` only (no ``locan`` / full LocData load). Columns are matched by
        case-insensitive substring — "sigma" (excluding background_sigma / bkgstd) and
        "uncertainty" — mirroring the Fiji plugin's ``autoFillFromCsv``. Returns
        ``(None, None)`` on any error or when a column has no numeric data.
        """
        import csv

        def _p95(values: list[float]) -> float | None:
            if not values:
                return None
            values.sort()
            idx = min(int(len(values) * 0.95), len(values) - 1)
            return values[idx]

        try:
            with open(path, newline="", encoding="utf-8", errors="replace") as fh:
                reader = csv.reader(fh)
                header = next(reader, None)
                if not header:
                    return (None, None)
                sigma_i = uncert_i = -1
                for i, name in enumerate(header):
                    low = name.strip().lower()
                    if sigma_i < 0 and "sigma" in low and "background" not in low \
                            and "bkgstd" not in low:
                        sigma_i = i
                    if uncert_i < 0 and "uncertainty" in low:
                        uncert_i = i
                if sigma_i < 0 and uncert_i < 0:
                    return (None, None)
                sigmas: list[float] = []
                uncerts: list[float] = []
                for row in reader:
                    if 0 <= sigma_i < len(row):
                        try:
                            sigmas.append(float(row[sigma_i]))
                        except ValueError:
                            pass
                    if 0 <= uncert_i < len(row):
                        try:
                            uncerts.append(float(row[uncert_i]))
                        except ValueError:
                            pass
            return (_p95(sigmas), _p95(uncerts))
        except Exception:
            return (None, None)

    @staticmethod
    def _read_csv_headers(path: Path) -> list[str]:
        """Read just the CSV header line and map to canonical names.

        Uses only the stdlib ``csv`` module — no ``locan`` import and no full-file
        parse — so browsing a large CSV is instant. The heavy ``locan`` load happens
        later, on the analysis worker thread.
        """
        import csv
        from .columns import canonical_headers
        try:
            with open(path, newline="", encoding="utf-8", errors="replace") as fh:
                first_row = next(csv.reader(fh), [])
            return canonical_headers(first_row) if first_row else []
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
        # Lazy import: pulls in .pipeline (locan/matplotlib/etc.) only when a
        # protocol is actually loaded, keeping app startup fast.
        from .pipeline import _load_protocol, qc_from_protocol
        protocol = _load_protocol(self._protocol_path)
        if not protocol:
            return
        qc = qc_from_protocol(protocol)

        def _set() -> None:
            # Camera pixel: protocol pixelSize is the effective pixel, so camera=pixel, mag=1
            self.qc_camera_px.value = f"{qc.pixel_size_nm:.1f}"
            self.qc_magnification.value = "1"
            self.qc_max_sigma.value = f"{qc.max_sigma_nm:.1f}"
            self.qc_max_unc.value = f"{qc.max_uncertainty_nm:.1f}"
            self.qc_min_intensity.value = f"{qc.min_intensity:.1f}"
        self._app._keep_scroll(_set)
        self._update_pixel_size_label()

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
            pixel_nm = d.pixel_size_nm
            cam_nm = d.camera_pixel_size_nm
            mag = d.magnification
        vis_mag = self._float(self.qc_visualization_mag, d.visualization_magnification)
        bin_nm = self._current_bin(pixel_nm)
        qc = QcParams(
            pixel_size_nm=pixel_nm,
            camera_pixel_size_nm=cam_nm,
            magnification=mag,
            max_sigma_nm=self._float(self.qc_max_sigma, d.max_sigma_nm),
            max_uncertainty_nm=self._float(self.qc_max_unc, d.max_uncertainty_nm),
            min_intensity=self._float(self.qc_min_intensity, d.min_intensity),
            dna_origami_spacing_nm=self._float(self.qc_spacing, d.dna_origami_spacing_nm),
            spacing_tol_nm=self._float(self.qc_spacing_tol, d.spacing_tol_nm),
            visualization_magnification=vis_mag,
            render_bin_size_nm=bin_nm,
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

        self.main_window = toga.MainWindow(title="ThunderSTORM Origami Analyzer",
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
            # Brief minimum splash so the logo is visible; the heavy UI build below
            # is now lightweight (no eager locan/matplotlib import), so this is the
            # only intentional delay. Heavy libs load lazily on first analysis.
            await asyncio.sleep(1.2)

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
            self._max_structures: int = 10
            self._min_blink_cycles = 2
            self._min_blink_frames = 5
            self._blink_gap_frames = 2
            self._dbscan_min_samples = 3
            self._collinear_angle_deg = 30.0
            self._assume_photons = False

            # ── Sidebar ───────────────────────────────────────────────────────
            sidebar = toga.Box(style=Pack(direction=COLUMN, margin=12, width=200,
                                           background_color="#e8eaf6"))

            sidebar.add(toga.Label("ThunderSTORM\nOrigami Analyzer",
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

            self.check_updates_btn = toga.Button("Check for Updates…",
                                                 on_press=self._check_updates,
                                                 style=Pack(margin_bottom=8))
            sidebar.add(self.check_updates_btn)

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

            self._dataset_scroll = toga.ScrollContainer(
                content=self.dataset_container,
                style=Pack(flex=1, background_color="#fafafa"),
            )

            self.log_area = toga.MultilineTextInput(
                readonly=True, value="",
                style=Pack(height=160, font_family="monospace", font_size=9,
                           background_color="#fafafa", color="#333333"),
            )

            datasets_tab = toga.Box(style=Pack(direction=COLUMN, flex=1))
            datasets_tab.add(self._dataset_scroll)
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

        max_structures_row = toga.Box(style=Pack(direction=ROW, margin_bottom=8))
        max_structures_row.add(toga.Label("Max structures to show:",
                                          style=Pack(width=280, color="#212121", margin_right=8)))
        self._max_structures_input = toga.TextInput(value="10", style=Pack(width=60))
        max_structures_row.add(self._max_structures_input)
        box.add(max_structures_row)

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

        # Render bin size is not set here — it is per dataset, auto-derived from each
        # dataset's effective pixel, visualization magnification and spacing (and
        # manually overridable) in that dataset's Camera / Optics section.
        adv_row3 = toga.Box(style=Pack(direction=ROW, margin_bottom=6))
        adv_row3.add(toga.Label("Collinear angle (°):", style=adv_lbl))
        self._collinear_angle_input = toga.TextInput(value="30.0", style=adv_inp)
        adv_row3.add(self._collinear_angle_input)
        box.add(adv_row3)

        # Intensity calibration: the app reads the intensity column as-is (ThunderSTORM
        # already applied/skipped the gain). Off → label intensity as a.u. (default);
        # On → trust it is photon-calibrated and label/report it as photons.
        calib_row = toga.Box(style=Pack(direction=ROW, margin_bottom=2))
        self._assume_photons_switch = toga.Switch(
            "Real calibration (intensity is photons)", value=False, style=Pack(flex=1))
        calib_row.add(self._assume_photons_switch)
        box.add(calib_row)
        box.add(toga.Label(
            "Off → intensity shown as a.u. (uncalibrated);  On → trusts the protocol's "
            "photon calibration.",
            style=Pack(color="#546e7a", font_size=9, margin_bottom=6)))

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

    def _keep_scroll(self, fn) -> None:
        """Run ``fn``, restoring the dataset list's scroll position afterwards.

        Toga re-lays out the whole window whenever a widget/label value changes, which
        snaps the dataset ``ScrollContainer`` back to the top. Saving and restoring the
        vertical position keeps the user where they were while editing. Fully guarded so
        it degrades to a plain ``fn()`` if a backend lacks the scroll-position API.
        """
        scroll = getattr(self, "_dataset_scroll", None)
        pos = None
        if scroll is not None:
            try:
                pos = scroll.vertical_position
            except Exception:
                pos = None
        fn()
        if scroll is not None and pos is not None:
            try:
                scroll.vertical_position = pos
            except Exception:
                pass

    def _update_col_pickers(self, headers: list[str]) -> None:
        """Replace TextInput col fields with Selection dropdowns populated from CSV headers."""
        def _swap() -> None:
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
        self._keep_scroll(_swap)

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
            self._max_structures = max(1, int(self._max_structures_input.value))
        except (ValueError, TypeError):
            self._max_structures = 10
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
            self._collinear_angle_deg = float(self._collinear_angle_input.value)
        except (ValueError, TypeError):
            self._collinear_angle_deg = 30.0
        self._assume_photons = bool(self._assume_photons_switch.value)

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
            entry.qc.max_structures = self._max_structures
            entry.qc.min_blink_cycles = self._min_blink_cycles
            entry.qc.min_blink_frames = self._min_blink_frames
            entry.qc.blink_gap_frames = self._blink_gap_frames
            entry.qc.dbscan_min_samples = self._dbscan_min_samples
            entry.qc.collinear_angle_deg = self._collinear_angle_deg
            entry.qc.assume_photons = self._assume_photons
            # render_bin_size_nm is set per-dataset in DatasetRow.to_entry (from the
            # dataset's Visualization magnification), so it is not overridden here.

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
        tabs.content.append(toga.OptionItem("Origami Viewer", self._make_origami_viewer_box(r)))
        return tabs

    def _make_origami_viewer_box(self, r) -> toga.Box:
        """Render the origami structure carousel, or explain why it's not available."""
        outer = toga.Box(style=Pack(direction=COLUMN, flex=1))
        if r.viewer_html_path and r.viewer_html_path.exists():
            html = r.viewer_html_path.read_text(encoding="utf-8")
            wv = toga.WebView(style=Pack(flex=1))
            wv.set_content("http://localhost/", html)
            outer.add(wv)
        elif r.error:
            outer.add(toga.Label(
                "Analysis failed — no origami viewer.",
                style=Pack(color="#c62828", margin=16)))
        else:
            outer.add(toga.Label(
                "Origami Viewer not available.\n"
                "Enable 'Blinking showcase' in the Configuration tab and re-run.",
                style=Pack(color="#9e9e9e", margin=16)))
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

        iu = "photon" if self._assume_photons else "a.u."
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
            (f"Mean intensity ({iu})", fmt(r.mean_intensity)),
            (f"Median intensity ({iu})", fmt(r.median_intensity)),
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
        iu = "photon" if self._assume_photons else "a.u."
        plot_files = [
            ("uncertainty_histogram.png",
             lambda res: f"Uncertainty | median={res.median_uncertainty:.1f} nm, mean={res.mean_uncertainty:.1f} nm, n={res.n_filtered}"),
            ("intensity_hist.png",
             lambda res: f"Intensity | mean={res.mean_intensity:.1f} {iu}, median={res.median_intensity:.1f} {iu}"),
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

        iu = "photon" if self._assume_photons else "a.u."
        comp_plots = [
            ("n_filtered_comparison.png", "Localizations per dataset"),
            ("mean_uncertainty_comparison.png", "Mean localization uncertainty (nm)"),
            ("median_intensity_comparison.png", f"Median intensity ({iu})"),
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

    # ── Update check ──────────────────────────────────────────────────────────

    async def _check_updates(self, widget: toga.Widget) -> None:
        """Manually check GitHub Releases for a newer version (off the UI thread)."""
        from . import updates

        self.check_updates_btn.enabled = False
        try:
            release = await asyncio.to_thread(updates.fetch_latest_release)
        finally:
            self.check_updates_btn.enabled = True

        current = updates.current_version()

        if release is None:
            await self.main_window.dialog(toga.InfoDialog(
                "Check for Updates",
                "Couldn't reach the update server.\n"
                "Check your internet connection and try again."))
            return

        latest_tag = str(release.get("tag_name", "")).strip()
        if not latest_tag or not updates.is_newer(latest_tag, current):
            await self.main_window.dialog(toga.InfoDialog(
                "Check for Updates",
                f"You're up to date.\nInstalled version: {current}"))
            return

        latest_display = latest_tag.lstrip("vV")
        open_page = await self.main_window.dialog(toga.QuestionDialog(
            "Update Available",
            f"Version {latest_display} is available (you have {current}).\n\n"
            "Open the download page?"))
        if open_page:
            import webbrowser
            url = str(release.get("html_url") or updates.RELEASES_PAGE)
            webbrowser.open(url)

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
                suggested_filename="origami_results.zip",
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
        "ThunderSTORM Origami Analyzer",
        "com.thunderstormanalyzer.thunderstormanalyzer",
        icon="resources/icons/thunderstormanalyzer",
    )
