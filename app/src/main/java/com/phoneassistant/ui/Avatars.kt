package com.phoneassistant.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.phoneassistant.R
import kotlin.math.abs

/**
 * Avatars colorés : initiales + couleur déterministe dérivée du nom.
 * La forme est toujours circulaire (GradientDrawable.OVAL), quel que soit le layout.
 */
object Avatars {

    private val palette = intArrayOf(
        R.color.avatar_0, R.color.avatar_1, R.color.avatar_2, R.color.avatar_3,
        R.color.avatar_4, R.color.avatar_5, R.color.avatar_6, R.color.avatar_7
    )

    fun initials(name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || !n.any { it.isLetterOrDigit() }) return "#"
        val parts = n.split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.mapNotNull { it.firstOrNull { c -> c.isLetterOrDigit() }?.toString() }
            .take(2).joinToString("").uppercase().ifEmpty { "#" }
    }

    fun color(ctx: Context, key: String?): Int {
        val k = key?.trim().orEmpty()
        val idx = if (k.isEmpty()) 0 else abs(k.hashCode()) % palette.size
        return ContextCompat.getColor(ctx, palette[idx])
    }

    /** Applique un avatar circulaire coloré et ses initiales. */
    fun bind(container: View, initialsView: TextView, name: String?, key: String? = name) {
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(container.context, key))
        }
        initialsView.text = initials(name)
    }

    /** Pastille circulaire (badge de type d'appel) avec anneau de séparation. */
    fun circle(fillColor: Int, ringColor: Int, ringPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (ringPx > 0) setStroke(ringPx, ringColor)
        }
}
