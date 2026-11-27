#!/usr/bin/env bash
# Copyright (C) 2026 Term Organize contributors.
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail
classes="${1:-app/build/tmp/kotlin-classes/release}"
javap="${JAVA_HOME:+$JAVA_HOME/bin/}javap"
banner="$classes/app/termorganize/core/banner"
output=$("$javap" -public -classpath "$classes" \
  app.termorganize.core.banner.BannerClient \
  app.termorganize.core.banner.BannerClientKt \
  app.termorganize.core.banner.BannerProvider \
  app.termorganize.core.banner.BannerCompositionKt)
printf '%s\n' "$output"
if find "$banner" -name '*DebugBannerComposition*' -o -name '*FakeBanner*' | grep -q .; then
  echo 'Release contains test composition' >&2
  exit 1
fi
# The release composition source must be exactly the fixed provider line (plus header comments).
release_src="$(dirname "$0")/../app/src/release"
extra=$(find "$release_src" -type f | grep -v '/core/banner/BannerComposition.kt$' || true)
if [ -n "$extra" ]; then
  echo "Unexpected release source: $extra" >&2
  exit 1
fi
code=$(grep -vE '^[[:space:]]*(//|$)' "$release_src/java/app/termorganize/core/banner/BannerComposition.kt")
expected=$'package app.termorganize.core.banner\ninternal fun appBannerProvider(): BannerProvider = liveBannerProvider'
if [ "$code" != "$expected" ]; then
  echo 'Release BannerComposition.kt is not the fixed provider' >&2
  exit 1
fi
# Inspect every Banner client/provider/composition class, including companions, lambdas and synthetic accessors.
# They must be immutable: no set* method and no non-final static field, whatever the name.
all=$(find "$banner" \( -name 'BannerClient*.class' -o -name 'BannerProvider*.class' -o -name 'BannerComposition*.class' \))
if [ -z "$all" ]; then
  echo 'No release banner composition classes found' >&2
  exit 1
fi
while IFS= read -r class; do
  members=$("$javap" -p "$class")
  if grep -E '[[:space:]]set[A-Za-z0-9_$]*\(' <<< "$members"; then
    echo "Release exposes a setter in $class" >&2
    exit 1
  fi
  if grep -E '[[:space:]]static[[:space:]]' <<< "$members" | grep -vE '[[:space:]]final[[:space:]]|\(|\{' ; then
    echo "Release has a non-final static field in $class" >&2
    exit 1
  fi
done <<< "$all"
