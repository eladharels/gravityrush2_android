package com.gow3.android.data.model

import com.gow3.android.data.CatalogProblem

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 游戏身份状态映射（change redesign-android-launcher-home）的纯 JVM 验证：
 * 目录缺失 / 缺 eboot / 缺 param.sfo 归因正确，就绪路径产出徽章所需字段，
 * 非版本类 IO 异常收敛为 READ_FAILED。UNSUPPORTED / VERSION 依赖受支持
 * TITLE_ID 清单，由真机走查（tasks 6.3）覆盖，不在纯 JVM 用例内伪造。
 */
class GameIdentityTest {

    @get:Rule val temp = TemporaryFolder()
    private val catalogs = File(requireNotNull(System.getProperty("gow3.patchCatalogDir")))

    @Test fun `null game dir maps to no_game`() {
        val state = GameIdentity.load(null, null) { throw AssertionError("不应触达 asset 打开") }
        assertEquals(GameIdentityState.Failed(CatalogProblem.NO_GAME), state)
    }

    @Test fun `blank game dir maps to no_game the same as null`() {
        val state = GameIdentity.load("  ", null) { throw AssertionError("不应触达 asset 打开") }
        assertEquals(GameIdentityState.Failed(CatalogProblem.NO_GAME), state)
    }

    @Test fun `game dir without eboot maps to invalid_game`() {
        val dir = temp.newFolder("no-eboot")
        val state = GameIdentity.load(dir.path, null) { throw AssertionError("不应触达 asset 打开") }
        assertEquals(GameIdentityState.Failed(CatalogProblem.INVALID_GAME), state)
    }

    @Test fun `game dir with eboot but no sfo maps to invalid_game`() {
        val dir = temp.newFolder("no-sfo")
        File(dir, "eboot.bin").writeText("synthetic executable")
        val state = GameIdentity.load(dir.path, null) { throw AssertionError("不应触达 asset 打开") }
        assertEquals(GameIdentityState.Failed(CatalogProblem.INVALID_GAME), state)
    }

    @Test fun `valid game dir resolves ready with identity fields`() {
        val dir = syntheticGame("ready", "CUSA01740", "01.00")
        val state = GameIdentity.load(dir.path, null) { File(catalogs, it).inputStream() }
        val catalog = (state as GameIdentityState.Ready).catalog
        assertEquals("CUSA01740", catalog.titleId)
        assertEquals("01.00", catalog.appVersion)
    }

    @Test fun `unreadable update folder maps to invalid_update and bad version to version`() {
        val base = syntheticGame("base", "CUSA01740", "01.00")
        val wrongTitle = syntheticGame("wrong", "CUSA01715", "01.02")
        val newer = syntheticGame("newer", "CUSA01740", "01.03")
        assertEquals(
            GameIdentityState.Failed(CatalogProblem.INVALID_UPDATE),
            GameIdentity.load(base.path, wrongTitle.path) { File(catalogs, it).inputStream() },
        )
        assertEquals(
            GameIdentityState.Failed(CatalogProblem.VERSION),
            GameIdentity.load(base.path, newer.path) { File(catalogs, it).inputStream() },
        )
    }

    @Test fun `asset open failure after valid identity falls back to read_failed`() {
        val dir = syntheticGame("read-fail", "CUSA01740", "01.00")
        val state = GameIdentity.load(dir.path, null) { throw java.io.IOException("asset missing") }
        assertEquals(GameIdentityState.Failed(CatalogProblem.READ_FAILED), state)
    }

    @Test fun `every problem has a localized message resource`() {
        for (problem in CatalogProblem.entries) {
            assertEquals(true, GameIdentity.problemMessageRes(problem) != 0)
        }
    }

    /** 与 PatchCatalogTest 相同的合成 SFO 构造（TITLE_ID/APP_VER 两键）。 */
    private fun syntheticGame(name: String, title: String, version: String): File {
        val dir = temp.newFolder(name)
        File(dir, "eboot.bin").writeText("synthetic executable")
        File(dir, "sce_sys").mkdir()
        val keys = "TITLE_ID\u0000APP_VER\u0000".toByteArray()
        val titleBytes = "$title\u0000".toByteArray()
        val versionBytes = "$version\u0000".toByteArray()
        val dataOffset = 20 + 32 + keys.size
        val b = ByteBuffer.allocate(dataOffset + titleBytes.size + versionBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46535000).putInt(0x101).putInt(52).putInt(dataOffset).putInt(2)
        b.putShort(0).putShort(0x204).putInt(titleBytes.size).putInt(titleBytes.size).putInt(0)
        b.putShort(9).putShort(0x204).putInt(versionBytes.size).putInt(versionBytes.size).putInt(titleBytes.size)
        b.put(keys).put(titleBytes).put(versionBytes)
        File(dir, "sce_sys/param.sfo").writeBytes(b.array())
        return dir
    }
}
