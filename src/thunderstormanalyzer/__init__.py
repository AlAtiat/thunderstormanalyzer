from importlib.metadata import version, PackageNotFoundError
from .app import ThunderSTORMAnalyzer, main

try:
    __version__ = version("thunderstormanalyzer")
except PackageNotFoundError:
    __version__ = "dev"

__all__ = ["ThunderSTORMAnalyzer", "main", "__version__"]
