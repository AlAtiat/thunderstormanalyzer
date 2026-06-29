from __future__ import annotations

import threading
import traceback
from pathlib import Path
from typing import Callable

from .models import AnalysisResult, ColumnConfig, DatasetEntry, PlotConfig


class AnalysisRunner:
    """Runs the analysis pipeline on a background thread."""

    def __init__(self) -> None:
        self._thread: threading.Thread | None = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def run(
        self,
        datasets: list[DatasetEntry],
        output_dir: Path,
        on_log: Callable[[str], None],
        on_done: Callable[[list[AnalysisResult]], None],
        col_config: ColumnConfig | None = None,
        plot_config: PlotConfig | None = None,
    ) -> None:
        if self.running:
            return

        if col_config is None:
            col_config = ColumnConfig()
        if plot_config is None:
            plot_config = PlotConfig()

        def _worker() -> None:
            # Heavy imports (locan, matplotlib, numpy, pandas via .pipeline) happen here,
            # on the background thread, so the GUI never blocks on first analysis.
            from .pipeline import analyze_entry, generate_comparison

            results: list[AnalysisResult] = []
            for entry in datasets:
                try:
                    on_log(f"[INFO] Starting: {entry.name}")
                    result = analyze_entry(entry, output_dir, on_log,
                                          col_config=col_config,
                                          plot_config=plot_config)
                    if result.error:
                        on_log(f"[WARN] {entry.name}: {result.error}")
                    else:
                        on_log(
                            f"[OK]  {entry.name}  "
                            f"({result.n_filtered}/{result.n_raw} locs after QC, "
                            f"uncertainty={result.mean_uncertainty:.1f} nm)"
                        )
                    results.append(result)
                except Exception:
                    on_log(f"[ERR] {entry.name}:\n{traceback.format_exc()}")

            if results and plot_config.comparison_plots:
                try:
                    on_log("[INFO] Generating comparison plots…")
                    assume_photons = bool(datasets[0].qc.assume_photons) if datasets else False
                    generate_comparison(results, output_dir / "comparison", on_log,
                                        assume_photons=assume_photons)
                except Exception:
                    on_log(f"[ERR] Comparison plots failed:\n{traceback.format_exc()}")

            on_log("[DONE] Analysis complete.")
            on_done(results)

        self._thread = threading.Thread(target=_worker, daemon=True)
        self._thread.start()
