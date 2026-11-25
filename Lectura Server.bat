@echo off
rem Windows: double-click to run the server in a console window (Ctrl+C or closing the window stops it).
rem On macOS use "Lectura Server.command". Needs Python 3.10+; texts come from Claude Code if it is
rem installed and logged in, otherwise from a local model in Ollama (downloaded on the first start).
chcp 65001 >nul
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
title Lectura - server
cd /d "%~dp0"

set "PY="
where py >nul 2>nul && py -3 -c "import sys; sys.exit(sys.version_info < (3, 10))" >nul 2>nul && set "PY=py -3"
if not defined PY (
  where python >nul 2>nul && python -c "import sys; sys.exit(sys.version_info < (3, 10))" >nul 2>nul && set "PY=python"
)
if not defined PY (
  echo Нужен Python 3.10 или новее: https://www.python.org/downloads/
  echo При установке отметьте "Add python.exe to PATH". Или в PowerShell: winget install Python.Python.3.12
  goto end
)

%PY% pipeline\server.py %*

:end
echo.
pause
