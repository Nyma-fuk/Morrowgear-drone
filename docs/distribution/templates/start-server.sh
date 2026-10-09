#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
  echo "[ERROR] Java was not found. Install 64-bit Java 25 or newer." >&2
  exit 1
fi

java -version
echo "[MORROWGEAR] Starting Minecraft 26.2 Fabric server..."
exec java -Xms2G -Xmx6G -jar fabric-server-mc.26.2-loader.0.19.3-launcher.1.1.2.jar nogui

