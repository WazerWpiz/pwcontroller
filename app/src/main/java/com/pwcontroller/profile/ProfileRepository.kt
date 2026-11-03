package com.pwcontroller.profile

import android.content.Context
import com.pwcontroller.model.ControllerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepository(private val dao: ProfileDao) {

    suspend fun saveProfile(profile: ControllerProfile) {
        dao.insert(profile)
    }

    suspend fun updateProfile(profile: ControllerProfile) {
        dao.update(profile)
    }

    suspend fun deleteProfile(profile: ControllerProfile) {
        dao.delete(profile)
    }

    fun getAllProfiles(): Flow<List<ControllerProfile>> {
        return dao.getAllProfiles()
    }

    suspend fun getProfileByName(name: String): ControllerProfile? {
        return dao.getProfileByName(name)
    }

    suspend fun getDefaultProfile(): ControllerProfile? {
        return dao.getDefaultProfile()
    }

    companion object {
        fun create(context: Context): ProfileRepository {
            val db = ProfileDatabase.getDatabase(context)
            return ProfileRepository(db.profileDao())
        }
    }
}