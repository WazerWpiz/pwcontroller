# PW Controller ProGuard Rules

# Keep model classes for Room serialization
-keep class com.pwcontroller.model.** { *; }

# Keep profile classes
-keep class com.pwcontroller.profile.** { *; }

# Keep service classes
-keep class com.pwcontroller.service.** { *; }

# Keep util classes
-keep class com.pwcontroller.util.** { *; }

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }

# Keep Room
-keep class androidx.room.** { *; }

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }

# Keep Material Components
-keep class com.google.android.material.** { *; }

# Keep ConstraintLayout
-keep class androidx.constraintlayout.** { *; }