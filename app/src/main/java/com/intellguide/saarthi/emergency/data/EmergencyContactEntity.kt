package com.intellguide.saarthi.emergency.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity storing registration information and emergency contacts.
 * Enforces a single row (id = 1) for the single active guardian/user profile.
 */
@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey val id: Int = 1,
    val userName: String,
    val contact1Name: String,
    val contact1Phone: String,
    val contact2Name: String,
    val contact2Phone: String
)
