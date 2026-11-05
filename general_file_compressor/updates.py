"""At start-up, check GitHub for a newer release. Silent when offline; never blocks the window."""
from __future__ import annotations

import json
import re
import urllib.request

from PySide6.QtCore import QObject, QThread, QTimer, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QMessageBox

REPO = "gabrydl99/General-file-compressor"


def parse_version(text: str) -> tuple:
    nums = re.findall(r"\d+", text or "")
    return tuple(int(n) for n in nums[:3]) if nums else (0,)


class _Fetch(QThread):
    found = Signal(str, str, str)   # version, page url, notes

    def __init__(self, repo: str, current: str):
        super().__init__()
        self.repo, self.current = repo, current

    def run(self) -> None:
        try:
            req = urllib.request.Request(f"https://api.github.com/repos/{self.repo}/releases/latest",
                                         headers={"Accept": "application/vnd.github+json",
                                                  "User-Agent": "update-check"})
            with urllib.request.urlopen(req, timeout=8) as r:
                data = json.load(r)
            tag = data.get("tag_name", "")
            if parse_version(tag) > parse_version(self.current):
                self.found.emit(".".join(map(str, parse_version(tag))), data.get("html_url", ""),
                                (data.get("body") or "").strip())
        except Exception:
            pass  # offline or GitHub not reachable: say nothing


class UpdateChecker(QObject):
    def __init__(self, parent, app_name: str, current: str, repo: str = REPO, delay_ms: int = 2500):
        super().__init__(parent)
        self.parent_widget, self.app_name, self.current, self.repo = parent, app_name, current, repo
        self.thread = None
        QTimer.singleShot(delay_ms, self.start)

    def start(self) -> None:
        self.thread = _Fetch(self.repo, self.current)
        self.thread.found.connect(self._show)
        self.thread.start()

    def _show(self, version: str, url: str, notes: str) -> None:
        box = QMessageBox(self.parent_widget)
        box.setWindowTitle("Update available")
        box.setIcon(QMessageBox.Icon.Information)
        box.setText(f"{self.app_name} {version} is available.\nYou have version {self.current}.")
        if notes:
            box.setDetailedText(notes[:3000])
        dl = box.addButton("Download", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Later", QMessageBox.ButtonRole.RejectRole)
        box.exec()
        if box.clickedButton() is dl and url:
            QDesktopServices.openUrl(QUrl(url))
