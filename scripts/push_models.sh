#!/usr/bin/env bash
# 把三套端侧模型 adb push 到设备对应目录（猫娘聊天 App 运行前必跑一次）。
#
# 关键：本仓在 WSL，但用的是 Windows 版 adb.exe —— adb.exe 只认 Windows 路径
# （D:\...），读不了 WSL 的 /home 或 /mnt/d。所以源模型必须放在 D 盘，脚本里
# 用反斜杠 Windows 路径喂给 adb.exe push。
#
# 用法:
#   bash scripts/push_models.sh
#   ADB='/mnt/d/softwares/platform-tools/adb.exe' DEVICE=xxxx bash scripts/push_models.sh
#
# 设备目标目录（App 私有目录，统一在 models/ 下；v0.4 起 App 改从这里读，免存储权限）:
#   LLM  -> /sdcard/Android/data/com.neko.chat/files/models/llm/qwen35_08b_nekoneko-MNN/
#   ASR  -> /sdcard/Android/data/com.neko.chat/files/models/asr/
#   TTS  -> /sdcard/Android/data/com.neko.chat/files/models/tts/
# 说明：正式分发走 App 内「Neko 能力中心」下载；本脚本仅供开发预置，省得每次真机重下。
set -euo pipefail

ADB="${ADB:-/mnt/d/softwares/platform-tools/adb.exe}"
DEVICE="${DEVICE:-10AG5D10H90089M}"
A() { "$ADB" -s "$DEVICE" "$@"; }

# ---- 源路径：Windows 反斜杠路径（adb.exe 用），按你机器实际位置改 ----
# LLM / ASR 原本就在 D:\down；TTS 用 scripts/stage_models 拷到了 models-staging。
LLM_SRC='D:\down\qwen35_08b_nekoneko-MNN'
ASR_SRC='D:\down\sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20'
TTS_SRC='D:\Dev\apps\android\NekoChatMini\models-staging\tts'

# ---- 设备目标（App 私有目录，统一根 models/）----------------------------
MODELS_ROOT="/sdcard/Android/data/com.neko.chat/files/models"
LLM_DST="$MODELS_ROOT/llm/qwen35_08b_nekoneko-MNN"
ASR_DST="$MODELS_ROOT/asr"
TTS_DST="$MODELS_ROOT/tts"

echo "== 设备: $DEVICE =="
A shell mkdir -p "$LLM_DST" "$ASR_DST" "$TTS_DST"

echo "== push LLM (必须含 visual.*：该模型 llm_config.json is_visual=true，"
echo "   MNN Llm::load 会强制读 visual.mnn，缺则整体加载失败，即使纯文本聊天) =="
for f in config.json llm.mnn llm.mnn.json llm.mnn.weight llm_config.json tokenizer.mtok visual.mnn visual.mnn.weight; do
  A push "$LLM_SRC\\$f" "$LLM_DST/"
done

echo "== push ASR =="
for f in encoder-epoch-99-avg-1.int8.mnn decoder-epoch-99-avg-1.int8.mnn joiner-epoch-99-avg-1.int8.mnn tokens.txt; do
  A push "$ASR_SRC\\$f" "$ASR_DST/"
done

echo "== push TTS =="
for f in dp.mnn te.mnn ve.mnn vocoder.mnn catgirl_style.json; do
  A push "$TTS_SRC\\$f" "$TTS_DST/"
done

echo "== 完成。App v0.4 从私有目录读，无需存储权限；首次录音时授予麦克风权限即可 =="
