package com.intellguide.saarthi.emergency.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EmergencyContactDao {

    @Query("SELECT * FROM emergency_contacts WHERE id = 1 LIMIT 1")
    fun getEmergencyContactFlow(): Flow<EmergencyContactEntity?>

    @Query("SELECT * FROM emergency_contacts WHERE id = 1 LIMIT 1")
    suspend fun getEmergencyContactDirect(): EmergencyContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(contact: EmergencyContactEntity)

    @Query("DELETE FROM emergency_contacts")
    suspend fun clear()
}
