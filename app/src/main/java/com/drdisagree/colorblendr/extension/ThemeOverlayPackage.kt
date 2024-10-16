package com.drdisagree.colorblendr.extension

import android.graphics.Color
import android.os.Build
import android.util.Log
import com.drdisagree.colorblendr.common.Const.MONET_SEED_COLOR
import com.drdisagree.colorblendr.common.Const.MONET_SEED_COLOR_EXTERNAL_OVERLAY
import com.drdisagree.colorblendr.common.Const.MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED
import com.drdisagree.colorblendr.common.Const.MONET_STYLE_ORIGINAL_NAME
import com.drdisagree.colorblendr.config.RPrefs
import com.drdisagree.colorblendr.config.RPrefs.getInt
import com.drdisagree.colorblendr.config.RPrefs.getString
import com.drdisagree.colorblendr.utils.ColorUtil.intToHexColorNoHash
import org.json.JSONObject

object ThemeOverlayPackage {

    private val TAG: String = ThemeOverlayPackage::class.java.simpleName
    const val THEME_STYLE: String = "android.theme.customization.theme_style"
    const val COLOR_SOURCE: String = "android.theme.customization.color_source"
    const val SYSTEM_PALETTE: String = "android.theme.customization.system_palette"
    const val ACCENT_COLOR: String = "android.theme.customization.accent_color"
    const val COLOR_BOTH: String = "android.theme.customization.color_both"
    const val APPLIED_TIMESTAMP: String = "_applied_timestamp"

    val themeCustomizationOverlayPackages: JSONObject
        get() {
            return try {
                val seedKey: String =
                    if (RPrefs.getBoolean(MONET_SEED_COLOR_EXTERNAL_OVERLAY_ENABLED))
                        MONET_SEED_COLOR_EXTERNAL_OVERLAY
                    else
                        MONET_SEED_COLOR

                JSONObject().apply {
                    putOpt(
                        COLOR_SOURCE,
                        "preset"
                    )
                    putOpt(
                        THEME_STYLE,
                        getString(
                            MONET_STYLE_ORIGINAL_NAME,
                            "TONAL_SPOT"
                        )
                    )
                    putOpt(
                        SYSTEM_PALETTE,
                        intToHexColorNoHash(
                            getInt(
                                seedKey,
                                Color.BLUE
                            )
                        )
                    )
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        putOpt(
                            ACCENT_COLOR,
                            intToHexColorNoHash(
                                getInt(
                                    seedKey,
                                    Color.BLUE
                                )
                            )
                        )
                    }
                    putOpt(
                        APPLIED_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "themeCustomizationOverlayPackages:", e)
                JSONObject()
            }
        }
}
