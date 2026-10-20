package com.gow3.android.data.model

/**
 * 本项目 GitHub 仓库的唯一地址来源。
 *
 * About 页的 star / issues 入口与检测更新的 release 查询都从这里取值，避免出现两份
 * 互相独立、可能指向不同仓库的常量。检测更新依赖该仓库确实发布 Android release：
 * tag 形如 `v<PC版本>-bionic-v<N>`（历史 release 为 `v<PC版本>-android<N>`），
 * 资产为 `God.of.War.III-<version>.apk` 与同名
 * `.apk.sha256`。发布目标由 `tools/android/release_local.sh` 的 `--repo` /
 * `GOW3_RELEASE_REPO` 决定，若改指向其它仓库，这里的取值必须同步。
 */
internal object ProjectRepo {
    const val OWNER = "zFitness"
    const val NAME = "gow3_android"

    val URL: String get() = "https://github.com/$OWNER/$NAME"
    val ISSUES_URL: String get() = "$URL/issues"

    /** 检测更新读取的最新 release；未认证请求私有仓库会返回 404。 */
    val LATEST_RELEASE_API: String get() = "https://api.github.com/repos/$OWNER/$NAME/releases/latest"
}
