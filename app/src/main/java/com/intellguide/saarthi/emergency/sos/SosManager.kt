package com.intellguide.saarthi.emergency.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.intellguide.saarthi.emergency.data.EmergencyContactEntity
import com.intellguide.saarthi.emergency.data.EmergencyRepository

data class AvailableContact(
    val name: String,
    val phoneNumber: String,
    val contactIndex: Int
)

sealed class SosCallResult {
    data class CallingContact(val contactName: String, val phoneNumber: String) : SosCallResult()
    data class CallingContact1(val contactName: String, val phoneNumber: String) : SosCallResult()
    data class CallingContact2(val contactName: String, val phoneNumber: String, val reason: String) : SosCallResult()
    data class Error(val message: String) : SosCallResult()
    object NoRegistration : SosCallResult()
    object PermissionNeeded : SosCallResult()
}

/**
 * Handles Emergency SOS flow, contact retrieval, voice name matching, and direct calling.
 */
class SosManager(private val context: Context) {

    private val repository = EmergencyRepository(context)

    /**
     * Retrieves registered and valid emergency contacts from the database.
     */
    suspend fun getAvailableContacts(): List<AvailableContact> {
        val contactEntity = repository.getEmergencyContact() ?: return emptyList()
        val list = mutableListOf<AvailableContact>()

        val c1Name = contactEntity.contact1Name.trim()
        val c1Phone = contactEntity.contact1Phone.trim()
        if (c1Name.isNotEmpty() && EmergencyRepository.isValidPhoneNumber(c1Phone)) {
            list.add(
                AvailableContact(
                    name = c1Name,
                    phoneNumber = EmergencyRepository.cleanPhoneNumber(c1Phone),
                    contactIndex = 1
                )
            )
        }

        val c2Name = contactEntity.contact2Name.trim()
        val c2Phone = contactEntity.contact2Phone.trim()
        if (c2Name.isNotEmpty() && EmergencyRepository.isValidPhoneNumber(c2Phone)) {
            list.add(
                AvailableContact(
                    name = c2Name,
                    phoneNumber = EmergencyRepository.cleanPhoneNumber(c2Phone),
                    contactIndex = 2
                )
            )
        }

        return list
    }

    /**
     * Matches user's spoken name input against registered emergency contacts.
     * Supports case-insensitivity, filler words ("call", "please", etc.),
     * first name matching, and contact index aliases ("contact one", "contact two").
     */
    fun matchContact(spokenText: String, availableContacts: List<AvailableContact>): AvailableContact? {
        val normalizedSpoken = spokenText.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")
        if (normalizedSpoken.isBlank() || availableContacts.isEmpty()) return null

        val spokenWords = normalizedSpoken.split("\\s+".toRegex()).filter { it.isNotBlank() }

        // 1. Exact match on full normalized name
        for (contact in availableContacts) {
            val normContactName = contact.name.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")
            if (normContactName.isBlank()) continue
            if (normalizedSpoken == normContactName) return contact
        }

        // 2. Word and token matching (e.g. "call Rahul" or "Rahul Sharma")
        for (contact in availableContacts) {
            val normContactName = contact.name.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")
            if (normContactName.isBlank()) continue

            val contactWords = normContactName.split("\\s+".toRegex()).filter { it.isNotBlank() }

            // Spoken contains all words of the contact name
            if (contactWords.isNotEmpty() && spokenWords.containsAll(contactWords)) {
                return contact
            }

            // Spoken contains individual contact name tokens (e.g. first name)
            for (cWord in contactWords) {
                if (cWord.length >= 3 && spokenWords.contains(cWord)) {
                    return contact
                }
            }

            // Substring match
            if (normalizedSpoken.contains(normContactName) || (normContactName.length >= 4 && normContactName.contains(normalizedSpoken))) {
                return contact
            }
        }

        // 3. Ordinal / Index matching ("contact one", "first", "one", "contact two", "second", "two")
        for (contact in availableContacts) {
            if (contact.contactIndex == 1) {
                if (spokenWords.contains("one") || spokenWords.contains("first") || spokenWords.contains("1") ||
                    normalizedSpoken.contains("contact 1") || normalizedSpoken.contains("contact one")
                ) {
                    return contact
                }
            } else if (contact.contactIndex == 2) {
                if (spokenWords.contains("two") || spokenWords.contains("second") || spokenWords.contains("2") ||
                    normalizedSpoken.contains("contact 2") || normalizedSpoken.contains("contact two")
                ) {
                    return contact
                }
            }
        }

        // 4. Edit distance tolerance for minor speech recognition spelling discrepancies
        for (contact in availableContacts) {
            val normContactName = contact.name.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "")
            val contactWords = normContactName.split("\\s+".toRegex()).filter { it.isNotBlank() }
            for (sWord in spokenWords) {
                for (cWord in contactWords) {
                    if (cWord.length >= 4 && isSimilarWord(sWord, cWord)) {
                        return contact
                    }
                }
            }
        }

        return null
    }

    /**
     * Extracts spoken candidate name from speech input for error feedback.
     * e.g. "Call Amit" -> "Amit", "call to Priya" -> "Priya".
     */
    fun extractCandidateName(spokenText: String): String {
        val clean = spokenText.trim()
            .replace(Regex("(?i)^(please\\s+)?(call|phone|dial|contact|connect(\\s+to)?|i\\s+want(\\s+to\\s+call)?)\\s+"), "")
            .replace(Regex("(?i)\\s+(please|now)$"), "")
            .trim()

        return if (clean.isNotBlank()) {
            clean.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        } else {
            ""
        }
    }

    private fun isSimilarWord(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var diff = 0
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] != b[j]) {
                diff++
                if (diff > 1) return false
                if (a.length > b.length) i++
                else if (b.length > a.length) j++
                else { i++; j++ }
            } else {
                i++
                j++
            }
        }
        return true
    }

    /**
     * Initiates a DIRECT phone call using ACTION_CALL.
     * Will NOT open dial pad, dialer, or require manual button press.
     * Returns true if ACTION_CALL was successfully launched, false if permission is missing or error occurred.
     */
    fun makeDirectCall(phoneNumber: String): Boolean {
        val cleanNumber = EmergencyRepository.cleanPhoneNumber(phoneNumber)
        if (cleanNumber.isEmpty()) return false

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallPermission) {
            return false
        }

        val uri = Uri.parse("tel:$cleanNumber")
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Backward-compatible helper method that uses direct ACTION_CALL.
     */
    fun makePhoneCall(phoneNumber: String): Boolean {
        return makeDirectCall(phoneNumber)
    }

    /**
     * Check if CALL_PHONE permission is currently granted.
     */
    fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
