#!/bin/sh
# Installs/restarts the QA dashboard LaunchAgent (port 8097, https://drive.guseh1604.synology.me).
set -e
PLIST_SRC="$(cd "$(dirname "$0")" && pwd)/com.zobo.fogdrive.qa.plist"
PLIST=~/Library/LaunchAgents/com.zobo.fogdrive.qa.plist
case "${1:-restart}" in
  restart|install)
    cp "$PLIST_SRC" "$PLIST"
    launchctl bootout "gui/$(id -u)/com.zobo.fogdrive.qa" 2>/dev/null || true
    launchctl bootstrap "gui/$(id -u)" "$PLIST"
    echo "started: http://127.0.0.1:8097" ;;
  stop)
    launchctl bootout "gui/$(id -u)/com.zobo.fogdrive.qa" ;;
  *) echo "usage: $0 [restart|stop]"; exit 1 ;;
esac
