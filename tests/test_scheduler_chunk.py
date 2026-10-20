import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEDULER_H = ROOT / "gpu/shadps4/video_core/renderer_vulkan/vk_scheduler.h"
SCHEDULER_CPP = ROOT / "gpu/shadps4/video_core/renderer_vulkan/vk_scheduler.cpp"
GPU_SHIM = ROOT / "gpu/shim/gow3gpu.cpp"


class SchedulerChunkContractTest(unittest.TestCase):
    def test_recording_entries_keep_a_chunk_alive_during_handover(self):
        header = SCHEDULER_H.read_text()
        source = SCHEDULER_CPP.read_text()
        self.assertIn("RecordChunk& CurrentChunk()", header)
        self.assertIn("void RetireChunk()", header)
        self.assertIn("record_chunk.swap(full)", header)
        self.assertNotIn("record_chunk->", header)
        self.assertNotIn("record_chunk->", source)
        self.assertIn("std::mutex kick_mutex", header)
        kick = source.split("void Scheduler::KickRecording", 1)[1].split(
            "void Scheduler::SyncRecording", 1
        )[0]
        self.assertIn("std::scoped_lock kick_lock{kick_mutex}", kick)
        self.assertIn("std::unique_ptr<RecordChunk> next_record", kick)
        self.assertIn("record_chunk.swap(next_record)", kick)
        self.assertIn("if (!record_chunk)", header)

    def test_scheduler_fix_marker_is_emitted(self):
        source = GPU_SHIM.read_text()
        self.assertIn("GOW3_NATIVE_FIX_SCHEDULER_CHUNK=active", source)


if __name__ == "__main__":
    unittest.main()
