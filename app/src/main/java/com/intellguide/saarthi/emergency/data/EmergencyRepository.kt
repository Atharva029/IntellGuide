package com.intellguide.saarthi.emergency.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Data repository for emergency contact registration and Room database operations.
 */
class EmergencyRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.emergencyContactDao()

    val emergencyContactFlow: Flow<EmergencyContactEntity?> = dao.getEmergencyContactFlow()

    suspend fun getEmergencyContact(): EmergencyContactEntity? {
        return try {
            dao.getEmergencyContactDirect()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun saveEmergencyContact(
        userName: String,
        c1Name: String,
        c1Phone: String,
        c2Name: String,
        c2Phone: String
    ): Result<Unit> {
        val validationResult = validate(userName, c1Name, c1Phone, c2Name, c2Phone)
        if (validationResult.isFailure) {
            return validationResult
        }

        return try {
            val entity = EmergencyContactEntity(
                id = 1,
                userName = userName.trim(),
                contact1Name = c1Name.trim(),
                contact1Phone = cleanPhoneNumber(c1Phone),
                contact2Name = c2Name.trim(),
                contact2Phone = cleanPhoneNumber(c2Phone)
            )
            dao.insertOrUpdate(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Database write failed: ${e.localizedMessage}"))
        }
    }

    companion object {
        fun validate(
            userName: String,
            c1Name: String,
            c1Phone: String,
            c2Name: String,
            c2Phone: String
        ): Result<Unit> {
            if (userName.isBlank()) {
                return Result.failure(IllegalArgumentException("User name cannot be empty."))
            }
            if (c1Name.isBlank()) {
                return Result.failure(IllegalArgumentException("Contact 1 name cannot be empty."))
            }
            if (!isValidPhoneNumber(c1Phone)) {
                return Result.failure(IllegalArgumentException("Contact 1 phone number is invalid."))
            }
            if (c2Name.isBlank()) {
                return Result.failure(IllegalArgumentException("Contact 2 name cannot be empty."))
            }
            if (!isValidPhoneNumber(c2Phone)) {
                return Result.failure(IllegalArgumentException("Contact 2 phone number is invalid."))
            }
            return Result.success(Unit)
        }

        fun isValidPhoneNumber(phone: String): Boolean {
            val trimmed = phone.trim()
            if (trimmed.isEmpty()) return false
            // Phone numbers can contain +, digits, spaces, hyphens, parentheses
            val cleaned = trimmed.replace(Regex("[\\s\\-\\(\\)]"), "")
            // Must contain digits and optionally a leading +
            val isValidFormat = cleaned.matches(Regex("^\\+?[0-9]{3,15}$"))
            return isValidFormat
        }

        fun cleanPhoneNumber(phone: String): String {
            return phone.trim().replace(Regex("[^0-9+]"), "")
        }
    }
}
