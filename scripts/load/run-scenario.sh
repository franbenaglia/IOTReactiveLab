#!/usr/bin/env bash
# Escenario de carga reproducible: simulador + contador del lado receptor.
#
#   scripts/load/run-scenario.sh <dispositivos> <A|B> [intervalo_s] [duracion_s] [extra args del simulador...]
#   scripts/load/run-scenario.sh 1000 A 1 60
#   scripts/load/run-scenario.sh 5000 B 1 60 --devices-per-connection 250
#
# Requiere: docker compose up -d mosquitto, y simulator/.venv creado.
# Resultados en scripts/load/results/ (no versionado): JSON del simulador,
# JSON del receptor y la salida de ambos.
set -euo pipefail

DEVICES=${1:?dispositivos}
MODE=${2:?modo A o B}
INTERVAL=${3:-1}
DURATION=${4:-60}
shift $(( $# < 4 ? $# : 4 ))

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PY="$ROOT/simulator/.venv/bin/python"
OUT="$ROOT/scripts/load/results"
mkdir -p "$OUT"
TAG="$(date +%Y%m%d-%H%M%S)-${DEVICES}dev-${MODE}-${INTERVAL}s"

# El receptor escucha un poco más que el simulador (arranque + vaciado).
"$PY" "$ROOT/simulator/tools/count_messages.py" --duration $((DURATION + 25)) --interval 10 \
  --prefix esp32-sim- --summary-json "$OUT/$TAG-rx.json" > "$OUT/$TAG-rx.log" 2>&1 &
RX=$!
sleep 2

"$PY" "$ROOT/simulator/simulator.py" --devices "$DEVICES" --connection-mode "$MODE" \
  --interval "$INTERVAL" --duration "$DURATION" --stats-interval 10 --seed 42 \
  --summary-json "$OUT/$TAG-sim.json" "$@" > "$OUT/$TAG-sim.log" 2>&1 || true
wait $RX || true

python3 - "$OUT/$TAG-sim.json" "$OUT/$TAG-rx.json" "$TAG" <<'PY'
import json, sys
sim, rx, tag = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3]
print(f"{tag}: conexiones={sim['connections_expected']} pedidos={sim['telemetry_requested']} "
      f"confirmados={sim['telemetry_confirmed']} recibidos={rx['received']} válidos={rx['valid']} "
      f"duplicados={rx['duplicates']} dispositivosVistos={rx['devices_seen']} "
      f"conf/s={sim['confirmed_per_second']} ackAvg={sim['ack_latency_avg_ms']}ms ackMax={sim['ack_latency_max_ms']}ms "
      f"errores={sim['errors']} fallosConexión={sim['connect_failures']} enVuelo={sim['in_flight']}")
PY
