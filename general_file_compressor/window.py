"""Main window: a block layout with the file list on the left and settings on the right."""
from __future__ import annotations

import os
import subprocess
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from PySide6.QtCore import QPointF, QRectF, QSettings, QSize, Qt, QThread, QUrl, Signal
from PySide6.QtGui import (QAction, QColor, QDesktopServices, QFont, QFontMetrics, QIcon,
                           QKeySequence, QPainter, QPen, QPixmap)
from PySide6.QtWidgets import (QAbstractButton, QButtonGroup, QComboBox, QDialog,
                               QDoubleSpinBox, QFileDialog, QFrame, QGridLayout, QHBoxLayout,
                               QLabel, QMainWindow, QMenu, QProgressBar, QPushButton, QScrollArea,
                               QSizePolicy, QSlider, QStackedWidget, QVBoxLayout, QWidget)

from . import APP_NAME
from . import theme as T
from .core import (ALL_EXT, KIND_AUDIO, KIND_IMAGE, KIND_OFFICE, KIND_PDF, KIND_VIDEO, MB,
                   Cancelled, Job, Result, Settings, default_output_root, human_size, kill_all,
                   kind_of)
from .engine import compress
from .errors import diagnose

RESOLUTIONS = [("Auto", "auto"), ("Keep original", "original"), ("4K (2160p)", "2160"),
               ("1440p", "1440"), ("1080p", "1080"), ("720p", "720"), ("480p", "480"), ("360p", "360")]
FRAMERATES = [("Auto", "auto"), ("Keep original", "original"), ("60 fps", "60"), ("30 fps", "30"),
              ("25 fps", "25"), ("24 fps", "24"), ("15 fps", "15")]
VIDEO_FORMATS = [("Keep original", "keep"), ("MP4", "mp4"), ("MKV", "mkv"), ("MOV", "mov"),
                 ("WebM", "webm"), ("MP3 (audio only)", "mp3"), ("M4A (audio only)", "m4a")]
IMAGE_FORMATS = [("Keep original", "keep"), ("JPG", "jpg"), ("PNG", "png"), ("WebP", "webp")]
AUDIO_FORMATS = [("Keep original", "keep"), ("MP3", "mp3"), ("M4A", "m4a"), ("OGG", "ogg"),
                 ("Opus", "opus")]

WAITING, WORKING, DONE, FAILED = "waiting", "working", "done", "failed"


# ---------------------------------------------------------------- small pieces

def make_logo(size: int) -> QPixmap:
    pm = QPixmap(size, size)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    s = size / 64.0
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(T.COBALT))
    p.drawRoundedRect(QRectF(2 * s, 2 * s, 60 * s, 60 * s), 14 * s, 14 * s)
    p.setBrush(QColor("#FFFFFF"))
    for i, w in enumerate((40, 28, 16)):
        p.drawRoundedRect(QRectF(12 * s, (13 + i * 14) * s, w * s, 10 * s), 3 * s, 3 * s)
    p.end()
    return pm


def app_icon() -> QIcon:
    icon = QIcon()
    for sz in (16, 24, 32, 48, 64, 128, 256):
        icon.addPixmap(make_logo(sz))
    return icon


def repolish(w: QWidget) -> None:
    w.style().unpolish(w)
    w.style().polish(w)
    w.update()


class Chevron(QComboBox):
    def paintEvent(self, e) -> None:
        super().paintEvent(e)
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(QPen(QColor(T.MUTED), 1.8, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        x, y = self.width() - 18, self.height() / 2
        p.drawLine(QPointF(x - 4, y - 2), QPointF(x, y + 2))
        p.drawLine(QPointF(x, y + 2), QPointF(x + 4, y - 2))


class Toggle(QAbstractButton):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFixedSize(40, 22)

    def paintEvent(self, _e) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        on = self.isChecked()
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(T.COBALT if on else "#C5CDD9") if self.isEnabled() else QColor("#DDE2EA"))
        p.drawRoundedRect(QRectF(0, 0, 40, 22), 7, 7)
        p.setBrush(QColor("#FFFFFF"))
        p.drawRoundedRect(QRectF(21 if on else 3, 3, 16, 16), 5, 5)


class ElidedLabel(QLabel):
    def __init__(self, text: str = "", parent=None):
        super().__init__(parent)
        self._full = text
        self.setMinimumWidth(40)
        self.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        self.setText(text)

    def setText(self, text: str) -> None:  # noqa: N802
        self._full = text
        self._elide()

    def resizeEvent(self, e) -> None:
        super().resizeEvent(e)
        self._elide()

    def _elide(self) -> None:
        fm = QFontMetrics(self.font())
        super().setText(fm.elidedText(self._full, Qt.TextElideMode.ElideMiddle, max(10, self.width())))
        self.setToolTip(self._full if fm.horizontalAdvance(self._full) > self.width() else "")


def block(title: Optional[str] = None) -> tuple[QFrame, QVBoxLayout]:
    f = QFrame()
    f.setObjectName("Block")
    v = QVBoxLayout(f)
    v.setContentsMargins(18, 16, 18, 16)
    v.setSpacing(10)
    if title:
        t = QLabel(title)
        t.setObjectName("BlockTitle")
        v.addWidget(t)
    return f, v


def label(text: str, name: str = "", wrap: bool = False) -> QLabel:
    lb = QLabel(text)
    if name:
        lb.setObjectName(name)
    lb.setWordWrap(wrap)
    return lb


def button(text: str, name: str, slot=None) -> QPushButton:
    b = QPushButton(text)
    b.setObjectName(name)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    if slot:
        b.clicked.connect(slot)
    return b


def reveal(path: Path) -> None:
    try:
        if os.name == "nt":
            if path.is_dir():
                os.startfile(str(path))  # type: ignore[attr-defined]
            else:
                subprocess.Popen(["explorer", "/select,", str(path)])
        else:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(path if path.is_dir() else path.parent)))
    except Exception:
        pass


