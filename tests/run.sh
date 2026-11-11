#!/usr/bin/env bash
# Esegue i test: motore di cronometraggio (dati GPS sintetici) e interfaccia
# (browser headless con GPS simulato; richiede Playwright + Chromium).
set -e
cd "$(dirname "$0")/.."
echo "== Test del motore di cronometraggio =="
cat js/geo.js js/timing.js tests/test-engine.js | node -
echo
echo "== Test end-to-end dell'interfaccia =="
node tests/test-ui.js
