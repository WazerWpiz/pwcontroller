package com.pwcontroller.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update
import com.pwcontroller.model.ControllerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ControllerProfile)

    @Update
    suspend fun update(profile: ControllerProfile)

    @Delete
    suspend fun delete(profile: ControllerProfile)

    @Query("SELECT * FROM controllerprofile ORDER BY createdAt DESC")
    fun getAllProfiles(): Flow<List<ControllerProfile>>

    @Query("SELECT * FROM controllerprofile WHERE name = :name")
    suspend fun getProfileByName(name: String): ControllerProfile?

    @Query("SELECT * FROM controllerprofile WHERE name = 'default'")
    suspend fun getDefaultProfile(): ControllerProfile?

    @Query("DELETE FROM controllerprofile")
    suspend fun clearAll()
}