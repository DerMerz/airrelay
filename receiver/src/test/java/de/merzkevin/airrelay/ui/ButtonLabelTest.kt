/*
 * AirRelay, Copyright (C) 2026 Kevin Merz
 * SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-AirRelay-Amazon-Appstore-SDK-exception
 * GNU GPL version 3 or later, with the additional permission in LICENSE-EXCEPTION.md.
 */
package de.merzkevin.airrelay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.merzkevin.airrelay.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Every button carries a readable label in every state (Kevin, 03.11.2026: a white pill
 * without a label on the waiting screen, the licences button).
 *
 * For each focusable, clickable view of the real layout, in German and English:
 *  - its label is not empty,
 *  - every text colour it can show has at least 4.5:1 against every fill its background can
 *    show, in any combination of states, over the brightest and darkest spot of the screen.
 *    Text and fill are checked crosswise on purpose: a text colour that is only readable
 *    while the fill is in the same state fails, because that is exactly the white-on-white
 *    pill when the two fall out of step,
 *  - focus is visible: the focused background differs from the unfocused one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ButtonLabelTest {

    private val buttons = setOf(
        "option_yearly", "option_monthly", "restore_button", "offer_retry",
        "retry_button", "network_button", "licenses_button",
    )

    private val focused = android.R.attr.state_focused
    private val pressed = android.R.attr.state_pressed
    private val activated = android.R.attr.state_activated
    private val enabled = android.R.attr.state_enabled
    private val window = android.R.attr.state_window_focused

    private val states: Map<String, IntArray> = mapOf(
        "normal" to intArrayOf(enabled, window),
        "focused" to intArrayOf(enabled, window, focused),
        "pressed" to intArrayOf(enabled, window, pressed),
        "focused+pressed" to intArrayOf(enabled, window, focused, pressed),
        "activated" to intArrayOf(enabled, window, activated),
        "focused+activated" to intArrayOf(enabled, window, focused, activated),
        "focused+activated+pressed" to intArrayOf(enabled, window, focused, activated, pressed),
        "focused, window without focus" to intArrayOf(enabled, focused),
        "normal, window without focus" to intArrayOf(enabled),
    )

    @Test fun labelsReadableInGerman() = check("de")

    @Test fun labelsReadableInEnglish() = check("en")

    @Test fun focusIsVisible() {
        val root = inflate("de")
        val problems = ArrayList<String>()
        for (b in buttonsOf(root)) {
            val d = copy(b.background)
            d.setState(states.getValue("normal"))
            val normal = d.current
            d.setState(states.getValue("focused"))
            if (d.current === normal) {
                problems += "${name(b)}: focused looks like unfocused"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun detectorSeesWhiteOnWhite() {
        // the old pairing: unfocused text colour on the focused white fill
        assertTrue(contrast(Color.parseColor("#C3CCDD"), Color.parseColor("#F4F7FC")) < 4.5)
        assertTrue(contrast(Color.parseColor("#F4F7FC"), Color.parseColor("#0A1020")) > 4.5)
    }

    private fun check(lang: String) {
        val root = inflate(lang)
        val found = buttonsOf(root)
        assertEquals(buttons, found.map { name(it) }.toSet())
        val c = root.context
        val top = c.getColor(R.color.bg_top)
        val bottom = c.getColor(R.color.bg_bottom)
        val glow = c.getColor(R.color.bg_glow)
        val screen = listOf(top, bottom, over(glow, top), over(glow, bottom))
        val box = screen.map { over(c.getColor(R.color.offer_fill), it) }
        val problems = ArrayList<String>()
        var checks = 0
        for (b in found) {
            val texts = textsOf(b)
            if (texts.none { it.text.isNotBlank() }) {
                problems += "$lang ${name(b)}: no label"
            }
            val bases = if (inOfferBox(b)) box else screen
            for ((bgState, bgSet) in states) {
                val fill = fillOf(b.background, bgSet)
                for (base in bases) {
                    val under = over(fill, base)
                    for (t in texts) {
                        for ((textState, textSet) in states) {
                            val color = t.textColors.getColorForState(textSet, t.textColors.defaultColor)
                            val ratio = contrast(over(color, under), under)
                            checks++
                            if (ratio < 4.5) {
                                problems += "$lang ${name(b)}/${name(t)}: text $textState on fill $bgState " +
                                    "%.2f:1 (text #%08X, fill #%08X)".format(ratio, color, under)
                            }
                        }
                    }
                }
            }
        }
        println("ButtonLabelTest $lang: ${found.size} buttons, $checks contrast checks")
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    private fun inflate(lang: String): View {
        RuntimeEnvironment.setQualifiers("+$lang")
        val c: Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_AirRelay)
        return LayoutInflater.from(c).inflate(R.layout.activity_main, null)
    }

    private fun all(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun buttonsOf(root: View) = all(root).filter { it.isFocusable && it.isClickable }

    private fun textsOf(b: View) = all(b).filterIsInstance<TextView>()

    private fun inOfferBox(v: View) =
        generateSequence(v.parent) { it.parent }.any { it is View && it.id == R.id.offer_box }

    private fun name(v: View) = if (v.id == View.NO_ID) v.javaClass.simpleName else v.resources.getResourceEntryName(v.id)

    private fun copy(d: Drawable): Drawable = d.constantState!!.newDrawable().mutate()

    private fun fillOf(background: Drawable, state: IntArray): Int {
        val d = copy(background)
        d.setState(state)
        val fill = (d.current as GradientDrawable).color!!
        return fill.getColorForState(state, fill.defaultColor)
    }

    private fun over(top: Int, base: Int): Int {
        val a = Color.alpha(top) / 255.0
        fun mix(t: Int, b: Int) = (t * a + b * (1 - a)).roundToInt()
        return Color.rgb(
            mix(Color.red(top), Color.red(base)),
            mix(Color.green(top), Color.green(base)),
            mix(Color.blue(top), Color.blue(base)),
        )
    }

    private fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
