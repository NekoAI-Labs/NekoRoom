#!/usr/bin/env bash
# 生成 NekoChatMini 纯净版源码 zip，两种模式：
#
#   bash scripts/make_clean_zip.sh               # 纯净可编译版：保留三方底座/so/角色资产，
#                                                #   只剔除构建产物（默认）
#   bash scripts/make_clean_zip.sh code-only     # 纯代码版：无任何大文件，
#                                                #   二进制/大文件全部换成同路径占位符
#
# 注意：不能直接 --exclude-from .gitignore，因为现有 .gitignore 把 /live2d/ 和
#       /sherpa/ 整目录排除了（它们是"从兄弟项目 rsync 拷贝、不入 git"的三方模块），
#       而纯净版 zip 恰恰必须包含这两个模块的集成代码。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_NAME="$(basename "$ROOT")"

VERSION="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle" | head -1)"
VERSION="${VERSION:-unknown}"
VERSION_CODE="$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$ROOT/app/build.gradle" | head -1)"
VERSION_CODE="${VERSION_CODE:-unknown}"

MODE="${1:-buildable}"
case "$MODE" in
  buildable) LABEL="纯净可编译版" ;;
  code-only) LABEL="纯代码版（大文件→占位符）" ;;
  *) echo "用法: make_clean_zip.sh [buildable|code-only] [输出目录]" >&2; exit 1 ;;
esac

OUT_DIR="${2:-$ROOT/../..}"
STAMP="$(date +%Y%m%d-%H%M%S)"
if [ "$MODE" = "code-only" ]; then
  ZIP_NAME="${PROJECT_NAME}-code-only-${VERSION}.zip"
else
  ZIP_NAME="${PROJECT_NAME}-clean-${VERSION}.zip"
fi
OUT_ZIP="$OUT_DIR/$ZIP_NAME"

STAGE="$(mktemp -d /tmp/nekochat-clean.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT
DST="$STAGE/$PROJECT_NAME"

echo "==> 模式: $LABEL"
echo "==> 源目录: $ROOT"
echo "==> 输出:   $OUT_ZIP"

# 1. 全量拷贝，剔除构建产物 / 缓存 / 密钥 / 本地配置。
rsync -a \
  --exclude '.git' \
  --exclude '.gradle' \
  --exclude 'build' \
  --exclude '.cxx' \
  --exclude 'local.properties' \
  --exclude '.idea' \
  --exclude '*.iml' \
  --exclude '*.apk' \
  --exclude '*.aab' \
  --exclude '*.keystore' \
  --exclude '*.jks' \
  --exclude 'models-staging' \
  "$ROOT/" "$DST/"

# 2. 兜底：再次强制剔除任何漏网的构建产物。
find "$DST" \( -type d -name build -o -type d -name .cxx -o -type d -name .gradle \) -prune -exec rm -rf {} +

if [ "$MODE" = "code-only" ]; then
  # ============ 纯代码版 ============
  # 2a. 去掉第三方 SDK 源码（Cubism SDKRoot，15MB），换成说明占位。
  if [ -d "$DST/live2d/src/SDKRoot" ]; then
    find "$DST/live2d/src/SDKRoot" -depth -delete
    mkdir -p "$DST/live2d/src/SDKRoot"
    cat > "$DST/live2d/src/SDKRoot/README.txt" <<'EOF'
