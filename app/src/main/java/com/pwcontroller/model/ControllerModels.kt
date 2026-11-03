package com.pwcontroller.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

data class ButtonMapping(
    val buttonId: String,
    val screenX: Float,
    val screenY: Float,
    val actionType: ActionType = ActionType.TAP,
    val swipeDirection: SwipeDirection? = null,
    val swipeDistance: Float = 0f,
    /**
     * Normalised drag applied while the button is held (fraction of screen width / height).
     * Virtual joysticks in Unity games expect a drag, not a tap: the finger goes down on the
     * stick and is moved by this offset. 0 = no drag.
     */
    val dragDx: Float = 0f,
    val dragDy: Float = 0f
) : Parcelable {
    constructor(parcel: Parcel) : this(
        buttonId = parcel.readString()!!,
        screenX = parcel.readFloat(),
        screenY = parcel.readFloat(),
        actionType = ActionType.valueOf(parcel.readString()!!),
        swipeDirection = parcel.readString()?.let { SwipeDirection.valueOf(it) },
        swipeDistance = parcel.readFloat(),
        dragDx = parcel.readFloat(),
        dragDy = parcel.readFloat()
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(buttonId)
        dest.writeFloat(screenX)
        dest.writeFloat(screenY)
        dest.writeString(actionType.name)
        dest.writeString(swipeDirection?.name)
        dest.writeFloat(swipeDistance)
        dest.writeFloat(dragDx)
        dest.writeFloat(dragDy)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ButtonMapping> {
        override fun createFromParcel(parcel: Parcel): ButtonMapping = ButtonMapping(parcel)
        override fun newArray(size: Int): Array<ButtonMapping?> = arrayOfNulls(size)
    }
}

enum class ActionType {
    TAP, SWIPE, HOLD
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

@Entity(tableName = "controllerprofile")
data class ControllerProfile(
    @PrimaryKey val name: String,
    val mappings: List<ButtonMapping>,
    val dpadPosition: Position = Position(0.1f, 0.7f),
    val analogPosition: Position = Position(0.35f, 0.7f),
    val faceButtonsPosition: Position = Position(0.85f, 0.7f),
    val shoulderButtonsPosition: Position = Position(0.85f, 0.4f),
    val centerButtonsPosition: Position = Position(0.5f, 0.9f),
    val toggleButtonPosition: Position = Position(0.05f, 0.05f),
    val buttonScale: Float = 1.0f,
    val buttonAlpha: Float = 0.8f,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {
    @Ignore
    constructor(parcel: Parcel) : this(
        name = parcel.readString()!!,
        mappings = parcel.createTypedArrayList(ButtonMapping.CREATOR)!!,
        dpadPosition = parcel.readParcelable(Position::class.java.classLoader)!!,
        analogPosition = parcel.readParcelable(Position::class.java.classLoader)!!,
        faceButtonsPosition = parcel.readParcelable(Position::class.java.classLoader)!!,
        shoulderButtonsPosition = parcel.readParcelable(Position::class.java.classLoader)!!,
        centerButtonsPosition = parcel.readParcelable(Position::class.java.classLoader)!!,
        toggleButtonPosition = parcel.readParcelable(Position::class.java.classLoader)!!,
        buttonScale = parcel.readFloat(),
        buttonAlpha = parcel.readFloat(),
        createdAt = parcel.readLong()
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeTypedList(mappings)
        dest.writeParcelable(dpadPosition, flags)
        dest.writeParcelable(analogPosition, flags)
        dest.writeParcelable(faceButtonsPosition, flags)
        dest.writeParcelable(shoulderButtonsPosition, flags)
        dest.writeParcelable(centerButtonsPosition, flags)
        dest.writeParcelable(toggleButtonPosition, flags)
        dest.writeFloat(buttonScale)
        dest.writeFloat(buttonAlpha)
        dest.writeLong(createdAt)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ControllerProfile> {
        override fun createFromParcel(parcel: Parcel): ControllerProfile = ControllerProfile(parcel)
        override fun newArray(size: Int): Array<ControllerProfile?> = arrayOfNulls(size)
    }
}

data class Position(
    val x: Float, // 0.0 to 1.0 (relative to screen width)
    val y: Float  // 0.0 to 1.0 (relative to screen height)
) : Parcelable {
    constructor(parcel: Parcel) : this(
        x = parcel.readFloat(),
        y = parcel.readFloat()
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFloat(x)
        dest.writeFloat(y)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Position> {
        override fun createFromParcel(parcel: Parcel): Position = Position(parcel)
        override fun newArray(size: Int): Array<Position?> = arrayOfNulls(size)
    }
}