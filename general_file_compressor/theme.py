"""Colors, fonts and the Qt style sheet: light canvas, white blocks, solid type tiles."""
from __future__ import annotations

from PySide6.QtGui import QColor, QFont, QFontDatabase

from .core import KIND_AUDIO, KIND_IMAGE, KIND_OFFICE, KIND_PDF, KIND_VIDEO, resource_dir

CANVAS = "#E9EDF3"
BLOCK = "#FFFFFF"
SUNK = "#F3F5F9"       # inputs, tracks
INK = "#11151C"
MUTED = "#5F6878"
LINE = "#D9DFE8"
COBALT = "#2346F5"
COBALT_HOVER = "#3858FF"
COBALT_DOWN = "#1A37CC"
DANGER = "#D93B3B"
SUCCESS = "#0F9D58"

KIND_COLORS = {
    KIND_VIDEO: "#6D4DF2",
    KIND_IMAGE: "#0B9E8F",
    KIND_PDF: "#E0473E",
    KIND_AUDIO: "#E58A00",
    KIND_OFFICE: "#1F6FEB",
}

DISPLAY = "Space Grotesk"
TEXT = "Manrope"


def qc(hex_color: str, alpha: float = 1.0) -> QColor:
    c = QColor(hex_color)
    c.setAlphaF(alpha)
    return c


def load_fonts() -> tuple[str, str]:
    folder = resource_dir() / "assets" / "fonts"
    loaded = set()
    for f in sorted(folder.glob("*.ttf")):
        fid = QFontDatabase.addApplicationFont(str(f))
        if fid >= 0:
            loaded.update(QFontDatabase.applicationFontFamilies(fid))
    display = DISPLAY if any(DISPLAY in n for n in loaded) else "Segoe UI"
    text = TEXT if any(TEXT in n for n in loaded) else "Segoe UI"
    return display, text


def font(family: str, px: int, weight: QFont.Weight = QFont.Weight.Normal) -> QFont:
    f = QFont(family)
    f.setPixelSize(px)
    f.setWeight(weight)
    f.setHintingPreference(QFont.HintingPreference.PreferNoHinting)
    return f


