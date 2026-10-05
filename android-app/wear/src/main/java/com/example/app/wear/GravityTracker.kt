package com.example.app.wear

import kotlin.math.abs

/**
 * How far one raw-accelerometer reading is from the wrist's resting |a|.
 *
 * Without a linear-acceleration sensor, motion is |a| minus gravity. Assuming
 * gravity reads exactly 9.81 does not survive real hardware: watch
 * accelerometers are commonly a few percent off, and that constant offset read
 * as permanent motion (0.8-1.1 m/s² at rest on a Galaxy Watch5, over the
 * phone's 0.5 "moving" line). So the resting magnitude is learned instead: a
 * slow average of |a|, which a still wrist settles on and a moving one leaves
 * alone, since steady walking averages out to gravity too.
 *
 * ponytail: one exponential average; a gravity-vector low-pass (direction as
 * well as magnitude) is the upgrade if turning the wrist ever reads as motion.
 */
class GravityTracker(private val alpha: Float = 0.02f) {  // ~10 s time constant at 5 Hz
    private var estimate = 0f

    fun deviation(magnitude: Float): Float {
        estimate = if (estimate == 0f) magnitude else estimate + alpha * (magnitude - estimate)
        return abs(magnitude - estimate)
    }
}
