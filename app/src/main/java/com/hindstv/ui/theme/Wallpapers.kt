package com.hindstv.ui.theme

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.hindstv.R

/** Home's backdrop. One set per theme, 1920×1080 WebP in drawable-nodpi (kept small for the APK budget). */
data class Wallpaper(@DrawableRes val image: Int, @StringRes val name: Int)

val DarkWallpapers = listOf(
    Wallpaper(R.drawable.wall_dark_reservoir, R.string.wall_reservoir),
    Wallpaper(R.drawable.wall_dark_fells, R.string.wall_fells),
)

val LightWallpapers = listOf(
    Wallpaper(R.drawable.wall_light_moorings, R.string.wall_moorings),
    Wallpaper(R.drawable.wall_light_lakeshore, R.string.wall_lakeshore),
)

fun wallpapersFor(light: Boolean) = if (light) LightWallpapers else DarkWallpapers