def stylesheet(display: str, text: str) -> str:
    return f"""
* {{ font-family: "{text}"; color: {INK}; font-size: 13px; }}
QMainWindow, QWidget#Root {{ background: {CANVAS}; }}
QLabel {{ background: transparent; }}
QToolTip {{ background: {INK}; color: #FFFFFF; border: none; border-radius: 6px; padding: 6px 8px; }}

QFrame#Block {{ background: {BLOCK}; border: 1px solid {LINE}; border-radius: 16px; }}
QFrame#Block[drop="true"] {{ border: 2px solid {COBALT}; }}
QLabel#AppTitle {{ font-family: "{display}"; font-size: 24px; font-weight: 700; }}
QLabel#AppSub {{ color: {MUTED}; }}
QLabel#BlockTitle {{ font-family: "{display}"; font-size: 16px; font-weight: 700; }}
QLabel#Muted {{ color: {MUTED}; }}
QLabel#Small {{ color: {MUTED}; font-size: 12px; }}
QLabel#FieldLabel {{ color: {MUTED}; font-weight: 600; }}
QLabel#BigValue {{ font-family: "{display}"; font-size: 38px; font-weight: 700; }}
QLabel#Path {{ background: {SUNK}; border: 1px solid {LINE}; border-radius: 10px; padding: 8px 10px;
               font-size: 12px; font-weight: 600; }}

QFrame#Drop {{ background: {SUNK}; border: 2px dashed {LINE}; border-radius: 14px; }}
QFrame#Drop[drop="true"] {{ border-color: {COBALT}; background: #EEF1FF; }}
QLabel#DropTitle {{ font-family: "{display}"; font-size: 20px; font-weight: 700; }}

QFrame#Row {{ background: {BLOCK}; border: 1px solid {LINE}; border-radius: 12px; }}
QFrame#Row:hover {{ border-color: #BFC8D6; }}
QFrame#Row[state="failed"] {{ border-color: #F1B9B9; background: #FFF8F8; }}
QLabel#Tile {{ font-family: "{display}"; font-size: 12px; font-weight: 700; color: #FFFFFF;
               border-radius: 10px; }}
QLabel#RowName {{ font-weight: 700; font-size: 13px; }}
QLabel#RowMeta {{ color: {MUTED}; font-size: 12px; }}
QLabel#RowStatus {{ font-weight: 700; font-size: 12px; color: {MUTED}; }}
QLabel#RowStatus[tone="ok"] {{ color: {SUCCESS}; }}
QLabel#RowStatus[tone="bad"] {{ color: {DANGER}; }}
QLabel#RowStatus[tone="busy"] {{ color: {COBALT}; }}

QPushButton {{ border-radius: 10px; padding: 8px 14px; font-weight: 700; }}
QPushButton#Primary {{ background: {COBALT}; color: #FFFFFF; border: none; padding: 0 22px; min-height: 44px; font-size: 14px; }}
QPushButton#Primary:hover {{ background: {COBALT_HOVER}; }}
QPushButton#Primary:pressed {{ background: {COBALT_DOWN}; }}
QPushButton#Primary:disabled {{ background: #C9D1E3; color: #F4F6FA; }}
QPushButton#Danger {{ background: {DANGER}; color: #FFFFFF; border: none; padding: 0 22px; min-height: 44px; font-size: 14px; }}
QPushButton#Danger:hover {{ background: #E54E4E; }}
QPushButton#Danger:disabled {{ background: #EBB3B3; }}
QPushButton#Secondary {{ background: {BLOCK}; border: 1px solid {LINE}; color: {INK}; }}
QPushButton#Secondary:hover {{ border-color: {COBALT}; color: {COBALT}; }}
QPushButton#Secondary:disabled {{ color: #A9B1BF; border-color: {LINE}; }}
QPushButton#Link {{ background: transparent; border: none; color: {COBALT}; padding: 2px 4px; font-size: 12px; }}
QPushButton#Link:hover {{ text-decoration: underline; }}
QPushButton#Remove {{ background: transparent; border: none; color: {MUTED}; font-size: 15px; padding: 2px 6px; }}
QPushButton#Remove:hover {{ color: {DANGER}; }}
QPushButton#Chip {{ background: {SUNK}; border: 1px solid {LINE}; border-radius: 8px; padding: 5px 0; font-size: 12px; color: {MUTED}; }}
QPushButton#Chip:hover {{ border-color: {COBALT}; color: {COBALT}; }}
QPushButton:focus {{ outline: none; }}
QPushButton#Primary:focus, QPushButton#Secondary:focus, QPushButton#Danger:focus {{ border: 2px solid #9DB0FF; }}

QFrame#Segment {{ background: {SUNK}; border: 1px solid {LINE}; border-radius: 12px; }}
QPushButton#Seg {{ background: transparent; border: none; border-radius: 9px; padding: 7px 6px; font-size: 12px; color: {MUTED}; }}
QPushButton#Seg:checked {{ background: {INK}; color: #FFFFFF; }}
QPushButton#Seg:hover:!checked {{ color: {INK}; }}

QSlider {{ min-height: 26px; }}
QSlider::groove:horizontal {{ height: 6px; border-radius: 3px; background: {LINE}; }}
QSlider::sub-page:horizontal {{ border-radius: 3px; background: {COBALT}; }}
QSlider::handle:horizontal {{ width: 14px; margin: -7px 0; border-radius: 7px;
                              background: {COBALT}; border: 3px solid #FFFFFF; }}
QSlider::handle:horizontal:hover {{ background: {COBALT_HOVER}; }}

QDoubleSpinBox {{ background: {SUNK}; border: 1px solid {LINE}; border-radius: 12px; padding: 4px 12px;
                  font-family: "{display}"; font-size: 28px; font-weight: 700; min-height: 46px;
                  selection-background-color: {COBALT}; selection-color: #FFFFFF; }}
QDoubleSpinBox:focus {{ border-color: {COBALT}; }}
QDoubleSpinBox::up-button, QDoubleSpinBox::down-button {{ width: 0; border: none; }}

QComboBox {{ background: {SUNK}; border: 1px solid {LINE}; border-radius: 10px; padding: 6px 30px 6px 10px;
             font-weight: 600; min-height: 20px; }}
QComboBox:hover, QComboBox:focus {{ border-color: {COBALT}; }}
QComboBox:disabled {{ color: #A9B1BF; }}
QComboBox::drop-down {{ border: none; width: 24px; }}
QComboBox::down-arrow {{ image: none; width: 0; height: 0; }}
QComboBox QAbstractItemView {{ background: {BLOCK}; border: 1px solid {LINE}; padding: 4px; outline: none;
                               selection-background-color: #E6EBFF; selection-color: {INK}; }}

QProgressBar {{ background: {SUNK}; border: none; border-radius: 3px; min-height: 6px; max-height: 6px; }}
QProgressBar::chunk {{ border-radius: 3px; background: {COBALT}; }}
QProgressBar#Overall {{ min-height: 10px; max-height: 10px; border-radius: 5px; }}
QProgressBar#Overall::chunk {{ border-radius: 5px; }}

QScrollArea {{ background: transparent; border: none; }}
QScrollArea > QWidget > QWidget {{ background: transparent; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: #C5CDD9; border-radius: 3px; min-height: 30px; }}
QScrollBar::handle:vertical:hover {{ background: #A9B3C3; }}
QScrollBar::add-line, QScrollBar::sub-line, QScrollBar::add-page, QScrollBar::sub-page {{ height: 0; background: none; }}

QMenu {{ background: {BLOCK}; border: 1px solid {LINE}; border-radius: 10px; padding: 4px; }}
QMenu::item {{ padding: 7px 16px; border-radius: 6px; }}
QMenu::item:selected {{ background: #E6EBFF; }}
QDialog {{ background: {BLOCK}; }}
"""
