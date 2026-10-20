import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEXTURE_CACHE_H = ROOT / "gpu/shadps4/video_core/texture_cache/texture_cache.h"
TEXTURE_CACHE_CPP = ROOT / "gpu/shadps4/video_core/texture_cache/texture_cache.cpp"
GPU_SHIM = ROOT / "gpu/shim/gow3gpu.cpp"


class BackingWriteContractTest(unittest.TestCase):
    def test_backing_write_notification_is_thread_safe_and_deferred(self):
        header = TEXTURE_CACHE_H.read_text()
        source = TEXTURE_CACHE_CPP.read_text()
        self.assertIn("void NoteBackingWrite(VAddr address, u64 size)", header)
        self.assertIn("std::mutex backing_writes_mutex", header)
        self.assertIn("std::atomic<bool> has_backing_writes", header)
        self.assertIn("void ApplyBackingWrites()", header)
        self.assertIn("backing_writes.emplace_back(address, size)", source)
        self.assertIn("ApplyBackingWrites();", source)

    def test_fast_paths_are_disabled_while_backing_writes_are_pending(self):
        source = TEXTURE_CACHE_H.read_text()
        self.assertGreaterEqual(source.count("!has_backing_writes.load"), 2)

    def test_small_backing_writes_are_filtered_and_marker_exists(self):
        source = GPU_SHIM.read_text()
        self.assertIn("written && rasterizer && size > 8", source)
        self.assertIn("GOW3_NATIVE_FIX_A101EDB=active", source)

    def test_apply_boundary_invalidates_only_overlapping_non_gpu_images(self):
        """范围失效语义：仅覆盖范围内的镜像被标记 CpuDirty；GPU 修改/别名镜像跳过。"""
        source = TEXTURE_CACHE_CPP.read_text()
        start = source.index("void TextureCache::ApplyBackingWrites()")
        body = source[start:source.index("void TextureCache::InvalidateMemoryFromGPU", start)]
        # 失效按写入范围遍历（重叠才命中；非重叠镜像不会被访问）
        self.assertIn("ForEachImageInRegion(address, size", body)
        # 与 GPU writer 共享内存的镜像交由 alias 同步，不在此处标脏
        self.assertIn("ImageFlagBits::GpuModified | ImageFlagBits::Aliased", body)
        self.assertIn("ImageFlagBits::CpuDirty", body)
        # 并发：pending 列表在锁内 swap，原子标志成对复位
        self.assertIn("std::scoped_lock lock{backing_writes_mutex};", body)
        self.assertIn("writes.swap(backing_writes);", body)
        self.assertIn('has_backing_writes.store(false, std::memory_order_release);', body)


if __name__ == "__main__":
    unittest.main()
