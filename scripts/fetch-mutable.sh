#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
root="app/src/main/cpp/eurorack"
if [ ! -d "$root/.git" ]; then
  rm -rf "$root"
  git clone --filter=blob:none https://github.com/pichenettes/eurorack.git "$root"
fi
cd "$root"
git fetch origin 08460a6 || true
git checkout --detach 08460a6
git submodule update --init --recursive
if [ ! -f plaits/dsp/voice.cc ] || [ ! -f rings/dsp/part.cc ]; then
  echo 'ERROR: Mutable Instruments source checkout incomplete' >&2
  exit 1
fi
printf 'Using Mutable Instruments sources at %s\n' "$(git rev-parse --short HEAD)"
