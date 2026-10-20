package com.gow3.android.data.driver

import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** 内置驱动源目录中的一个源：公开的 GitHub release 渠道，纯数据、无任何驱动二进制。 */
internal data class DriverSource(
    val id: String,
    val name: String,
    val description: String,
    val apiUrl: String,
    val repoUrl: String,
    val gpuVendor: String,
)

/**
 * 内置驱动源目录（assets `driver_sources.json`，形态与参考工具 DriverScope 的 sources
 * 目录同构）。加载或解析失败返回 null，由 UI 呈现「源列表不可用」的降级态，
 * 不影响 launcher 其他功能（spec：源目录损坏时降级）。
 */
internal class DriverSourceCatalog internal constructor(val sources: List<DriverSource>) {

    companion object {

        const val ASSET_NAME = "driver_sources.json"

        /** 从 assets 加载；[open] 注入使本函数可测（生产侧传 `context.assets::open`）。 */
        fun load(open: (String) -> InputStream): DriverSourceCatalog? = try {
            open(ASSET_NAME).use { stream -> parse(stream.bufferedReader().readText()) }
        } catch (_: Exception) {
            null
        }

        /**
         * 解析源目录 JSON：整体结构或类型不符返回 null；单个条目缺 id/apiUrl 等关键
         * 字段时跳过该条目，缺描述等展示字段时容错为空串。
         */
        fun parse(json: String): DriverSourceCatalog? {
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val array = try {
                root.optJSONArray("sources") ?: return null
            } catch (_: Exception) {
                return null
            }
            val sources = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val apiUrl = item.optString("apiUrl").takeIf { it.isNotBlank() } ?: continue
                    add(
                        DriverSource(
                            id = id,
                            name = item.optString("name").takeIf { it.isNotBlank() } ?: id,
                            description = item.optString("description").orEmpty(),
                            apiUrl = apiUrl,
                            repoUrl = item.optString("repoUrl").orEmpty(),
                            gpuVendor = item.optString("gpuVendor").orEmpty(),
                        ),
                    )
                }
            }
            return DriverSourceCatalog(sources)
        }
    }
}
