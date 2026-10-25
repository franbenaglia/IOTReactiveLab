#!/usr/bin/env bash
# Hook SessionStart: inyecta el estado del proyecto al abrir una sesión de
# Claude Code, para no depender de la memoria de sesiones anteriores.
cd "$(dirname "$0")/../.." || exit 0

echo "=== IoT Reactive Lab — contexto de inicio de sesión ==="
echo "Protocolo: leer CLAUDE.md §1 antes de modificar código."
echo
if [ -f docs/progress.md ]; then
  # Secciones "Estado actual" y "Pendientes / bloqueos", hasta "## Entorno".
  awk '/^## Estado actual/{p=1} /^## Entorno/{p=0} p' docs/progress.md
fi
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "--- git: rama $(git branch --show-current) ---"
  git log --oneline -5 2>/dev/null
  cambios=$(git status --porcelain | head -20)
  [ -n "$cambios" ] && { echo "--- cambios sin commitear ---"; echo "$cambios"; }
fi
exit 0
