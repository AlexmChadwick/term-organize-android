#!/usr/bin/env bash
set -euo pipefail
classes="${1:-app/build/tmp/kotlin-classes/release}"
javap="${JAVA_HOME:+$JAVA_HOME/bin/}javap"
output=$("$javap" -public -classpath "$classes" \
  app.termorganize.core.banner.BannerClient \
  app.termorganize.core.banner.BannerClientKt \
  app.termorganize.core.banner.BannerProvider \
  app.termorganize.core.banner.BannerCompositionKt)
printf '%s\n' "$output"
if find "$classes/app/termorganize/core/banner" -name '*DebugBannerComposition*' -o -name '*FakeBanner*' | grep -q .; then
  echo 'Release contains test composition' >&2
  exit 1
fi
# Inspect every Banner client/provider class, including companions and synthetic accessors.
all=$(find "$classes/app/termorganize/core/banner" -name 'BannerClient*.class' -o -name 'BannerProvider*.class' -o -name 'BannerComposition*.class')
while IFS= read -r class; do
  if "$javap" -public "$class" | grep -E 'setFactory|resetFactory|setOverrideProvider|setLiveBannerProvider'; then
    echo 'Release exposes mutable Banner composition' >&2
    exit 1
  fi
done <<< "$all"
