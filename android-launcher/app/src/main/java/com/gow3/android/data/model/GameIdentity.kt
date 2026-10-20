package com.gow3.android.data.model

import com.gow3.android.data.CatalogProblem
import com.gow3.android.data.PatchCatalog
import com.gow3.android.data.PatchCatalogException
import com.gow3.android.R

import java.io.File
import java.io.InputStream

/**
 * 游戏身份卡的状态模型（change redesign-android-launcher-home）：由游戏目录与升级补丁
 * 目录的 catalog 解析结果驱动，健康 / 未选目录 / 无法读取三态。
 *
 * 与旧实现的差别：`PatchCatalog.load` 的失败原因（`CatalogProblem`）不再被
 * `runCatching { }.getOrNull()` 吞掉，「无法读取」状态可以给出可理解的原因并引导
 * 玩家去 Settings 存储分类重新选择目录。
 */
internal sealed interface GameIdentityState {

    /** 解析进行中：身份卡呈加载态，不阻塞页面其它区域。 */
    data object Loading : GameIdentityState

    /** 健康：游戏目录与版本受支持，补丁目录解析成功。 */
    data class Ready(val catalog: PatchCatalog) : GameIdentityState

    /** 无法读取 / 未选目录：携带失败原因供 UI 给出可理解文案。 */
    data class Failed(val problem: CatalogProblem) : GameIdentityState
}

/** [GameIdentityState] 的解析入口；纯 JVM 可测（目录与 asset 打开器均由调用方注入）。 */
internal object GameIdentity {

    /**
     * 解析游戏身份。[gameDir] 为空直接归为 [CatalogProblem.NO_GAME]（防御态：正常链路
     * 经首启选择页不会出现）；解析失败统一收敛为 [GameIdentityState.Failed]，其中非
     * 版本类 IO 异常（asset 缺失、文件不可读）归为 [CatalogProblem.READ_FAILED]，
     * 不向上抛出——展示层状态机不允许被解析异常打断。
     */
    fun load(gameDir: String?, updateDir: String?, open: (String) -> InputStream): GameIdentityState {
        if (gameDir.isNullOrBlank()) return GameIdentityState.Failed(CatalogProblem.NO_GAME)
        val update = updateDir?.takeIf { it.isNotBlank() }?.let(::File)
        return try {
            GameIdentityState.Ready(PatchCatalog.load(File(gameDir), update, open))
        } catch (e: PatchCatalogException) {
            GameIdentityState.Failed(e.problem)
        } catch (_: Exception) {
            GameIdentityState.Failed(CatalogProblem.READ_FAILED)
        }
    }

    /** 失败原因对应的用户可见文案资源；Settings 存储行与 Home 身份卡共用同一套文案。 */
    fun problemMessageRes(problem: CatalogProblem): Int = when (problem) {
        CatalogProblem.NO_GAME -> R.string.home_problem_no_game
        CatalogProblem.INVALID_GAME -> R.string.home_problem_invalid_game
        CatalogProblem.INVALID_UPDATE -> R.string.home_problem_invalid_update
        CatalogProblem.UNSUPPORTED -> R.string.home_problem_unsupported
        CatalogProblem.VERSION -> R.string.home_problem_version
        CatalogProblem.READ_FAILED -> R.string.home_problem_read_failed
    }
}
