@echo off
rem Builds "dist\General File Compressor\General File Compressor.exe" on Windows.
rem Needs Python 3.12 (64-bit) from python.org. Run it by double-clicking.
setlocal
cd /d "%~dp0"

py -3.12 -V >nul 2>nul
if errorlevel 1 (
  echo Python 3.12 was not found. Install it from https://www.python.org/downloads/ and try again.
  pause
  exit /b 1
)

if not exist .venv py -3.12 -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt pyinstaller imageio-ffmpeg || goto :fail
pip install --no-deps PyMuPDF || goto :fail

rem FFmpeg comes from the imageio-ffmpeg package
for /f "delims=" %%F in ('python -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())"') do set "FFMPEG=%%F"
if not exist packaging\ffmpeg mkdir packaging\ffmpeg
copy /y "%FFMPEG%" packaging\ffmpeg\ffmpeg.exe >nul || goto :fail

pyinstaller --noconfirm --clean packaging\general_file_compressor.spec || goto :fail
echo.
echo Done: dist\General File Compressor\General File Compressor.exe
pause
exit /b 0

:fail
echo Build failed, see the messages above.
pause
exit /b 1
