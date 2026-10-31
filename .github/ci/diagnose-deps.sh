#!/usr/bin/env bash
# Sucht fehlende Abhängigkeiten: welches Repo hat sie, wer zieht sie herein. Ausgabe als Annotation.
set -u
mod="${1:-com/thingclips/android/module/thingmodule-annotation/1.5.0-SNAPSHOT}"
name=$(echo "$mod" | awk -F/ '{print $(NF-1)}')
{
  echo "Repo-Suche nach $mod"
  for r in \
    https://maven-other.tuya.com/repository/maven-releases \
    https://maven-other.tuya.com/repository/maven-commercial-releases \
    https://maven-other.tuya.com/repository/maven-snapshots \
    https://maven-other.tuya.com/repository/maven-public \
    https://maven-other.tuya.com/repository/maven-commercial-snapshots \
    https://jitpack.io \
    https://maven.aliyun.com/repository/public \
    https://maven.aliyun.com/repository/snapshots \
    https://developer.huawei.com/repo \
    https://oss.sonatype.org/content/repositories/snapshots \
    https://s01.oss.sonatype.org/content/repositories/snapshots \
    https://central.sonatype.com/repository/maven-snapshots; do
    c1=$(curl -s -o /dev/null -m 20 -w '%{http_code}' "$r/$mod/maven-metadata.xml")
    c2=$(curl -s -o /dev/null -m 20 -w '%{http_code}' "$r/$(dirname "$mod")/maven-metadata.xml")
    echo "$c1 $c2 $r"
    if [ "$c2" = 200 ]; then
      curl -s -m 20 "$r/$(dirname "$mod")/maven-metadata.xml" | grep -oE "<(version|latest|release)>[^<]+" | tail -15 | tr '\n' ' '; echo
    fi
  done
  echo
  echo "dependencyInsight $name:"
  ./gradlew --no-daemon -q :app:dependencyInsight --configuration playDebugRuntimeClasspath --dependency "$name" 2>&1 | head -80
} > diag.log 2>&1
enc=$(sed -e 's/%/%25/g' -e 's/\r/%0D/g' diag.log | cut -c1-300 | awk 'BEGIN{ORS="%0A"} {print}')
echo "::error title=Diagnose::$enc"
