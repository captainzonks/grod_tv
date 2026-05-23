package com.captainzonks.grodtv.ui

import androidx.compose.ui.graphics.Color

/**
 * Brand palette. Sampled from the banner background and adapted for screen
 * states. Keep these values centralized — every Button focus color and every
 * OutlinedTextField border color should derive from them.
 */
object GrodColors {
    /** Primary brand purple. Sampled as the median dark purple in the banner art. */
    val BrandPurple = Color(0xFF22053E)

    /** Focused/elevated brand — lifted for D-pad focus rings. Bright enough
     *  to read on pure black at 4K from a TV viewing distance. */
    val BrandPurpleFocused = Color(0xFFAA46FF)

    /** Pressed/click feedback — between BrandPurple and Focused, slightly desaturated. */
    val BrandPurplePressed = Color(0xFF3D1463)

    /** Field background tint when focused — very subtle lift off pure black. */
    val FieldFocusedBg = Color(0xFF1A0830)

    /** Mid-grey for unfocused field borders + secondary labels. */
    val Slate = Color(0xFF8A8A8A)

    /** Lighter slate for focused labels / placeholder hints. */
    val SlateLight = Color(0xFFCCCCCC)
}
