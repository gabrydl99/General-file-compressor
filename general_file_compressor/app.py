"""Starts the window."""
from __future__ import annotations

import os
import sys


def run() -> int:
    if os.name == "nt":
        try:  # own taskbar icon instead of the Python one
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("GeneralFileCompressor.App")
        except Exception:
            pass
    from PySide6.QtWidgets import QApplication

    from . import APP_NAME
    from . import theme as T
    from .window import MainWindow, app_icon

    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setStyle("Fusion")
    display, text = T.load_fonts()
    app.setFont(T.font(text, 13))
    app.setStyleSheet(T.stylesheet(display, text))
    app.setWindowIcon(app_icon())
    w = MainWindow(display, text)
    w.show()
    return app.exec()
