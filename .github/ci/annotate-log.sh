#!/usr/bin/env bash
# Schreibt die wichtigsten Fehlerzeilen eines Gradle-Logs als Annotationen in den Lauf.
# Annotationen lassen sich über die GitHub-API lesen (check-runs/{id}/annotations),
# auch wenn die Roh-Logs nicht erreichbar sind.
set -u
log="${1:-build.log}"
[ -f "$log" ] || { echo "::error title=Build-Log fehlt::$log nicht gefunden"; exit 0; }

encode() {
  # %, CR und LF für Workflow-Kommandos maskieren
  sed -e 's/%/%25/g' -e 's/\r/%0D/g' | awk 'BEGIN{ORS="%0A"} {print}'
}

errors=$(grep -E '^(e: |error: )|What went wrong|Could not (resolve|find|get|determine)|^> |FAILED$|Execution failed|Unresolved reference|Plugin .* was not found|not found in any of the following sources|Test.*FAILED|AssertionError|Exception' "$log" \
  | grep -v '^w: ' | awk '!seen[$0]++' | head -n 150)
if [ -n "$errors" ]; then
  echo "::error title=Gradle-Fehler::$(printf '%s\n' "$errors" | cut -c1-400 | encode)"
fi
echo "::error title=Gradle-Log (Ende)::$(tail -n 120 "$log" | cut -c1-400 | encode)"
