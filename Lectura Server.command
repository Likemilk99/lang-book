#!/bin/zsh
# macOS: double-click to run the «Мои фразы» / «Живой разговор» server in a Terminal window
# (Ctrl+C or closing the window stops it). On Windows use «Lectura Server.bat».
# A shortcut: Finder → right click → «Создать псевдоним», then move it to the Desktop or the Dock.
cd "$(dirname "$0")" || exit 1
printf '\e]0;Lectura — сервер «Мои фразы»\a'
if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null; then
  echo "❌ Нужен Python 3.10 или новее: https://www.python.org/downloads/ (или: brew install python)"
else
  # caffeinate: the Mac doesn't idle-sleep while the server runs (closing the lid still sleeps it on battery).
  caffeinate -i python3 pipeline/server.py "$@"
fi
echo
read -k 1 "?Нажмите любую клавишу, чтобы закрыть окно…"
