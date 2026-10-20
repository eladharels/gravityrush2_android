#!/usr/bin/env bash
# 在本地构建机上打包并发布 Android release 到 GitHub。
# 版本号跟随 PC 上游 tag；签名材料沿用 CI 的同一套环境变量，支持 Base64 形式的 keystore。
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
root=$PWD

gradle_file=android-launcher/app/build.gradle.kts
release_dir=out/release
temp_keystore_dir=.local-deps/android/release

# 已知 PC 上游仓库：Android release 绝不能发布到这些仓库
pc_repos='zFitness/gow3_pc
R-V-L/gow3_pc
FelipePrado0/gow3_pc'

repo=''
upstream_remote=upstream-rvl
dry_run=0
allow_dev_signing=0
skip_build=0

# 本次发布实际使用的签名方式，写入 release notes
signing_mode='unset'
temp_keystore=''
keystore_owned=0

log() { printf '%s\n' "$*" >&2; }

die() {
    printf 'release_local: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat >&2 <<'EOF'
usage: bash tools/android/release_local.sh [options]

options:
  --repo <owner/name>       发布目标仓库；也可用 GOW3_RELEASE_REPO
  --upstream-remote <name>  读取 PC 上游 tag 的 git remote，默认 upstream-rvl
  --allow-dev-signing       允许用本地开发 keystore 签名发布
  --skip-build              跳过打包与签名，直接发布已存在的 APK 产物
  --dry-run                 只打印将要发布的版本、tag、仓库与产物，不改动工作树
  -h, --help                显示本帮助

签名材料（只从环境变量读取，不要作为命令行参数传入）：
  ANDROID_KEYSTORE_BASE64   keystore 的 Base64 内容
  ANDROID_KEYSTORE_PASSWORD keystore 口令
  ANDROID_KEY_ALIAS         key alias
  ANDROID_KEY_PASSWORD      key 口令

或直接提供已有 keystore 文件：
  GOW3_KEYSTORE             keystore 文件路径
  GOW3_KEYSTORE_PASSWORD / GOW3_KEY_ALIAS / GOW3_KEY_PASSWORD
EOF
}

cleanup() {
    if [[ $keystore_owned -eq 1 && -n $temp_keystore && -f $temp_keystore ]]; then
        rm -f -- "$temp_keystore"
        log "release_local: removed temporary keystore"
    fi
}
trap cleanup EXIT

# 还原版本改写：只有本次运行改写过才还原
restore_version=''

restore_version_file() {
    if [[ -n $restore_version ]]; then
        printf '%s\n' "$restore_version" > "$gradle_file"
        restore_version=''
        log "release_local: restored $gradle_file"
    fi
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --repo)
            [[ $# -ge 2 ]] || die '--repo requires <owner/name>'
            repo=$2
            shift 2
            ;;
        --upstream-remote)
            [[ $# -ge 2 ]] || die '--upstream-remote requires <name>'
            upstream_remote=$2
            shift 2
            ;;
        --allow-dev-signing)
            allow_dev_signing=1
            shift
            ;;
        --skip-build)
            skip_build=1
            shift
            ;;
        --dry-run)
            dry_run=1
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage
            die "unknown option: $1"
            ;;
    esac
done

# ---------- 前置检查：全部只读，必须早于任何写操作 ----------

check_gh() {
    command -v gh >/dev/null 2>&1 ||
        die 'gh CLI not found; install it first (macOS: brew install gh) and run: gh auth login'
    gh auth status >/dev/null 2>&1 ||
        die 'gh is not logged in or the credentials are invalid; run: gh auth login'
}

# 只比较版本号各段的数值大小
version_key() {
    local v=$1 part out=''
    IFS='.' read -r -a parts <<< "$v"
    for part in "${parts[@]}"; do
        out+=$(printf '%010d' "$part")
    done
    printf '%s' "$out"
}