PLACEHOLDER: 官方 Cubism SDK 5-r.5 源码 + Core 预编译库（原 ~15MB），
纯代码版不包含。来源：AvatarLive2DMini/live2d/src/SDKRoot/
（本工程 live2d 模块的渲染底座，编译 :live2d 必需，按 README.md「从零复现」步骤 2 拷入）。
目录结构：Framework/（官方框架源码）+ Core/（lib/dll 预编译库）。
EOF
  fi

  # 2b. 收集"应替换为占位符"的文件清单（来自源目录，用于生成 PLACEHOLDERS.md）。
  #     规则：
  #       a) 二进制扩展名（so/a/lib/moc3/wav/...），无论大小；
  #       b) 体积 > 100KB 的**非源码/非文档**文件（贴图、运行时数据 JSON 等）。
  #     源码与文档（kt/java/cpp/h/md/xml/gradle/sh/py/patch...）永不占位。
  SRC_KEEP=( -name '*.kt' -o -name '*.java' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' \
             -o -name '*.cc' -o -name '*.c' -o -name '*.gradle' -o -name '*.properties' \
             -o -name '*.md' -o -name '*.txt' -o -name '*.xml' -o -name '*.sh' -o -name '*.py' \
             -o -name '*.patch' -o -name '*.cmake' -o -name '*.pro' -o -name '*.mk' \
             -o -name '*.yml' -o -name '*.yaml' )
  PLACEHOLDER_LIST="$(mktemp /tmp/nekochat-phlist.XXXXXX)"
  {
    find "$ROOT" -type f \( -name '*.so' -o -name '*.a' -o -name '*.lib' -o -name '*.dylib' \
      -o -name '*.moc3' -o -name '*.cmo3' -o -name '*.can3' \
      -o -name '*.wav' -o -name '*.mp3' -o -name '*.m4a' -o -name '*.ogg' -o -name '*.aac' -o -name '*.flac' \
      -o -name '*.mp4' -o -name '*.webm' \
      -o -name '*.png' -o -name '*.jpg' -o -name '*.jpeg' -o -name '*.webp' -o -name '*.gif' -o -name '*.bmp' -o -name '*.ico' \
      -o -name '*.mnn' -o -name '*.onnx' -o -name '*.bin' \) \
      -not -path '*/build/*' -not -path '*/.cxx/*' -not -path '*/.gradle/*' \
      -not -path '*/.git/*' -not -path '*/SDKRoot/*' -not -path '*/models-staging/*' \
      -printf '%p\t%s\n'
    find "$ROOT" -type f -size +100k ! \( "${SRC_KEEP[@]}" \) \
      -not -path '*/build/*' -not -path '*/.cxx/*' -not -path '*/.gradle/*' \
      -not -path '*/.git/*' -not -path '*/SDKRoot/*' -not -path '*/models-staging/*' \
      -printf '%p\t%s\n'
  } | sort -u > "$PLACEHOLDER_LIST"

  # 2c. 把拷贝进 stage 的对应文件原位替换成占位符（保留路径与文件名）。
  while IFS=$'\t' read -r orig size; do
    rel="${orig#"$ROOT"/}"
    target="$DST/$rel"
    [ -f "$target" ] || continue
    printf 'PLACEHOLDER: 原文件为二进制/大文件（原大小约 %s），未纳入纯代码版。\n原始路径: %s\n详见 PLACEHOLDERS.md。\n' \
      "$(numfmt --to=iec --suffix=B "$size" 2>/dev/null || echo "$size B")" \
      "$rel" > "$target"
  done < "$PLACEHOLDER_LIST"

  # 2d. 生成占位符总清单 PLACEHOLDERS.md。
  {
    echo "# PLACEHOLDERS.md — 纯代码版占位符清单"
    echo
    echo "纯代码版不包含二进制/大文件，以下路径均以同名的文本占位符存在（保留目录结构）。"
    echo "如需完整可编译工程，请用 \`bash scripts/make_clean_zip.sh\`（纯净可编译版），"
    echo "或按 README.md「从零复现」从兄弟项目/模型源补齐。"
    echo
    echo "| 路径 | 原大小 | 来源 |"
    echo "|---|---|---|"
    while IFS=$'\t' read -r orig size; do
      rel="${orig#"$ROOT"/}"
      src_hint="—"
      case "$rel" in
        app/src/main/jniLibs/*)            src_hint="MoeAvatarPro/app/src/main/jniLibs/arm64-v8a/（MNN 编译产物）" ;;
        sherpa/src/main/jniLibs/*)         src_hint="MoeAvatarPro/sherpa/src/main/jniLibs/arm64-v8a/（sherpa-mnn 预编译）" ;;
        live2d/src/main/assets/*)          src_hint="AvatarLive2DMini/live2d/src/main/assets/Live2DModels/（角色贴图/moc3）" ;;
        app/src/main/assets/room_default.png) src_hint="README 步骤 5：cp imgs/room.png → app/src/main/assets/room_default.png" ;;
        app/src/main/assets/*.json)        src_hint="运行时数据（拼音/unicode 词典），原文件在 git 或上游 SuperTonicMini" ;;
        experiments/*.wav)                 src_hint="由 experiments/tts_step_benchmark/scripts/benchmark_steps.py 重新生成" ;;
        experiments/*)                     src_hint="实验产物（report.json），可由 benchmark 脚本重新生成" ;;
      esac
      printf '| `%s` | %s | %s |\n' "$rel" \
        "$(numfmt --to=iec --suffix=B "$size" 2>/dev/null || echo "$size B")" \
        "$src_hint"
    done < "$PLACEHOLDER_LIST"
  } > "$DST/PLACEHOLDERS.md"
  rm -f "$PLACEHOLDER_LIST"

  # 2e. 纯代码版说明。
  cat > "$DST/MANIFEST.txt" <<EOF
NekoChatMini 纯代码版源码归档
=============================
版本      : v$VERSION (versionCode $VERSION_CODE)
来源目录  : $ROOT
生成时间  : $STAMP

内容      : 本项目全部源码（app/live2d/sherpa 的 Kotlin/Java/C++/Gradle/资源 xml）
           + docs/ experiments/ scripts/ patches/ 文档与工具。
不含      : 任何二进制/大文件。.so/.a/.moc3/贴图/wav/大数据 JSON 等一律以
           同路径文本占位符替代（见 PLACEHOLDERS.md）。
编译      : 本版本不保证可编译，仅用于代码阅读/集成梳理。需要编译请使用
           \`bash scripts/make_clean_zip.sh\` 生成的 NekoChatMini-clean-*.zip。
EOF

  # 2f. 换一份"干净版" .gitignore。
  cat > "$DST/.gitignore" <<'EOF'
# 构建产物
.gradle/
build/
**/build/
**/.cxx/
local.properties
*.iml
.idea/

# APK / 签名
*.apk
*.aab
*.keystore
*.jks

# 端侧模型权重（运行时按需下载 / adb push，见 README）
*.mnn
*.onnx
*.bin
models-staging/
EOF

else
  # ============ 纯净可编译版 ============
  cat > "$DST/.gitignore" <<'EOF'
# 构建产物
.gradle/
build/
**/build/
**/.cxx/
local.properties
*.iml
.idea/

# APK / 签名
*.apk
*.aab
*.keystore
*.jks

# 端侧模型权重（运行时按需下载 / adb push，见 README）
*.mnn
*.onnx
*.bin
models-staging/
EOF

  cat > "$DST/MANIFEST.txt" <<EOF
NekoChatMini 纯净版源码归档
===========================
版本      : v$VERSION (versionCode $VERSION_CODE)
来源目录  : $ROOT
生成时间  : $STAMP

内容      : app + live2d + sherpa 三个 module 全部源码、gradle 骨架与 wrapper、
           docs/ experiments/ scripts/ patches/，以及编译运行必需的三方底座
           （Cubism SDK5 源码与 Core 库、Live2D 角色资产、MNN/sherpa 预编译 .so）。
已剔除    : build/ .cxx/ .gradle/ .git/ APK/keystore/local.properties 等构建产物。

构建      : 见 README.md「从零复现」。本 zip 不含模型权重(.mnn/.bin)与签名文件；
           编译前在本机补 local.properties（sdk.dir=...）。
EOF
fi

# 3. 打 zip（第一层就是项目目录名）。
(cd "$STAGE" && zip -qr "$OUT_ZIP" "$PROJECT_NAME")

echo "==> 完成: $(du -sh "$OUT_ZIP" | cut -f1)  $OUT_ZIP"
