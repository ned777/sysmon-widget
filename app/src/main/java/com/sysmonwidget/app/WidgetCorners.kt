package com.sysmonwidget.app

import android.appwidget.AppWidgetManager
import android.content.res.Resources
import android.os.Build
import android.widget.RemoteViews

/**
 * Keeps the widget's rounded border lined up with the launcher's corner mask.
 *
 * Samsung One UI doesn't lay a widget out at its real on-screen size: it lays it
 * out bigger and then shrinks the whole thing to fit the home-screen grid cell
 * (the shrink factor arrives in the widget options as "hsResizeRatio", e.g. 0.83).
 * Our border's corner radius shrinks along with it, but the launcher still clips
 * the widget with its own full-size radius ("semShapeRadius", 24dp). The clip
 * mask then has a rounder corner than our border, so it shaves the outside of
 * the border's curve and the corners look cut off.
 *
 * Fix: draw the border with radius = launcherRadius / resizeRatio, so after the
 * launcher shrinks it, it lands exactly on the launcher's mask. RemoteViews can't
 * set a drawable's corner radius directly, so we ship pre-baked copies of the
 * background at 1dp steps (widget_background_r24..r36) and pick the nearest one that's
 * at least as round as needed (a slightly rounder border is never clipped; a
 * less round one is).
 */
object WidgetCorners {
    private const val MIN_RADIUS_DP = 24
    private val VARIANTS = intArrayOf(
        R.drawable.widget_background_r24,
        R.drawable.widget_background_r25,
        R.drawable.widget_background_r26,
        R.drawable.widget_background_r27,
        R.drawable.widget_background_r28,
        R.drawable.widget_background_r29,
        R.drawable.widget_background_r30,
        R.drawable.widget_background_r31,
        R.drawable.widget_background_r32,
        R.drawable.widget_background_r33,
        R.drawable.widget_background_r34,
        R.drawable.widget_background_r35,
        R.drawable.widget_background_r36
    )

    /** Swap [rootViewId]'s background to the radius that matches this widget's current scaling. */
    fun apply(views: RemoteViews, rootViewId: Int, manager: AppWidgetManager, widgetId: Int) {
        val opts = manager.getAppWidgetOptions(widgetId)
        val hasLauncherScale = opts.containsKey("hsResizeRatio")
        // Pre-Android 12 launchers that don't scale widgets: keep the layout's default background.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !hasLauncherScale) return

        val ratio = opts.getFloat("hsResizeRatio", 1f).takeIf { it in 0.3f..1f } ?: 1f
        val maskRadiusDp = opts.getFloat("semShapeRadius", 0f).takeIf { it > 0f } ?: systemRadiusDp()
        val neededDp = maskRadiusDp / ratio
        // Round up (tiny epsilon so 24.0000001 doesn't jump to 25).
        val index = (Math.ceil(neededDp - 0.05).toInt() - MIN_RADIUS_DP).coerceIn(0, VARIANTS.size - 1)
        views.setInt(rootViewId, "setBackgroundResource", VARIANTS[index])
    }

    private fun systemRadiusDp(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return MIN_RADIUS_DP.toFloat()
        val res = Resources.getSystem()
        return res.getDimension(android.R.dimen.system_app_widget_background_radius) / res.displayMetrics.density
    }
}