resolve_pc_version() {
    local tags line pc best='' best_key=''
    tags=$(git ls-remote --tags "$upstream_remote" 2>/dev/null) ||
        die "cannot read tags from remote '$upstream_remote'"
    while read -r line; do
        [[ $line == *refs/tags/* ]] || continue
        pc=${line##*refs/tags/}
        pc=${pc%\^\{\}}
        if [[ $pc =~ ^v([0-9]+(\.[0-9]+)*)-linux[0-9]+$ ]]; then
            local candidate=${BASH_REMATCH[1]} key
            key=$(version_key "$candidate")
            if [[ -z $best || $key > $best_key ]]; then
                best=$candidate
                best_key=$key
            fi
        fi
    done <<< "$tags"
    [[ -n $best ]] ||
        die "no upstream tag matching 'v<version>-linux<N>' on '$upstream_remote';
       remotes that do have matching tags: $(remotes_with_pc_tags)"
    printf '%s' "$best"
}

remotes_with_pc_tags() {
    local name found=''
    while read -r name; do
        [[ -n $name ]] || continue
        if git ls-remote --tags "$name" 2>/dev/null |
            grep -Eq 'refs/tags/v[0-9]+(\.[0-9]+)*-linux[0-9]+\^?\{?\}?$'; then
            found+="${found:+, }$name"
        fi
    done < <(git remote)
    printf '%s' "${found:-none}"
}

# Android tag 统计在目标仓库上进行：PC 上游仓库永远不含 v*-bionic-v<N>
# bionic 管线起 tag 形态为 v<pc>-bionic-v<N>；旧形态 v<pc>-android<N> 不再生成，
# 仅由 launcher 的 UpdateChecker 兼容识别历史 release。
next_android_tag() {
    local pc=$1 target=$2 tag n best=0
    while read -r tag; do
        [[ -n $tag ]] || continue
        if [[ $tag =~ ^v${pc}-bionic-v([0-9]+)$ ]]; then
            n=${BASH_REMATCH[1]}
            ((10#$n > best)) && best=$((10#$n))
        fi
    done < <(gh api "repos/$target/tags" --paginate --jq '.[].name' 2>/dev/null || true)
    printf 'v%s-bionic-v%d' "$pc" "$((best + 1))"
}

target_repo_has_tag() {
    local tag=$1 target=$2 name
    while read -r name; do
        [[ $name == "$tag" ]] && return 0
    done < <(gh api "repos/$target/tags" --paginate --jq '.[].name' 2>/dev/null || true)
    return 1
}

check_no_tag_conflict() {
    if target_repo_has_tag "$android_tag" "$target_repo"; then
        die "tag '$android_tag' already exists in '$target_repo'; refusing to overwrite it"
    fi
}

remote_to_repo() {
    local url=$1
    url=${url%.git}
    case $url in
        # https://host/owner/repo[.git] 或 ssh://host/owner/repo
        *://*)
            url=${url#*://}
            url=${url#*@}
            url=${url#*/}
            ;;
        # SCP 形式 git@host:owner/repo
        *:*)
            url=${url##*:}
            ;;
    esac
    url=${url#/}
    url=${url%/}
    [[ $url == */* ]] || return 1
    printf '%s' "$url"
}

resolve_repo() {
    local candidate=$1 known
    if [[ -z $candidate ]]; then
        candidate=${GOW3_RELEASE_REPO:-}
    fi
    if [[ -z $candidate ]]; then
        local url
        if url=$(git remote get-url private 2>/dev/null); then
            candidate=$(remote_to_repo "$url") || candidate=''
        fi
    fi
    [[ -n $candidate ]] ||
        die 'cannot determine the release repository; pass --repo owner/name or set GOW3_RELEASE_REPO'
    while read -r known; do
        [[ -n $known ]] || continue
        if [[ $candidate == "$known" ]]; then
            die "refusing to publish the Android release to the PC upstream repository '$candidate'"
        fi
    done <<< "$pc_repos"
    printf '%s' "$candidate"
}

check_inputs() {
    local missing=0 d
    for d in out/bionic/bin/gow3-probe out/bionic/lib/libgow3cpu.so \
        out/bionic/lib/libgow3gpu.so; do
        [[ -e $d ]] && continue
        log "release_local: missing build input: $d"
        missing=1
    done
    ((missing == 0)) ||
        die 'required Bionic build inputs are missing; run tools/android/build_bionic.sh before packaging a release'
}

check_version_file_clean() {
    git diff --quiet -- "$gradle_file" 2>/dev/null ||
        die "$gradle_file has uncommitted changes; commit or stash them before releasing"
}

check_gh
pc_version=$(resolve_pc_version)
target_repo=$(resolve_repo "$repo")
# 目标仓库先于 tag 计数解析：Android tag 计数在目标仓库上统计
android_tag=$(next_android_tag "$pc_version" "$target_repo")
base_commit=$(git rev-parse HEAD)
upstream_line=$(git ls-remote "$upstream_remote" HEAD 2>/dev/null | cut -f1)
upstream_line=${upstream_line:-unknown}
# tag 带 v 前缀，versionName 不带：APK 内部版本与 tag 保持同一个版本串
version=${android_tag#v}
apk_name="God of War III-bionic-$version.apk"
apk_path="$release_dir/$apk_name"
checksum_path="$apk_path.sha256"

if ((skip_build == 1)); then
    log 'release_local: build inputs are not checked in --skip-build mode'
else
    check_inputs
    check_version_file_clean
fi
check_no_tag_conflict

log "release_local: PC upstream version   : $pc_version"
log "release_local: upstream HEAD          : $upstream_line"
log "release_local: release tag            : $android_tag"
log "release_local: target repository       : $target_repo"
log "release_local: release commit          : $base_commit"
log "release_local: release artifacts       : $apk_path"

if ((dry_run == 1)); then
    log 'release_local: dry run, no changes made and nothing pushed'
    exit 0
fi

# ---------- 签名材料 ----------

prepare_signing() {
    if [[ -n ${GOW3_KEYSTORE:-} ]]; then
        [[ -f $GOW3_KEYSTORE ]] || die "GOW3_KEYSTORE does not exist: $GOW3_KEYSTORE"
        : "${GOW3_KEYSTORE_PASSWORD:?GOW3_KEYSTORE_PASSWORD is required with GOW3_KEYSTORE}"
        : "${GOW3_KEY_ALIAS:?GOW3_KEY_ALIAS is required with GOW3_KEYSTORE}"
        : "${GOW3_KEY_PASSWORD:?GOW3_KEY_PASSWORD is required with GOW3_KEYSTORE}"
        signing_mode="provided keystore file ($(basename "$GOW3_KEYSTORE"))"
        return
    fi

    if [[ -n ${ANDROID_KEYSTORE_BASE64:-} ]]; then
        : "${ANDROID_KEYSTORE_PASSWORD:?ANDROID_KEYSTORE_PASSWORD is required with ANDROID_KEYSTORE_BASE64}"
        : "${ANDROID_KEY_ALIAS:?ANDROID_KEY_ALIAS is required with ANDROID_KEYSTORE_BASE64}"
        : "${ANDROID_KEY_PASSWORD:?ANDROID_KEY_PASSWORD is required with ANDROID_KEYSTORE_BASE64}"
        mkdir -p "$temp_keystore_dir"
        temp_keystore="$temp_keystore_dir/release.keystore"
        bash tools/android/inputs/prepare_signing.sh "$temp_keystore" ||
            die 'signing keystore validation failed'
        export GOW3_KEYSTORE=$temp_keystore
        export GOW3_KEYSTORE_PASSWORD=$ANDROID_KEYSTORE_PASSWORD
        export GOW3_KEY_ALIAS=$ANDROID_KEY_ALIAS
        export GOW3_KEY_PASSWORD=$ANDROID_KEY_PASSWORD
        keystore_owned=1
        signing_mode='Base64 keystore decoded for this run'
        return
    fi

    if ((allow_dev_signing == 0)); then
        die 'no release signing material found. Export ANDROID_KEYSTORE_BASE64 / ANDROID_KEYSTORE_PASSWORD /
       ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD, or set GOW3_KEYSTORE with an existing keystore.
       An APK signed with the local development keystore cannot be replaced later by a formally signed
       build, so --allow-dev-signing is required to publish with it.'
    fi
    signing_mode='local development keystore (NOT a release signature)'
}

# ---------- 版本同步 ----------

apply_version() {
    local current new_code
    current=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$gradle_file")
    [[ -n $current ]] || die "cannot read versionName from $gradle_file"
    new_code=$(( $(sed -n 's/.*versionCode *= *\([0-9]\+\).*/\1/p' "$gradle_file") + 1 ))
    restore_version=$(cat "$gradle_file")
    trap 'restore_version_file; cleanup' EXIT
    sed -i.bak \
        -e "s/versionName *= *\"$current\"/versionName = \"$version\"/" \
        -e "s/versionCode *= *[0-9]\+/versionCode = $new_code/" \
        "$gradle_file"
    rm -f "$gradle_file.bak"
    grep -q "versionName = \"$version\"" "$gradle_file" ||
        die "failed to update versionName in $gradle_file"
    log "release_local: versionName            : $current -> $version"
    log "release_local: versionCode            : $new_code"
}

if ((skip_build == 1)); then
    [[ -f $apk_path ]] ||
        die "--skip-build was given but the APK is missing: $apk_path (run without --skip-build first)"
    [[ -f $checksum_path ]] || die "checksum file is missing: $checksum_path"
    signing_mode="prebuilt artifact, not signed by this run"
    bt=${GOW3_ANDROID_SDK:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
    if [[ -x $bt/build-tools/35.0.0/apksigner ]]; then
        dn=$("$bt/build-tools/35.0.0/apksigner" verify --print-certs "$apk_path" 2>/dev/null |
            sed -n 's/.*certificate DN: //p' | head -1)
        [[ -n $dn ]] && signing_mode="prebuilt artifact signed by $dn"
    fi
    log "release_local: signing source          : $signing_mode"
else
    prepare_signing
    log "release_local: signing source          : $signing_mode"

    apply_version

    # OpenSpec 8.2 的 Bionic packer：stage payload -> assembleRelease -> 签名 -> 校验 -> sha256，
    # 产物名 God of War III-bionic-<version>.apk，落在本脚本上面约定的 apk_path。
    bash tools/android/apk/build_release_apk.sh

    [[ -f $apk_path ]] || die "release APK was not produced: $apk_path"
    [[ -f $checksum_path ]] || die "release checksum was not produced: $checksum_path"
fi

notes=$(cat <<EOF
God of War III Android release \`$android_tag\`

- PC upstream version: \`$pc_version\`
- PC upstream HEAD: \`$upstream_line\`
- Release commit: \`$base_commit\`
- Signing: $signing_mode
- ABI: \`arm64-v8a\`

Published from a local build machine. The build, packaging, signature and checksum checks ran locally;
device installation, Vulkan, Turnip/GPU compatibility and gameplay were NOT verified by this release.
EOF
)

gh release create "$android_tag" \
    --repo "$target_repo" \
    --target "$base_commit" \
    --title "$android_tag" \
    --notes "$notes" \
    --generate-notes \
    "$apk_path" "$checksum_path"

log "release_local: published $android_tag to $target_repo"
gh release view "$android_tag" --repo "$target_repo" --json url \
    | sed -n 's/.*"url": *"\([^"]*\)".*/release_local: release URL        : \1/p'

# 版本改动有意保留在工作树，由维护者自行决定是否提交
trap 'cleanup' EXIT
log 'release_local: done; version bump left uncommitted in the working tree'