def expected_ext(path: Path, s: Settings) -> str:
    kind, ext = kind_of(path), path.suffix.lower().lstrip(".")
    if kind == KIND_VIDEO:
        if s.video_format != "keep":
            return s.video_format
        return ext if ext in ("mp4", "mkv", "mov", "webm") else "mp4"
    if kind == KIND_IMAGE:
        if s.image_format != "keep":
            return s.image_format
        return ext if ext in ("jpg", "jpeg", "png", "webp") else "jpg"
    if kind == KIND_AUDIO:
        if s.audio_format != "keep":
            return s.audio_format
        return ext if ext in ("mp3", "m4a", "ogg", "opus", "aac") else "mp3"
    return ext


# ---------------------------------------------------------------- files

DISCORD_TARGET_MB = 19  # Discord free upload limit is 20 MB (since August 2026)


@dataclass
class FileItem:
    path: Path
    size: int
    kind: str
    state: str = WAITING
    result: Optional[Result] = None
    row: Optional["FileRow"] = None


TILE_TEXT = {KIND_VIDEO: "VID", KIND_IMAGE: "IMG", KIND_PDF: "PDF", KIND_AUDIO: "AUD", KIND_OFFICE: "DOC"}


class FileRow(QFrame):
    removeRequested = Signal(object)
    whyRequested = Signal(object)
    retryRequested = Signal(object)
    menuRequested = Signal(object, object)

    def __init__(self, item: FileItem):
        super().__init__()
        self.item = item
        self.setObjectName("Row")
        color = T.KIND_COLORS.get(item.kind, T.COBALT)
        h = QHBoxLayout(self)
        h.setContentsMargins(10, 10, 8, 10)
        h.setSpacing(12)
        tile = QLabel(item.path.suffix.lstrip(".").upper()[:4] or TILE_TEXT.get(item.kind, "FILE"))
        tile.setObjectName("Tile")
        tile.setAlignment(Qt.AlignmentFlag.AlignCenter)
        tile.setFixedSize(46, 46)
        tile.setStyleSheet(f"background: {color};")
        h.addWidget(tile, 0, Qt.AlignmentFlag.AlignTop)

        mid = QVBoxLayout()
        mid.setSpacing(3)
        top = QHBoxLayout()
        top.setSpacing(8)
        self.name = ElidedLabel(item.path.name)
        self.name.setObjectName("RowName")
        top.addWidget(self.name, 1)
        self.status = label("Waiting", "RowStatus")
        top.addWidget(self.status)
        mid.addLayout(top)
        self.meta = ElidedLabel("")
        self.meta.setObjectName("RowMeta")
        mid.addWidget(self.meta)
        bottom = QHBoxLayout()
        bottom.setSpacing(8)
        self.bar = QProgressBar()
        self.bar.setRange(0, 1000)
        self.bar.setValue(0)
        self.bar.setTextVisible(False)
        self.bar.setStyleSheet(f"QProgressBar::chunk {{ background: {color}; border-radius: 3px; }}")
        bottom.addWidget(self.bar, 1)
        self.why = button("Why did it fail?", "Link", lambda: self.whyRequested.emit(self.item))
        self.why.hide()
        bottom.addWidget(self.why)
        self.retry = button("↻", "Retry", lambda: self.retryRequested.emit(self.item))
        self.retry.setToolTip("Try again with the current settings")
        self.retry.setFixedSize(26, 22)
        self.retry.hide()
        bottom.addWidget(self.retry)
        mid.addLayout(bottom)
        h.addLayout(mid, 1)

        self.remove = button("✕", "Remove", lambda: self.removeRequested.emit(self.item))
        self.remove.setToolTip("Remove from the list")
        self.remove.setFixedWidth(28)
        h.addWidget(self.remove, 0, Qt.AlignmentFlag.AlignTop)

    def set_status(self, text: str, tone: str = "") -> None:
        self.status.setText(text)
        self.status.setProperty("tone", tone)
        repolish(self.status)

    def refresh(self, s: Settings) -> None:
        it = self.item
        out = expected_ext(it.path, s).upper()
        src = it.path.suffix.lstrip(".").upper()
        fmt = f"{src} → {out}" if out != src and out else src
        if it.state == WAITING:
            if s.convert_only:
                meta = f"{human_size(it.size)}, {fmt}"
            elif s.mode == "mb":
                meta = f"{human_size(it.size)} → about {human_size(min(it.size, s.target_mb * MB))}, {fmt}"
            else:
                meta = f"{human_size(it.size)} → about {human_size(it.size * s.percent / 100)}, {fmt}"
            self.meta.setText(meta)

    def mouseReleaseEvent(self, e) -> None:
        if e.button() == Qt.MouseButton.RightButton:
            self.menuRequested.emit(self.item, e.globalPosition().toPoint())
        super().mouseReleaseEvent(e)


