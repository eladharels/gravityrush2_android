import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEXTURE_CACHE_H = ROOT / "gpu/shadps4/video_core/texture_cache/texture_cache.h"
TEXTURE_CACHE_CPP = ROOT / "gpu/shadps4/video_core/texture_cache/texture_cache.cpp"
GOW3_TOGGLES_H = ROOT / "gpu/shim/gow3_toggles.h"
VK_RASTERIZER_CPP = ROOT / "gpu/shadps4/video_core/renderer_vulkan/vk_rasterizer.cpp"


def resolve_overlap_body() -> str:
    """提取 ResolveOverlap 函数体（从定义到下一个函数定义），便于做顺序断言。"""
    source = TEXTURE_CACHE_CPP.read_text()
    start = source.index("std::tuple<ImageId, int, int> TextureCache::ResolveOverlap(")
    end = source.find("\nImageId TextureCache::ResolveDepthOverlap", start)
    if end < 0:
        end = source.find("\nImageId TextureCache::", start)
    return source[start:end]


class PartialHeightViewContractTest(unittest.TestCase):
    """上游 b646683（partial-height integer view reuse）的移植契约。"""

    def test_toggle_bit_and_env_switch_exist(self):
        """场景：运行期开关——toggle bit 58 存在，GOW3_PARTIAL_HEIGHT_VIEWS 默认开启。"""
        toggles = GOW3_TOGGLES_H.read_text()
        self.assertIn("PartialHeightViews = 1ull << 58", toggles)
        body = resolve_overlap_body()
        self.assertIn("GOW3_PARTIAL_HEIGHT_VIEWS", body)
        # 默认开启：仅在显式传 0 时关闭
        self.assertIn("return !value || value[0] != '0';", body)
        self.assertIn("!Gow3Toggle::Disabled(Gow3Toggle::PartialHeightViews)", body)

    def test_reuse_requires_integer_view_and_same_layout(self):
        """场景：部分高度整数请求复用——仅另一格式的整数视图 + 同 pitch/tile/bpp + 更低高度。"""
        body = resolve_overlap_body()
        self.assertIn('numeric == "UINT" || numeric == "SINT"', body)
        self.assertIn("a.pixel_format != b.pixel_format", body)
        self.assertIn("is_integer(a.pixel_format)", body)
        self.assertIn("lhs_block_size == rhs_block_size", body)
        self.assertIn("a.pitch == b.pitch", body)
        self.assertIn("a.tile_mode == b.tile_mode", body)
        self.assertIn("a.size.height < b.size.height", body)

    def test_reuse_block_precedes_pool_rebuild_path(self):
        """场景：toggle 关闭/条件不满足时回到既有重建路径——复用块位于 BlockDim 判断之前。"""
        body = resolve_overlap_body()
        reuse = body.index("partial_views_enabled && !Gow3Toggle::Disabled")
        pool = body.index("image_info.BlockDim() != cache_image.info.BlockDim()")
        self.assertLess(reuse, pool)
        # 同地址分支内的 pool 路径保持原样（复用未命中时逐级落到原逻辑）
        self.assertIn("Very likely this kind of overlap is caused by allocation from a pool", body)

    def test_same_format_requests_stay_on_pool_path(self):
        """场景：同格式请求不走复用（放开会在 Hades 场景炸曝光）。"""
        body = resolve_overlap_body()
        # 前置条件显式排除同格式
        self.assertIn("a.pixel_format != b.pixel_format && is_integer(a.pixel_format)", body)

    def test_normalized_sampling_is_excluded_via_needs_native(self):
        """场景：归一化采样不走复用——native_reads 门控 + memo/描述符缓存按 needs_native 区分。"""
        header = TEXTURE_CACHE_H.read_text()
        body = resolve_overlap_body()
        # 签名带 native_reads，且条件里实际使用
        self.assertIn("bool native_reads", header)
        self.assertIn("&& native_reads &&", body)
        # 仅 Texture/Storage 绑定可复用
        self.assertIn("binding == BindingType::Texture || binding == BindingType::Storage", body)
        # ImageDesc 携带 needs_native 并从 ImageResource 初始化
        self.assertIn("bool needs_native{true};", header)
        self.assertIn("needs_native{desc.needs_native}", header)
        # FindImage memo：hash 混入 + 相等比较 + 写入
        source = TEXTURE_CACHE_CPP.read_text()
        self.assertIn("u64(desc.needs_native) << 62", source)
        self.assertIn("cached.needs_native == desc.needs_native", source)
        self.assertIn(".needs_native = desc.needs_native,", source)
        # 调用点传递 desc.needs_native
        self.assertIn("ResolveOverlap(merged_info, desc.type, cache_id, image_id, desc.needs_native)",
                      source)

    def test_rasterizer_image_desc_cache_keys_on_needs_native(self):
        """场景：同一 T# 的采样与 load 绑定不共享 ImageDesc 缓存项。"""
        source = VK_RASTERIZER_CPP.read_text()
        self.assertIn("u32(res.needs_native) << 3", source)

    def test_light_partial_view_counter_and_diag_report(self):
        """轻量计数：GOW3_PERF_DIAG 门控的 5 秒节流日志，不引入上游 CreateStat 诊断面板。"""
        header = TEXTURE_CACHE_H.read_text()
        source = TEXTURE_CACHE_CPP.read_text()
        self.assertIn("void NotePartialView()", header)
        self.assertIn("void ReportPartialViews();", header)
        self.assertIn("void TextureCache::ReportPartialViews()", source)
        self.assertIn("Partial height views:", source)
        self.assertIn("if (Vulkan::WaitDiagnostics::Enabled() && !merged_image_id) {", source)
        # 不移植上游的完整诊断面板
        self.assertNotIn("CreateStat", source)
        self.assertNotIn("ReportImageCreates", source)


if __name__ == "__main__":
    unittest.main()
