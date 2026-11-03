package com.pwcontroller.util

import com.pwcontroller.model.ButtonMapping

/** Backend-agnostic input sender (Shizuku or Accessibility). */
interface InputDispatcher {
    fun dispatchTap(mapping: ButtonMapping, screenWidth: Int, screenHeight: Int)
    fun dispatchSwipe(mapping: ButtonMapping, screenWidth: Int, screenHeight: Int)
    fun startHold(key: String, mapping: ButtonMapping, screenWidth: Int, screenHeight: Int)
    fun stopHold(key: String)
    fun stopAllHolds()

    /** Press-follow for action buttons (jump/punch): pointerDown on DOWN, pointerUp on UP/CANCEL.
     * Ensures minimum 40ms hold if released too quickly. */
    fun pressFollow(key: String, mapping: ButtonMapping, screenWidth: Int, screenHeight: Int, isDown: Boolean)

    /**
     * Hold with a drag, used for virtual joysticks: the finger goes down on the stick and is
     * dragged by (dragDx * screenWidth, dragDy * screenHeight) pixels while the button is held.
     * Unity games ignore a plain tap on their joystick, they only react to the drag.
     */
    fun startDragHold(
        key: String,
        mapping: ButtonMapping,
        screenWidth: Int,
        screenHeight: Int,
        dragDx: Float,
        dragDy: Float
    )

    /** Returns the timestamp of the last injection (for diagnostics). */
    fun getLastInjectTime(): Long
}
