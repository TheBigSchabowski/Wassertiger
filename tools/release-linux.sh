#!/bin/bash
# From the Mac: copies the source to hostinger, builds the Linux packages there, fetches them into dist/linux.
set -euo pipefail
cd "$(dirname "$0")/.."
HOST="${HOST:-hostinger}"
REMOTE="build/Wassertiger"
rsync -az --delete --exclude .git --exclude build --exclude '*/build' --exclude .gradle --exclude .kotlin \
  --exclude local.properties --exclude dist ./ "$HOST:$REMOTE/"
ssh "$HOST" "cd $REMOTE && ./tools/build-linux.sh 2>&1 | tee ~/build/wassertiger-linux.log | tail -n 15"
mkdir -p dist/linux
rsync -az --delete "$HOST:$REMOTE/dist/linux/" dist/linux/
ls -la dist/linux
