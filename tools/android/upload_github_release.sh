#!/usr/bin/env bash
# 上传已有 Android APK 到 GitHub Release。
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
用法：
  GITHUB_TOKEN=... bash tools/android/upload_github_release.sh \
    --repo <owner/name> --tag <release-tag> --apk <apk-path> \
    [--filename-format <format>] [--checksum <sha256-file>] [--token <token>]

说明：
  --token             GitHub token；优先使用该参数，否则读取 GITHUB_TOKEN。
  --repo              GitHub 仓库，例如 zFitness/gow3_android_private。
  --tag               目标 Release tag；不存在时自动创建 Release。
  --apk               要上传的 APK 文件。
  --filename-format   上传后的文件名，默认使用 APK 原文件名。
  --checksum          可选的 SHA-256 文件；上传后文件名追加 .sha256。

文件名占位符：{basename}、{name}、{version}、{tag}
需要 curl、python3；token 需要对目标仓库有 Contents: write 权限。
EOF
}

die() {
    printf 'upload_github_release: %s\n' "$*" >&2
    exit 1
}

command -v curl >/dev/null 2>&1 || die 'curl 未安装'
command -v python3 >/dev/null 2>&1 || die 'python3 未安装'

token=${GITHUB_TOKEN:-}
repo=${GITHUB_RELEASE_REPO:-}
tag=${GITHUB_RELEASE_TAG:-}
apk=''
filename_format=''
checksum=''

while [[ $# -gt 0 ]]; do
    case $1 in
        --token) [[ $# -ge 2 ]] || die '--token 需要参数'; token=$2; shift 2 ;;
        --repo) [[ $# -ge 2 ]] || die '--repo 需要参数'; repo=$2; shift 2 ;;
        --tag) [[ $# -ge 2 ]] || die '--tag 需要参数'; tag=$2; shift 2 ;;
        --apk) [[ $# -ge 2 ]] || die '--apk 需要参数'; apk=$2; shift 2 ;;
        --filename-format) [[ $# -ge 2 ]] || die '--filename-format 需要参数'; filename_format=$2; shift 2 ;;
        --checksum) [[ $# -ge 2 ]] || die '--checksum 需要参数'; checksum=$2; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage; die "未知参数：$1" ;;
    esac
done

[[ -n $token ]] || die '请通过 --token 或 GITHUB_TOKEN 提供 token'
[[ $repo =~ ^[^/]+/[^/]+$ ]] || die "仓库格式必须是 owner/name：$repo"
[[ -n $tag ]] || die '请通过 --tag 或 GITHUB_RELEASE_TAG 提供 release tag'
[[ -n $apk ]] || die '请通过 --apk 提供 APK 路径'
[[ -f $apk ]] || die "APK 不存在：$apk"
[[ $apk == *.apk ]] || die "文件不是 .apk：$apk"
if [[ -n $checksum ]]; then [[ -f $checksum ]] || die "checksum 文件不存在：$checksum"; fi

api='https://api.github.com'
uploads='https://uploads.github.com'
auth_header="Authorization: Bearer $token"
accept_header='Accept: application/vnd.github+json'
api_version='X-GitHub-Api-Version: 2022-11-28'

api_get() {
    curl -fsS -H "$auth_header" -H "$accept_header" -H "$api_version" "$@"
}

json_field() {
    local field=$1
    python3 -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$field"
}

release_json=''
release_status=0
release_json=$(api_get "$api/repos/$repo/releases/tags/$tag" 2>/dev/null) || release_status=$?
if [[ $release_status -ne 0 ]]; then
    printf 'Release %s 不存在，正在创建……\n' "$tag" >&2
    create_payload=$(python3 - "$tag" <<'PY'
import json
import sys
print(json.dumps({
    "tag_name": sys.argv[1],
    "name": sys.argv[1],
    "draft": False,
    "prerelease": False,
    "generate_release_notes": True,
}))
PY
)
    release_json=$(curl -fsS -X POST \
        -H "$auth_header" -H "$accept_header" -H "$api_version" \
        -H 'Content-Type: application/json' --data "$create_payload" \
        "$api/repos/$repo/releases")
fi

release_id=$(printf '%s' "$release_json" | json_field id)
[[ $release_id =~ ^[0-9]+$ ]] || die 'GitHub API 未返回有效 release id'

basename_apk=${apk##*/}
name_apk=${basename_apk%.apk}
version=${tag#v}
filename_format=${filename_format:-$basename_apk}
filename=$filename_format
filename=${filename//\{basename\}/$basename_apk}
filename=${filename//\{name\}/$name_apk}
filename=${filename//\{version\}/$version}
filename=${filename//\{tag\}/$tag}
[[ -n $filename && $filename != */* && $filename != *\\* ]] || die "上传文件名必须是单个文件名：$filename"
[[ $filename == *.apk ]] || die "上传文件名必须以 .apk 结尾：$filename"

upload_asset() {
    local path=$1 name=$2 content_type=$3
    local encoded_name
    encoded_name=$(python3 -c 'import urllib.parse, sys; print(urllib.parse.quote(sys.argv[1]))' "$name")
    printf '上传 %s -> %s\n' "$path" "$name" >&2
    curl -fsS -X POST \
        -H "$auth_header" -H "$accept_header" -H "$api_version" \
        -H "Content-Type: $content_type" --data-binary "@$path" \
        "$uploads/repos/$repo/releases/$release_id/assets?name=$encoded_name" >/dev/null
}

upload_asset "$apk" "$filename" 'application/vnd.android.package-archive'
if [[ -n $checksum ]]; then upload_asset "$checksum" "$filename.sha256" 'text/plain'; fi

release_url=$(printf '%s' "$release_json" | json_field html_url)
printf 'GitHub Release 上传完成：%s\n' "${release_url:-$api/repos/$repo/releases/tag/$tag}"
