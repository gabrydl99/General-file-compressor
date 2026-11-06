# PyInstaller spec for the Windows build.  Run from the repository root:
#   pyinstaller --noconfirm packaging/general_file_compressor.spec
# FFmpeg must be at packaging/ffmpeg/ffmpeg.exe (build_windows.bat puts it there).
import os

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
FFMPEG = os.path.join(SPECPATH, "ffmpeg", "ffmpeg.exe")

a = Analysis(
    [os.path.join(ROOT, "main.py")],
    pathex=[ROOT],
    binaries=[(FFMPEG, "bin")],
    datas=[(os.path.join(ROOT, "general_file_compressor", "assets"), "assets")],
    hiddenimports=["pillow_heif"],
    excludes=["tkinter", "unittest", "numpy", "pymupdf4llm", "onnxruntime", "networkx",
              "PySide6.QtNetwork", "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtSql",
              "PySide6.QtTest", "PySide6.QtOpenGL", "PySide6.QtDBus"],
    noarchive=False,
)
# not needed: software OpenGL fallback and Qt's own translations (the app is in English)
a.binaries = [b for b in a.binaries if "opengl32sw" not in b[0].lower()]
a.datas = [d for d in a.datas if "pyside6/translations" not in d[0].replace("\\", "/").lower()]

pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="FileCompressor",
    icon=os.path.join(SPECPATH, "icon.ico"),
    version=os.path.join(SPECPATH, "version_info.txt"),
    console=False,
    upx=False,
)
coll = COLLECT(exe, a.binaries, a.datas, name="FileCompressor", upx=False)