class DropZone(QFrame):
    clicked = Signal()

    def __init__(self, display: str):
        super().__init__()
        self.setObjectName("Drop")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        v = QVBoxLayout(self)
        v.setAlignment(Qt.AlignmentFlag.AlignCenter)
        v.setSpacing(8)
        icon = QLabel()
        icon.setPixmap(self._icon(64))
        icon.setAlignment(Qt.AlignmentFlag.AlignCenter)
        v.addWidget(icon)
        t = label("Drop files or folders here", "DropTitle")
        t.setAlignment(Qt.AlignmentFlag.AlignCenter)
        v.addWidget(t)
        sub = label("Videos, photos, PDFs, audio, Word, PowerPoint and Excel", "Muted")
        sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        v.addWidget(sub)
        b = button("Choose files", "Secondary", self.clicked.emit)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(b)
        row.addStretch(1)
        v.addSpacing(6)
        v.addLayout(row)

    @staticmethod
    def _icon(size: int) -> QPixmap:
        pm = QPixmap(size, size)
        pm.fill(Qt.GlobalColor.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        cols = [T.KIND_COLORS[k] for k in (KIND_VIDEO, KIND_IMAGE, KIND_PDF, KIND_AUDIO)]
        cell = size / 2 - 3
        for i, c in enumerate(cols):
            x, y = (i % 2) * (cell + 6), (i // 2) * (cell + 6)
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(QColor(c))
            p.drawRoundedRect(QRectF(x, y, cell, cell), 7, 7)
        p.end()
        return pm

    def mouseReleaseEvent(self, e) -> None:
        if e.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit()


class DiagnosisDialog(QDialog):
    def __init__(self, item: FileItem, parent=None):
        super().__init__(parent)
        msg = item.result.message if item.result else "Unknown error"
        d = diagnose(msg)
        self.setWindowTitle("Why it failed")
        self.setMinimumWidth(460)
        v = QVBoxLayout(self)
        v.setContentsMargins(22, 20, 22, 18)
        v.setSpacing(8)
        v.addWidget(label(d.title, "BlockTitle", True))
        v.addWidget(label(item.path.name, "Small", True))
        v.addSpacing(4)
        v.addWidget(label("What happened", "FieldLabel"))
        v.addWidget(label(msg, wrap=True))
        v.addSpacing(4)
        v.addWidget(label("Possible causes", "FieldLabel"))
        for c in d.causes:
            v.addWidget(label(f"•  {c}", wrap=True))
        v.addSpacing(4)
        v.addWidget(label("What to try", "FieldLabel"))
        for tip in d.tips:
            v.addWidget(label(f"•  {tip}", wrap=True))
        v.addSpacing(10)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(button("Close", "Primary", self.accept))
        v.addLayout(row)


# ---------------------------------------------------------------- worker

class Worker(QThread):
    started_item = Signal(object)
    progressed = Signal(object, float, str)
    finished_item = Signal(object, object)
    aborted_item = Signal(object)

    def __init__(self, items: list[FileItem], settings: Settings):
        super().__init__()
        self.items, self.settings = items, settings
        self.cancel_event = threading.Event()
        self.job: Optional[Job] = None

    def stop(self) -> None:
        self.cancel_event.set()
        if self.job:
            kill_all(self.job)

    def run(self) -> None:
        for it in self.items:
            if self.cancel_event.is_set():
                break
            self.started_item.emit(it)
            self.job = Job(lambda f, t, it=it: self.progressed.emit(it, f, t), self.cancel_event)
            try:
                result = compress(it.path, self.settings, self.job)
            except Cancelled:
                self.aborted_item.emit(it)
                break
            self.finished_item.emit(it, result)


def gather(paths: list[Path]) -> tuple[list[Path], int]:
    found: list[Path] = []
    skipped = 0
    for pth in paths:
        if pth.is_dir():
            for f in sorted(pth.rglob("*")):
                if f.is_file() and f.suffix.lower() in ALL_EXT:
                    found.append(f)
        elif pth.is_file():
            if pth.suffix.lower() in ALL_EXT:
                found.append(pth)
            else:
                skipped += 1
    return found, skipped


# ---------------------------------------------------------------- window

class MainWindow(QMainWindow):
    def __init__(self, display: str, text: str):
        super().__init__()
        self.display = display
        self.items: list[FileItem] = []
        self.worker: Optional[Worker] = None
        self.output_root: Optional[Path] = None
        self.prefs = QSettings("GeneralFileCompressor", "GeneralFileCompressor")
        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(app_icon())
        self.setMinimumSize(QSize(1020, 680))
        self.resize(1180, 780)
        self.setAcceptDrops(True)
        from . import __version__
        from .updates import UpdateChecker
        self.updates = UpdateChecker(self, APP_NAME, __version__)

        root = QWidget()
        root.setObjectName("Root")
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(22, 18, 22, 20)
        outer.setSpacing(14)

        # header
        head = QHBoxLayout()
        head.setSpacing(12)
        logo = QLabel()
        logo.setPixmap(make_logo(44))
        head.addWidget(logo)
        titles = QVBoxLayout()
        titles.setSpacing(0)
        titles.addWidget(label(APP_NAME, "AppTitle"))
        titles.addWidget(label("Compress and convert videos, photos, PDFs, audio and Office files. "
                               "Everything stays on this PC.", "AppSub"))
        head.addLayout(titles, 1)
        self.btn_add = button("Add files", "Secondary", self.browse)
        self.btn_folder = button("Add a folder", "Secondary", self.browse_folder)
        head.addWidget(self.btn_add)
        head.addWidget(self.btn_folder)
        outer.addLayout(head)

        body = QHBoxLayout()
        body.setSpacing(14)
        outer.addLayout(body, 1)
        left = QVBoxLayout()
        left.setSpacing(14)
        left.addWidget(self._files_block(), 1)
        left.addWidget(self._save_block())
        body.addLayout(left, 1)
        body.addWidget(self._settings_column())
        outer.addWidget(self._action_bar())

        act = QAction(self)
        act.setShortcut(QKeySequence.StandardKey.Open)
        act.triggered.connect(self.browse)
        self.addAction(act)
        for b in self.findChildren(QPushButton):
            b.setFocusPolicy(Qt.FocusPolicy.TabFocus)  # focus ring only when using the keyboard
        self._load_prefs()
        self.refresh()
        self.setFocus()

    # ------------------------------------------------------------ blocks
    def _files_block(self) -> QFrame:
        f, v = block()
        self.files_frame = f
        head = QHBoxLayout()
        head.addWidget(label("Files", "BlockTitle"))
        self.lbl_count = label("", "Muted")
        head.addWidget(self.lbl_count)
        head.addStretch(1)
        self.btn_clear = button("Clear list", "Secondary", self.clear)
        head.addWidget(self.btn_clear)
        v.addLayout(head)

        self.files_stack = QStackedWidget()
        self.drop = DropZone(self.display)
        self.drop.clicked.connect(self.browse)
        self.files_stack.addWidget(self.drop)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        inner = QWidget()
        self.rows = QVBoxLayout(inner)
        self.rows.setContentsMargins(0, 0, 4, 0)
        self.rows.setSpacing(8)
        self.rows.addStretch(1)
        self.scroll.setWidget(inner)
        self.files_stack.addWidget(self.scroll)
        v.addWidget(self.files_stack, 1)
        self.lbl_drop_hint = label("Drop more files anywhere in the window. Right-click a file for more options.", "Small")
        v.addWidget(self.lbl_drop_hint)
        return f

    def _settings_column(self) -> QScrollArea:
        col = QWidget()
        v = QVBoxLayout(col)
        v.setContentsMargins(0, 0, 0, 0)
        v.setSpacing(14)

        # size
        f, b = block("How small")
        seg = QFrame()
        seg.setObjectName("Segment")
        sh = QHBoxLayout(seg)
        sh.setContentsMargins(3, 3, 3, 3)
        sh.setSpacing(3)
        self.mode_group = QButtonGroup(self)
        self.seg_buttons = []
        for i, txt in enumerate(("Percentage", "Size in MB", "Convert only")):
            sb = button(txt, "Seg")
            sb.setCheckable(True)
            self.mode_group.addButton(sb, i)
            self.seg_buttons.append(sb)
            sh.addWidget(sb, 1)
        self.seg_buttons[0].setChecked(True)
        self.mode_group.idClicked.connect(self._mode_changed)
        b.addWidget(seg)

        self.size_stack = QStackedWidget()
        pp = QWidget()
        pv = QVBoxLayout(pp)
        pv.setContentsMargins(0, 2, 0, 0)
        pv.setSpacing(6)
        self.lbl_pct = label("30%", "BigValue")
        pv.addWidget(self.lbl_pct)
        self.slider = QSlider(Qt.Orientation.Horizontal)
        self.slider.setRange(5, 95)
        self.slider.setPageStep(5)
        self.slider.setValue(30)
        self.slider.setCursor(Qt.CursorShape.PointingHandCursor)
        self.slider.valueChanged.connect(self._pct_changed)
        pv.addWidget(self.slider)
        ends = QHBoxLayout()
        ends.addWidget(label("Smallest", "Small"))
        ends.addStretch(1)
        ends.addWidget(label("Best quality", "Small"))
        pv.addLayout(ends)
        self.size_stack.addWidget(pp)

        mp = QWidget()
        mv = QVBoxLayout(mp)
        mv.setContentsMargins(0, 2, 0, 0)
        mv.setSpacing(8)
        self.spin = QDoubleSpinBox()
        self.spin.setRange(0.1, 100000)
        self.spin.setDecimals(1)
        self.spin.setValue(10)
        self.spin.setSuffix(" MB")
        self.spin.setButtonSymbols(QDoubleSpinBox.ButtonSymbols.NoButtons)
        self.spin.valueChanged.connect(lambda _v: self.refresh())
        mv.addWidget(self.spin)
        chips = QHBoxLayout()
        chips.setSpacing(6)
        for val in (5, 10, 25, 50, 100):
            c = button(f"{val} MB", "Chip", lambda _c=False, x=val: self.spin.setValue(x))
            chips.addWidget(c)
        dc = button("Discord", "Chip", lambda: self.spin.setValue(DISCORD_TARGET_MB))
        dc.setToolTip("Discord free accounts can send files up to 20 MB: aims at 19 MB to stay under it")
        chips.addWidget(dc)
        mv.addLayout(chips)
        self.size_stack.addWidget(mp)

        cp = QWidget()
        cv = QVBoxLayout(cp)
        cv.setContentsMargins(0, 2, 0, 0)
        cv.addWidget(label("Files change format without aiming for a size. Pick the formats in the "
                           "next block. When possible the video is not re-encoded, so no quality is lost.",
                           "Muted", True))
        cv.addStretch(1)
        self.size_stack.addWidget(cp)
        self.size_stack.setFixedHeight(96)
        b.addWidget(self.size_stack)
        self.lbl_explain = label("", "Small", True)
        b.addWidget(self.lbl_explain)
        v.addWidget(f)

        # formats + video quality
        f, b = block("Format and video")
        g = QGridLayout()
        g.setHorizontalSpacing(10)
        g.setVerticalSpacing(8)
        self.cmb_vfmt, self.cmb_ifmt, self.cmb_afmt = Chevron(), Chevron(), Chevron()
        self.cmb_res, self.cmb_fps = Chevron(), Chevron()
        rows = (("Video", self.cmb_vfmt, VIDEO_FORMATS), ("Photos", self.cmb_ifmt, IMAGE_FORMATS),
                ("Audio", self.cmb_afmt, AUDIO_FORMATS), ("Resolution", self.cmb_res, RESOLUTIONS),
                ("Frame rate", self.cmb_fps, FRAMERATES))
        for r, (name, cmb, opts) in enumerate(rows):
            for t, val in opts:
                cmb.addItem(t, val)
            cmb.setCursor(Qt.CursorShape.PointingHandCursor)
            cmb.currentIndexChanged.connect(lambda _i: self.refresh())
            gr = r if r < 3 else r + 1
            g.addWidget(label(name, "FieldLabel"), gr, 0)
            g.addWidget(cmb, gr, 1)
        sep = QFrame()
        sep.setFixedHeight(1)
        sep.setStyleSheet(f"background: {T.LINE};")
        g.addWidget(sep, 3, 0, 1, 2)
        g.setColumnStretch(1, 1)
        b.addLayout(g)
        b.addWidget(label("Auto lowers resolution and frame rate only when needed. "
                          "PDF and Office files keep their format.", "Small", True))
        v.addWidget(f)
        v.addStretch(1)

        sc = QScrollArea()
        sc.setWidgetResizable(True)
        sc.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        sc.setWidget(col)
        sc.setFixedWidth(372)
        return sc

    def _save_block(self) -> QFrame:
        f = QFrame()
        f.setObjectName("Block")
        v = QVBoxLayout(f)
        v.setContentsMargins(18, 12, 18, 12)
        v.setSpacing(8)
        row = QHBoxLayout()
        row.setSpacing(8)
        row.addWidget(label("Save to", "BlockTitle"))
        self.lbl_path = ElidedLabel("")
        self.lbl_path.setObjectName("Path")
        row.addWidget(self.lbl_path, 1)
        self.btn_change = button("Change folder", "Secondary", self.choose_output)
        self.btn_default = button("Use default", "Secondary", self.reset_output)
        row.addWidget(self.btn_change)
        row.addWidget(self.btn_default)
        v.addLayout(row)
        tg = QHBoxLayout()
        tg.setSpacing(10)
        self.tgl_move = Toggle()
        self.tgl_move.setChecked(True)
        self.tgl_move.toggled.connect(lambda _c: self.refresh())
        tg.addWidget(self.tgl_move)
        tg.addWidget(label("Move originals to the Original folder", "FieldLabel"))
        self.lbl_save_hint = ElidedLabel("")
        self.lbl_save_hint.setObjectName("Small")
        tg.addWidget(self.lbl_save_hint, 1)
        v.addLayout(tg)
        return f

    def _action_bar(self) -> QFrame:
        f = QFrame()
        f.setObjectName("Block")
        h = QHBoxLayout(f)
        h.setContentsMargins(18, 12, 12, 12)
        h.setSpacing(12)
        left = QVBoxLayout()
        left.setSpacing(6)
        self.lbl_status = label("Add some files to get started.", "Muted")
        left.addWidget(self.lbl_status)
        self.overall = QProgressBar()
        self.overall.setObjectName("Overall")
        self.overall.setRange(0, 1000)
        self.overall.setTextVisible(False)
        left.addWidget(self.overall)
        h.addLayout(left, 1)
        self.btn_open = button("Open output folder", "Secondary", self.open_output)
        h.addWidget(self.btn_open)
        self.btn_abort = button("Abort", "Danger", self.abort)
        self.btn_abort.setToolTip("Stop now. The file being processed is discarded; finished files are kept.")
        self.btn_abort.hide()
        h.addWidget(self.btn_abort)
        self.btn_go = button("Compress", "Primary", self.start)
        self.btn_go.setMinimumWidth(190)
        h.addWidget(self.btn_go)
        return f

    # ------------------------------------------------------------ settings
    def settings(self) -> Settings:
        mode = ("percent", "mb", "convert")[max(0, self.mode_group.checkedId())]
        return Settings(mode=mode, percent=self.slider.value(), target_mb=self.spin.value(),
                        resolution=self.cmb_res.currentData(), fps=self.cmb_fps.currentData(),
                        video_format=self.cmb_vfmt.currentData(), image_format=self.cmb_ifmt.currentData(),
                        audio_format=self.cmb_afmt.currentData(), output_root=self.output_root,
                        move_originals=self.tgl_move.isChecked())

    def _load_prefs(self) -> None:
        try:
            mode = int(self.prefs.value("mode", 0))
            self.seg_buttons[mode].setChecked(True)
            self.size_stack.setCurrentIndex(mode)
            self.slider.setValue(int(self.prefs.value("percent", 30)))
            self.spin.setValue(float(self.prefs.value("mb", 10.0)))
            for c, key in ((self.cmb_res, "resolution"), (self.cmb_fps, "fps"), (self.cmb_vfmt, "vfmt"),
                           (self.cmb_ifmt, "ifmt"), (self.cmb_afmt, "afmt")):
                c.setCurrentIndex(max(0, c.findData(self.prefs.value(key, "auto" if key in ("resolution", "fps") else "keep"))))
            root = self.prefs.value("output_root", "")
            self.output_root = Path(root) if root and Path(root).is_dir() else None
            self.tgl_move.setChecked(str(self.prefs.value("move", "true")).lower() == "true")
        except Exception:
            pass

    def _save_prefs(self) -> None:
        s = self.settings()
        values = {"mode": max(0, self.mode_group.checkedId()), "percent": s.percent, "mb": s.target_mb,
                  "resolution": s.resolution, "fps": s.fps, "vfmt": s.video_format, "ifmt": s.image_format,
                  "afmt": s.audio_format, "output_root": str(self.output_root or ""),
                  "move": "true" if s.move_originals else "false"}
        for k, val in values.items():
            self.prefs.setValue(k, val)

    def _mode_changed(self, idx: int) -> None:
        self.size_stack.setCurrentIndex(idx)
        self.refresh()

    def _pct_changed(self, v: int) -> None:
        self.lbl_pct.setText(f"{v}%")
        self.refresh()

    def choose_output(self) -> None:
        d = QFileDialog.getExistingDirectory(self, "Choose where to save", str(self.settings().root()))
        if d:
            self.output_root = Path(d)
            self.refresh()

    def reset_output(self) -> None:
        self.output_root = None
        self.refresh()

    def open_output(self) -> None:
        root = self.settings().root()
        target = root / "Compressed"
        reveal(target if target.exists() else root)

    # ------------------------------------------------------------ list
    def pending(self) -> list[FileItem]:
        return [i for i in self.items if i.state == WAITING]

    def refresh(self) -> None:
        s = self.settings()
        n = len(self.items)
        self.lbl_count.setText(f"{n} file{'s' if n != 1 else ''}, {human_size(sum(i.size for i in self.items))}" if n else "")
        self.files_stack.setCurrentIndex(1 if n else 0)
        self.lbl_drop_hint.setVisible(bool(n))
        pend = self.pending()
        if s.mode == "percent":
            before = sum(i.size for i in pend)
            self.lbl_explain.setText(
                f"Each file ends up at about {s.percent}% of its size. "
                + (f"Your files: {human_size(before)} → about {human_size(before * s.percent / 100)}."
                   if pend else f"1 GB becomes about {s.percent * 10} MB."))
        elif s.mode == "mb":
            small = [i for i in pend if i.size <= s.target_mb * MB]
            text = f"Each file ends up at about {s.target_mb:g} MB."
            if small:
                text += (f" {len(small)} of your files {'is' if len(small) == 1 else 'are'} "
                         "already smaller and will be skipped.")
            self.lbl_explain.setText(text)
        else:
            self.lbl_explain.setText("PDF and Office files can't be converted and will be skipped.")
        root = s.root()
        self.lbl_path.setText(str(root) + ("" if self.output_root else "  (program folder)"))
        self.btn_default.setEnabled(self.output_root is not None and not self.worker)
        self.lbl_save_hint.setText(
            "— results go in Compressed as name_compressed"
            + (", originals in Original." if s.move_originals else ", originals stay where they are."))
        for it in self.items:
            if it.row:
                it.row.refresh(s)
        if not self.worker:
            k = len(pend)
            verb = "Convert" if s.convert_only else "Compress"
            self.btn_go.setText(f"{verb} {k} file{'s' if k != 1 else ''}" if k else verb)
            self.btn_go.setEnabled(k > 0)
        self._save_prefs()

    def add_paths(self, paths: list[Path]) -> None:
        if self.worker:
            return
        found, skipped = gather(paths)
        known = {i.path for i in self.items}
        added = 0
        for pth in found:
            if pth in known:
                continue
            try:
                size = pth.stat().st_size
            except OSError:
                continue
            it = FileItem(pth, size, kind_of(pth) or "")
            row = FileRow(it)
            row.removeRequested.connect(self.remove_item)
            row.whyRequested.connect(self.show_why)
            row.retryRequested.connect(self.retry_item)
            row.menuRequested.connect(self.item_menu)
            it.row = row
            self.rows.insertWidget(self.rows.count() - 1, row)
            self.items.append(it)
            known.add(pth)
            added += 1
        msg = []
        if added:
            msg.append(f"Added {added} file{'s' if added != 1 else ''}.")
        if skipped:
            msg.append(f"{skipped} skipped because the format isn't supported.")
        if not found and not skipped:
            msg.append("No supported files found there.")
        self.lbl_status.setText(" ".join(msg))
        self.refresh()

    def remove_item(self, it: FileItem) -> None:
        if it.state == WORKING:
            return
        if it in self.items:
            self.items.remove(it)
        if it.row:
            it.row.setParent(None)
            it.row.deleteLater()
            it.row = None
        self.refresh()

    def clear(self) -> None:
        if self.worker:
            return
        for it in list(self.items):
            self.remove_item(it)
        self.overall.setValue(0)
        self.lbl_status.setText("Add some files to get started.")

    def browse(self) -> None:
        if self.worker:
            return
        exts = " ".join(f"*{e}" for e in sorted(ALL_EXT))
        files, _ = QFileDialog.getOpenFileNames(self, "Choose files", self.prefs.value("last_dir", ""),
                                                f"Supported files ({exts});;All files (*)")
        if files:
            self.prefs.setValue("last_dir", str(Path(files[0]).parent))
            self.add_paths([Path(f) for f in files])

    def browse_folder(self) -> None:
        if self.worker:
            return
        d = QFileDialog.getExistingDirectory(self, "Choose a folder", self.prefs.value("last_dir", ""))
        if d:
            self.prefs.setValue("last_dir", d)
            self.add_paths([Path(d)])

    def show_why(self, it: FileItem) -> None:
        DiagnosisDialog(it, self).exec()

    def item_menu(self, it: FileItem, pos) -> None:
        m = QMenu(self)
        r = it.result
        if r and r.ok and r.output:
            m.addAction("Open the result", lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(r.output))))
            m.addAction("Show the result in its folder", lambda: reveal(r.output))
        if r and r.original_moved_to:
            m.addAction("Show the original", lambda: reveal(r.original_moved_to))
        elif it.path.exists():
            m.addAction("Show the file in its folder", lambda: reveal(it.path))
        if it.state == FAILED:
            m.addAction("Why did it fail?", lambda: self.show_why(it))
            m.addAction("Try again", lambda: self.retry_item(it))
        if it.state != WORKING and not self.worker:
            m.addAction("Remove from the list", lambda: self.remove_item(it))
        m.exec(pos)

    def _reset_item(self, it: FileItem) -> None:
        it.state, it.result = WAITING, None
        if it.row:
            it.row.bar.setValue(0)
            it.row.why.hide()
            it.row.retry.hide()
            it.row.setProperty("state", "")
            repolish(it.row)
            it.row.set_status("Waiting")
        self.refresh()

    # ------------------------------------------------------------ drag & drop
    def _set_drop(self, on: bool) -> None:
        for w in (self.files_frame, self.drop):
            w.setProperty("drop", on)
            repolish(w)

    def dragEnterEvent(self, e) -> None:
        if e.mimeData().hasUrls() and not self.worker:
            e.acceptProposedAction()
            self._set_drop(True)

    def dragLeaveEvent(self, _e) -> None:
        self._set_drop(False)

    def dropEvent(self, e) -> None:
        self._set_drop(False)
        paths = [Path(u.toLocalFile()) for u in e.mimeData().urls() if u.isLocalFile()]
        if paths:
            e.acceptProposedAction()
            self.add_paths(paths)

    # ------------------------------------------------------------ run
    def _lock(self, running: bool) -> None:
        for w in [self.btn_add, self.btn_folder, self.btn_clear, self.slider, self.spin, self.cmb_res,
                  self.cmb_fps, self.cmb_vfmt, self.cmb_ifmt, self.cmb_afmt, self.btn_change,
                  self.btn_default, self.tgl_move] + self.seg_buttons + self.findChildren(QPushButton, "Chip"):
            w.setEnabled(not running)
        for it in self.items:
            if it.row:
                it.row.remove.setVisible(not running)
        self.btn_go.setVisible(not running)
        self.btn_abort.setVisible(running)
        self.btn_abort.setEnabled(True)
        self.btn_abort.setText("Abort")

    def retry_item(self, it: FileItem) -> None:
        """Run one failed file again, with the settings currently chosen (not the ones of the first try)."""
        if self.worker or it.state != FAILED:
            return
        self._reset_item(it)
        self.start([it])

    def start(self, only: Optional[list] = None) -> None:
        todo = only if only else self.pending()
        if not todo or self.worker:
            return
        s = self.settings()
        try:
            s.compressed_dir()
        except OSError as e:
            self.lbl_status.setText(f"Can't write in {s.root()} ({e.strerror or e}). "
                                    "Choose another folder in Save to.")
            return
        self.total, self.finished, self.ok_count, self.fail_count = len(todo), 0, 0, 0
        self.saved = 0
        self.worker = Worker(todo, s)
        self.worker.started_item.connect(self._on_start)
        self.worker.progressed.connect(self._on_progress)
        self.worker.finished_item.connect(self._on_done)
        self.worker.aborted_item.connect(self._on_aborted)
        self.worker.finished.connect(self._on_all_done)
        self._lock(True)
        self.overall.setValue(0)
        self.worker.start()

    def abort(self) -> None:
        if self.worker:
            self.btn_abort.setEnabled(False)
            self.btn_abort.setText("Aborting…")
            self.worker.stop()

    def _on_start(self, it: FileItem) -> None:
        it.state = WORKING
        if it.row:
            it.row.set_status("Starting", "busy")
            self.scroll.ensureWidgetVisible(it.row)
        verb = "Converting" if self.worker and self.worker.settings.convert_only else "Compressing"
        self.lbl_status.setText(f"{verb} {it.path.name} ({self.finished + 1} of {self.total})")

    def _on_progress(self, it: FileItem, frac: float, stage: str) -> None:
        if it.row:
            it.row.bar.setValue(int(frac * 1000))
            it.row.set_status(f"{int(frac * 100)}%  {stage}", "busy")
        self.overall.setValue(int((self.finished + frac) / max(1, self.total) * 1000))

    def _on_done(self, it: FileItem, r: Result) -> None:
        self.finished += 1
        it.result = r
        row = it.row
        if r.ok:
            it.state = DONE
            self.ok_count += 1
            self.saved += r.original_size - r.new_size
            change = (r.new_size / r.original_size - 1) * 100 if r.original_size else 0
            if row:
                row.bar.setValue(1000)
                row.set_status(f"Done  {change:+.0f}%", "ok")
                row.meta.setText(f"{human_size(r.original_size)} → {human_size(r.new_size)}, "
                                 f"saved as {r.output.name}")
                tip = [f"Saved in: {r.output.parent}"] + ([f"Original moved to: {r.original_moved_to.parent}"]
                                                         if r.original_moved_to else []) + r.notes
                row.setToolTip("\n".join(tip))
        else:
            it.state = FAILED
            self.fail_count += 1
            if row:
                row.bar.setValue(0)
                row.set_status("Failed", "bad")
                row.meta.setText(r.message)
                row.why.show()
                row.retry.show()
                row.setProperty("state", "failed")
                repolish(row)
        self.overall.setValue(int(self.finished / max(1, self.total) * 1000))

    def _on_aborted(self, it: FileItem) -> None:
        it.state = WAITING
        if it.row:
            it.row.bar.setValue(0)
            it.row.set_status("Aborted, not changed", "bad")

    def _on_all_done(self) -> None:
        aborted = self.worker.cancel_event.is_set() if self.worker else False
        self.worker = None
        self._lock(False)
        parts = []
        if self.ok_count:
            if self.saved > 0:
                parts.append(f"Done: {self.ok_count} file{'s' if self.ok_count != 1 else ''}, "
                             f"{human_size(self.saved)} saved.")
            else:
                parts.append(f"Done: {self.ok_count} file{'s' if self.ok_count != 1 else ''}.")
        if self.fail_count:
            parts.append(f"{self.fail_count} failed, click “Why did it fail?” on them for help.")
        if aborted:
            parts.append("Aborted. The file in progress was left untouched.")
        self.lbl_status.setText(" ".join(parts) or "Nothing was processed.")
        if not aborted:
            self.overall.setValue(1000)
        self.refresh()

    def closeEvent(self, e) -> None:
        if self.worker:
            self.worker.stop()
            self.worker.wait(5000)
        super().closeEvent(e)
