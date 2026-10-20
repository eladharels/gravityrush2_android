package com.gow3.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.gow3.android.R
import com.gow3.android.core.settings.GamePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * uncharted_android: the launcher's backdrop is the player's own game art, read at run time from
 * the selected dump (sce_sys/pic1.png, the PS4 background, or pic0.png), the update folder's copy
 * first. No game artwork is packaged in the APK; before a game folder is chosen the neutral
 * bg_wallpaper is shown.
 */
object GameArt {
    private val NAMES = listOf("sce_sys/pic1.png", "sce_sys/pic0.png")

    fun load(context: Context): ImageBitmap? {
        val prefs = GamePrefs(context)
        val dirs = listOfNotNull(prefs.updateDir(), prefs.gameDir()).map(::File)
        for (dir in dirs) {
            for (name in NAMES) {
                val file = File(dir, name)
                if (!file.isFile) continue
                val bitmap = runCatching {
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    BitmapFactory.decodeFile(file.path, options)
                }.getOrNull() ?: continue
                return bitmap.asImageBitmap()
            }
        }
        return null
    }
}

/** The game's background art when the selected dump has one, else the neutral wallpaper. */
@Composable
fun GameArtBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { GameArt.load(context) }.getOrNull() }
    }
    val bitmap = art
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Image(
            painter = painterResource(R.drawable.bg_wallpaper),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
