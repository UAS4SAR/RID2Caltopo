package org.ncssar.rid2caltopo.video

import android.content.Context
import org.osmdroid.config.DefaultConfigurationProvider
import java.io.File

/** osmdroid's default tile-path getter calls mkdirs even when a path is already set. */
internal class PreparedOsmdroidConfiguration(
    private val base: File,
    private val tiles: File,
) : DefaultConfigurationProvider() {
    override fun getOsmdroidBasePath(): File = base
    override fun getOsmdroidBasePath(context: Context?): File = base
    override fun getOsmdroidTileCache(): File = tiles
    override fun getOsmdroidTileCache(context: Context?): File = tiles
}
