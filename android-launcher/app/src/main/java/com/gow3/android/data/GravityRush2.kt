package com.gow3.android.data

import java.io.File

/**
 * gravityrush2_android: Gravity Rush 2 (PS4; HayatoG/gr2_pc plays CUSA03694 with update 01.11),
 * one executable, eboot.bin. The folder is recognised by the game's content archive, so any region
 * is found, and gets the runtime options below instead of God of War III's presets and patches.
 */
object GravityRush2 {
    /** [status]: how far it is known to work in this Android port, shown on the home screen. */
    data class Game(
        val key: String,
        val title: String,
        val executable: String,
        val env: Map<String, String>,
        val status: String = "Experimental",
    )

    val GAME = Game("gr2", "Gravity Rush 2", "eboot.bin", mapOf(
        "GOW3_FRAME_STATS" to "1",
        // The renderer's threads on the big cores and the draw pipe on: measured with Uncharted on
        // the Galaxy S24 Ultra (uncharted_android), not specific to that game.
        "GOW3_BIG_CORES" to "1", "GOW3_DRAW_PIPE" to "1",
        // Measured in the opening scene (2026-10-28): reading every linear render target back
        // (the Uncharted default) copied the 16 MB HDR target to guest memory at each of ~16
        // submissions a frame, 4.6 FPS; without it 16 FPS, picture unchanged. The game's G-buffer
        // has 3 colour targets, which start the reduced-resolution scene (default 5).
        "GOW3_READBACK_LINEAR" to "0", "GOW3_SCENE_MRT" to "3",
    ), "Boot test")

    @JvmStatic
    fun isGame(game: File?): Boolean =
        game != null && File(game, "eboot.bin").isFile && File(game, "Gravite2_Chunk0.psarc").isFile

    @JvmStatic
    fun selected(game: File?): Game? = if (isGame(game)) GAME else null
}
