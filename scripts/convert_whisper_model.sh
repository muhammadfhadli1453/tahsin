#!/usr/bin/env bash
# Convert a Hugging Face Whisper checkpoint to a ggml model Tahsin can load.
#
# The general-purpose models in the in-app catalogue understand Arabic, but a
# Quran fine-tune is distinctly better at recitation. Those are published in
# Transformers format, so they have to be converted locally:
#
#   ./scripts/convert_whisper_model.sh tarteel-ai/whisper-base-ar-quran
#
# The result is written to build/models/. Push it to a device and import it from
# Tahsin's Settings screen ("Import a .bin model from storage"), or drop it into
# app/src/main/assets/models/ to ship it inside the APK.
#
# Requirements: python3 with torch, transformers, numpy and huggingface_hub, and
# the whisper.cpp submodule checked out.
set -euo pipefail

MODEL_ID="${1:-tarteel-ai/whisper-base-ar-quran}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WHISPER_CPP="$REPO_ROOT/whisper/src/main/cpp/whisper.cpp"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/build/models}"
QUANTIZE="${QUANTIZE:-q5_1}"   # set QUANTIZE=none to skip quantisation

# The upstream converter reads the mel filter bank out of the *OpenAI* whisper
# Python package's asset directory, which whisper.cpp does not vendor. Fetching
# the single .npz is far lighter than installing openai-whisper.
MEL_FILTERS_URL="https://raw.githubusercontent.com/openai/whisper/main/whisper/assets/mel_filters.npz"

SLUG="$(printf '%s' "$MODEL_ID" | tr '/' '-')"
CONVERTER="$WHISPER_CPP/models/convert-h5-to-ggml.py"

if [[ ! -f "$CONVERTER" ]]; then
    echo "error: whisper.cpp submodule is missing at $WHISPER_CPP" >&2
    echo "       run: git submodule update --init --recursive" >&2
    exit 1
fi

for module in torch transformers numpy huggingface_hub; do
    python3 -c "import $module" 2>/dev/null || {
        echo "error: python module '$module' is required" >&2
        echo "       pip install torch transformers numpy huggingface_hub" >&2
        exit 1
    }
done

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Downloading $MODEL_ID"
# Fetch the repo files verbatim rather than round-tripping through
# save_pretrained(): the converter needs the original vocab.json / merges.txt /
# added_tokens.json, and recent transformers versions save a fast tokenizer
# (tokenizer.json) instead, which the converter cannot read.
python3 - "$MODEL_ID" "$WORK/hf" <<'PY'
import sys
from huggingface_hub import snapshot_download

model_id, target = sys.argv[1], sys.argv[2]
path = snapshot_download(
    repo_id=model_id,
    local_dir=target,
    allow_patterns=[
        "*.json",
        "merges.txt",
        "pytorch_model.bin",
        "model.safetensors",
    ],
    ignore_patterns=["runs/*", "all_results.json", "eval_results.json",
                     "train_results.json", "trainer_state.json"],
)
print(f"fetched {model_id} -> {path}")
PY

# The upstream converter writes n_text_ctx from config.json's "max_length", but
# on fine-tuned checkpoints that field often carries a *generation* limit rather
# than the decoder's real size - tarteel-ai/whisper-base-ar-quran ships
# max_length=1024 against a 448-row positional embedding, which produces a model
# whisper.cpp rejects with "decoder.positional_embedding has wrong size".
# max_target_positions is the authoritative value, so pin max_length to it.
echo "==> Normalising decoder context length"
python3 - "$WORK/hf/config.json" <<'NORMALISE'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    config = json.load(handle)

positions = config.get("max_target_positions")
if positions and config.get("max_length") != positions:
    print(f"    max_length {config.get('max_length')} -> {positions}")
    config["max_length"] = positions
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(config, handle, ensure_ascii=False, indent=2)
NORMALISE

for required in vocab.json added_tokens.json config.json; do
    if [[ ! -f "$WORK/hf/$required" ]]; then
        echo "error: $MODEL_ID is missing $required, which the converter needs" >&2
        echo "       (only classic Whisper checkpoints can be converted this way)" >&2
        exit 1
    fi
done

echo "==> Fetching mel filter bank"
mkdir -p "$WORK/assets/whisper/assets"
curl -fsSL -o "$WORK/assets/whisper/assets/mel_filters.npz" "$MEL_FILTERS_URL"

echo "==> Converting to ggml"
mkdir -p "$WORK/out"
python3 "$CONVERTER" "$WORK/hf" "$WORK/assets" "$WORK/out"

CONVERTED="$(find "$WORK/out" -name '*.bin' | head -n 1)"
if [[ -z "$CONVERTED" ]]; then
    echo "error: conversion produced no .bin file" >&2
    exit 1
fi
OUT_F16="$OUT_DIR/ggml-$SLUG.bin"
mv "$CONVERTED" "$OUT_F16"
echo "==> Wrote $OUT_F16 ($(du -h "$OUT_F16" | cut -f1))"

FINAL="$OUT_F16"
if [[ "$QUANTIZE" != "none" ]]; then
    QUANT_BIN="$WHISPER_CPP/build/bin/whisper-quantize"
    if [[ ! -x "$QUANT_BIN" ]]; then
        echo "==> Building the whisper.cpp quantize tool"
        cmake -S "$WHISPER_CPP" -B "$WHISPER_CPP/build" -DCMAKE_BUILD_TYPE=Release >/dev/null
        cmake --build "$WHISPER_CPP/build" --target whisper-quantize -j"$(nproc)" >/dev/null
    fi
    if [[ ! -x "$QUANT_BIN" ]]; then
        echo "warning: could not build whisper-quantize; keeping the f16 model" >&2
        QUANTIZE=none
    fi
    if [[ "$QUANTIZE" != "none" ]]; then
        OUT_Q="$OUT_DIR/ggml-$SLUG-$QUANTIZE.bin"
        "$QUANT_BIN" "$OUT_F16" "$OUT_Q" "$QUANTIZE"
        echo "==> Wrote $OUT_Q ($(du -h "$OUT_Q" | cut -f1))"
        FINAL="$OUT_Q"
    fi
fi

echo
echo "Install it with:"
echo "  adb push $FINAL /sdcard/Download/"
echo "then import it from Tahsin > Settings > Import a .bin model from storage."
