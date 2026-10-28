package com.zobo.burung.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import com.zobo.burung.voice.Sfx
import com.zobo.burung.voice.Sound

/**
 * Pointers that went down on something which must stay silent (the map, a blocking cover). By id, so
 * a second finger on the map doesn't silence a button the first finger is tapping.
 */
private object ClickSoundGate {
    val muted = HashSet<PointerId>()
}

/**
 * Plays the tap sound for every tap a control handled, app-wide, without touching each button: the
 * root watches each gesture and, when the finger lifts without having moved, asks whether something
 * below consumed the release (buttons, switches, list items and tabs do; scrolling, dragging and
 * disabled controls don't). Goes on the root of the screen.
 */
fun Modifier.clickSounds(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        // Initial pass: the root sees the press before anything below it.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        ClickSoundGate.muted.clear()
        val start = down.position
        var moved = false
        while (true) {
            // Final pass: after everything below has had its turn (and maybe consumed it).
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
            if (!change.pressed) {
                val quick = change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                // A control that made its own sound for this tap (tab, switch) is not ticked over.
                val ownSound = Sfx.lastOwnAt >= down.uptimeMillis
                if (change.isConsumed && !moved && quick && down.id !in ClickSoundGate.muted && !ownSound) {
                    // Timed from the finger lifting, so a tap handled late by a busy UI stays silent.
                    Sfx.play(Sound.TAP, at = change.uptimeMillis)
                }
                break
            }
        }
    }
}

/**
 * Taps here make no click sound: the map (its view takes every touch, so a pan or a tap on the map
 * would otherwise sound like a button) and covers that only swallow touches.
 */
fun Modifier.muteClickSounds(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            for (c in event.changes) if (c.changedToDownIgnoreConsumed()) ClickSoundGate.muted += c.id
        }
    }
}
