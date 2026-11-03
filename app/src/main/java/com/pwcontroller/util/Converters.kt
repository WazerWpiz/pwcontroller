package com.pwcontroller.util

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pwcontroller.model.ButtonMapping
import com.pwcontroller.model.Position

class Converters {
    private val gson = Gson()
    private val mappingListType = TypeToken.getParameterized(List::class.java, ButtonMapping::class.java).type
    private val positionType = TypeToken.get(Position::class.java).type

    @TypeConverter
    fun fromMappingList(value: String?): List<ButtonMapping> {
        return value?.let { gson.fromJson(it, mappingListType) } ?: emptyList()
    }

    @TypeConverter
    fun toMappingList(list: List<ButtonMapping>?): String {
        return gson.toJson(list)
    }

    @TypeConverter
    fun fromPosition(value: String?): Position? {
        return value?.let { gson.fromJson(it, positionType) }
    }

    @TypeConverter
    fun toPosition(position: Position?): String? {
        return position?.let { gson.toJson(it) }
    }
}