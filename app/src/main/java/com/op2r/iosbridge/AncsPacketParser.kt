package com.op2r.iosbridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Apple Notification Center Service (ANCS) Specification v1.1 Binary Protocol Engine.
 *
 * Implements:
 * 1. 8-byte Notification Source (UUID 9FBF120D-6301-42D9-8C58-25E699A21DBD) parser.
 * 2. 15-byte Control Point (UUID 69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9) command builder:
 *    - CommandIDGetNotificationAttributes (0x00) + NotificationUID (4B LE) +
 *      AttributeIDAppIdentifier (0x00) +
 *      AttributeIDTitle (0x01, max 64B LE) [Contact Name / Sender Name] +
 *      AttributeIDSubtitle (0x02, max 64B LE) [Caller Phone Number / Group Name] +
 *      AttributeIDMessage (0x03, max 256B LE) [Full 256B Message Body]
 *    - CommandIDPerformNotificationAction (0x02) + NotificationUID (4B LE) + ActionID (0x00/0x01)
 * 3. Stateful Multi-Packet Fragmentation State Machine for Data Source
 *    (UUID 22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB) with:
 *    - Heuristic Contact Name vs Phone Number classification for Incoming Calls
 *    - Raw attribute preservation (rawTitle, rawSubtitle, rawMessage, rawAppId) for live diagnostics
 *    - Mid-stream header resynchronization and 350ms idle-stream flush
 */
class AncsPacketParser(
    private val onNotificationAssembled: (AssembledAncsNotification) -> Unit
) {
    companion object {
        // EventID Values
        const val EVENT_ID_NOTIFICATION_ADDED: Int = 0
        const val EVENT_ID_NOTIFICATION_MODIFIED: Int = 1
        const val EVENT_ID_NOTIFICATION_REMOVED: Int = 2

        // EventFlags Bitmask
        const val EVENT_FLAG_SILENT: Int = 1 shl 0            // 0x01
        const val EVENT_FLAG_IMPORTANT: Int = 1 shl 1         // 0x02
        const val EVENT_FLAG_PRE_EXISTING: Int = 1 shl 2      // 0x04
        const val EVENT_FLAG_POSITIVE_ACTION: Int = 1 shl 3   // 0x08
        const val EVENT_FLAG_NEGATIVE_ACTION: Int = 1 shl 4   // 0x10

        // CategoryID Values
        const val CATEGORY_ID_OTHER: Int = 0
        const val CATEGORY_ID_INCOMING_CALL: Int = 1
        const val CATEGORY_ID_MISSED_CALL: Int = 2
        const val CATEGORY_ID_VOICEMAIL: Int = 3
        const val CATEGORY_ID_SOCIAL: Int = 4
        const val CATEGORY_ID_SCHEDULE: Int = 5
        const val CATEGORY_ID_EMAIL: Int = 6
        const val CATEGORY_ID_NEWS: Int = 7
        const val CATEGORY_ID_HEALTH_AND_FITNESS: Int = 8
        const val CATEGORY_ID_BUSINESS_AND_FINANCE: Int = 9
        const val CATEGORY_ID_LOCATION: Int = 10
        const val CATEGORY_ID_ENTERTAINMENT: Int = 11

        // CommandID Values
        const val COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES: Byte = 0x00
        const val COMMAND_ID_GET_APP_ATTRIBUTES: Byte = 0x01
        const val COMMAND_ID_PERFORM_NOTIFICATION_ACTION: Byte = 0x02

        // NotificationAttributeID Values
        const val ATTRIBUTE_ID_APP_IDENTIFIER: Byte = 0x00
        const val ATTRIBUTE_ID_TITLE: Byte = 0x01
        const val ATTRIBUTE_ID_SUBTITLE: Byte = 0x02
        const val ATTRIBUTE_ID_MESSAGE: Byte = 0x03
        const val ATTRIBUTE_ID_MESSAGE_SIZE: Byte = 0x04
        const val ATTRIBUTE_ID_DATE: Byte = 0x05
        const val ATTRIBUTE_ID_POSITIVE_ACTION_LABEL: Byte = 0x06
        const val ATTRIBUTE_ID_NEGATIVE_ACTION_LABEL: Byte = 0x07

        // ActionID Values
        const val ACTION_ID_POSITIVE: Byte = 0x00
        const val ACTION_ID_NEGATIVE: Byte = 0x01

        // Requested attribute maximum lengths (Little Endian uint16)
        const val MAX_TITLE_BYTES: Int = 64
        const val MAX_SUBTITLE_BYTES: Int = 64
        const val MAX_MESSAGE_BYTES: Int = 256

        private val EXPECTED_ATTRIBUTES = setOf(
            ATTRIBUTE_ID_APP_IDENTIFIER.toInt(),
            ATTRIBUTE_ID_TITLE.toInt(),
            ATTRIBUTE_ID_SUBTITLE.toInt(),
            ATTRIBUTE_ID_MESSAGE.toInt()
        )
    }

    data class NotificationSourceEvent(
        val eventId: Int,
        val eventFlags: Int,
        val categoryId: Int,
        val categoryCount: Int,
        val notificationUid: Int
    ) {
        val isSilent: Boolean get() = (eventFlags and EVENT_FLAG_SILENT) != 0
        val isImportant: Boolean get() = (eventFlags and EVENT_FLAG_IMPORTANT) != 0
        val isPreExisting: Boolean get() = (eventFlags and EVENT_FLAG_PRE_EXISTING) != 0
        val hasPositiveAction: Boolean get() = (eventFlags and EVENT_FLAG_POSITIVE_ACTION) != 0
        val hasNegativeAction: Boolean get() = (eventFlags and EVENT_FLAG_NEGATIVE_ACTION) != 0
    }

    data class AssembledAncsNotification(
        val notificationUid: Int,
        val categoryId: Int,
        val eventFlags: Int,
        val appIdentifier: String,
        val appDisplayName: String,
        val title: String,
        val subtitle: String,
        val message: String,
        val rawAppId: String,
        val rawTitle: String,
        val rawSubtitle: String,
        val rawMessage: String,
        val hasPositiveAction: Boolean,
        val hasNegativeAction: Boolean,
        val receivedTimestampMs: Long = System.currentTimeMillis()
    )

    // Metadata cache keyed by NotificationUID from the 8-byte Notification Source packet
    private val sourceMetadataByUid = LinkedHashMap<Int, NotificationSourceEvent>()

    // Stateful Data Source reassembly buffer across arbitrary BLE MTU chunks (20B - 247B)
    private val rawStreamBuffer = ByteArrayOutputStream()
    private var activeCommandUid: Int? = null
    private var currentAttributeId: Int? = null
    private var currentAttributeExpectedLen: Int = -1
    private val collectedAttributes = mutableMapOf<Int, String>()

    /**
     * Parses the 8-byte Notification Source GATT notification payload:
     * Byte 0: EventID (0=Added, 1=Modified, 2=Removed)
     * Byte 1: EventFlags bitmask
     * Byte 2: CategoryID (1=Incoming Call, 2=Missed Call, 4=Social, etc.)
     * Byte 3: CategoryCount
     * Bytes 4..7: NotificationUID (uint32 Little-Endian)
     */
    @Synchronized
    fun parseNotificationSourcePacket(bytes: ByteArray): NotificationSourceEvent? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val eventId = buffer.get().toInt() and 0xFF
        val eventFlags = buffer.get().toInt() and 0xFF
        val categoryId = buffer.get().toInt() and 0xFF
        val categoryCount = buffer.get().toInt() and 0xFF
        val notificationUid = buffer.int

        val event = NotificationSourceEvent(
            eventId = eventId,
            eventFlags = eventFlags,
            categoryId = categoryId,
            categoryCount = categoryCount,
            notificationUid = notificationUid
        )

        if (eventId == EVENT_ID_NOTIFICATION_REMOVED) {
            sourceMetadataByUid.remove(notificationUid)
        } else {
            sourceMetadataByUid[notificationUid] = event
            if (sourceMetadataByUid.size > 128) {
                val oldestKey = sourceMetadataByUid.keys.firstOrNull()
                if (oldestKey != null) {
                    sourceMetadataByUid.remove(oldestKey)
                }
            }
        }
        return event
    }

    /**
     * Constructs the 15-byte Control Point request for CommandIDGetNotificationAttributes (0x00):
     * - Byte 0: 0x00 (CommandIDGetNotificationAttributes)
     * - Bytes 1..4: NotificationUID (4 bytes Little-Endian)
     * - Byte 5: 0x00 (AttributeIDAppIdentifier - no length parameter per ANCS spec)
     * - Byte 6: 0x01 (AttributeIDTitle)
     * - Bytes 7..8: 0x40, 0x00 (64 bytes max length, uint16 LE)
     * - Byte 9: 0x02 (AttributeIDSubtitle - carries Caller ID Phone Number / Group Name)
     * - Bytes 10..11: 0x40, 0x00 (64 bytes max length, uint16 LE)
     * - Byte 12: 0x03 (AttributeIDMessage - carries 256B Notification Message Body)
     * - Bytes 13..14: 0x00, 0x01 (256 bytes max length, uint16 LE)
     */
    fun buildGetNotificationAttributesCommand(notificationUid: Int): ByteArray {
        val buffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES)
        buffer.putInt(notificationUid)

        // Attribute 0: AppIdentifier (no 2-byte length field per ANCS spec)
        buffer.put(ATTRIBUTE_ID_APP_IDENTIFIER)

        // Attribute 1: Title + 2-byte max length (64B LE) -> Contact Name / Sender Name
        buffer.put(ATTRIBUTE_ID_TITLE)
        buffer.putShort(MAX_TITLE_BYTES.toShort())

        // Attribute 2: Subtitle + 2-byte max length (64B LE) -> Caller Phone Number / Subtitle
        buffer.put(ATTRIBUTE_ID_SUBTITLE)
        buffer.putShort(MAX_SUBTITLE_BYTES.toShort())

        // Attribute 3: Message + 2-byte max length (256B LE) -> Full 256B Message Body
        buffer.put(ATTRIBUTE_ID_MESSAGE)
        buffer.putShort(MAX_MESSAGE_BYTES.toShort())

        return buffer.array()
    }

    /**
     * Constructs the 6-byte Control Point request for CommandIDPerformNotificationAction (0x02):
     * - Byte 0: 0x02 (CommandIDPerformNotificationAction)
     * - Bytes 1..4: NotificationUID (4 bytes Little-Endian)
     * - Byte 5: ActionID (0x00 = Positive/Answer Call, 0x01 = Negative/Decline Call or Dismiss)
     */
    fun buildPerformNotificationActionCommand(notificationUid: Int, positive: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(COMMAND_ID_PERFORM_NOTIFICATION_ACTION)
        buffer.putInt(notificationUid)
        buffer.put(if (positive) ACTION_ID_POSITIVE else ACTION_ID_NEGATIVE)
        return buffer.array()
    }

    /**
     * Stateful Data Source (UUID 22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB) chunk consumer.
     * Reassembles attributes split across arbitrary BLE MTU notification chunks.
     */
    @Synchronized
    fun onDataSourceChunkReceived(chunk: ByteArray) {
        if (chunk.isEmpty()) return

        // If a brand-new response header [0x00 + Known NotificationUID + 0x00 AppID] starts at byte 0
        // of this chunk while a previous partial notification was still open, flush the previous one first!
        if (activeCommandUid != null && chunk.size >= 6 && chunk[0] == COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES) {
            val candidateUid = ByteBuffer.wrap(chunk, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val nextAttrId = chunk[5].toInt() and 0xFF
            if (sourceMetadataByUid.containsKey(candidateUid) && nextAttrId == ATTRIBUTE_ID_APP_IDENTIFIER.toInt()) {
                if (collectedAttributes.isNotEmpty()) {
                    emitCurrentActiveNotification()
                } else {
                    resetCurrentNotificationState()
                }
                rawStreamBuffer.reset()
            }
        }

        rawStreamBuffer.write(chunk, 0, chunk.size)
        processStreamBuffer()
    }

    @Synchronized
    private fun processStreamBuffer() {
        val bytes = rawStreamBuffer.toByteArray()
        var offset = 0

        while (offset < bytes.size) {
            // Step 1: Parse 5-byte Response Header [CommandID (1B) + NotificationUID (4B LE)]
            if (activeCommandUid == null) {
                while (offset < bytes.size && bytes[offset] != COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES) {
                    offset++
                }
                if (bytes.size - offset < 5) break

                val uid = ByteBuffer.wrap(bytes, offset + 1, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                activeCommandUid = uid
                collectedAttributes.clear()
                currentAttributeId = null
                currentAttributeExpectedLen = -1
                offset += 5
            }

            // Step 2: Parse 3-byte Attribute Header [AttributeID (1B) + AttributeLength (2B LE)]
            if (currentAttributeId == null) {
                if (bytes.size - offset < 3) break
                val attrId = bytes[offset].toInt() and 0xFF
                val attrLen = ByteBuffer.wrap(bytes, offset + 1, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short.toInt() and 0xFFFF

                if (attrId !in 0..7 || attrLen > 1024) {
                    if (collectedAttributes.isNotEmpty()) {
                        emitCurrentActiveNotification()
                    } else {
                        resetCurrentNotificationState()
                    }
                    offset = bytes.size
                    break
                }

                currentAttributeId = attrId
                currentAttributeExpectedLen = attrLen
                offset += 3
            }

            // Step 3: Read UTF-8 payload bytes for the active attribute
            val expectedLen = currentAttributeExpectedLen
            val attrId = currentAttributeId ?: break

            if (expectedLen == 0) {
                collectedAttributes[attrId] = ""
                currentAttributeId = null
                currentAttributeExpectedLen = -1
                if (checkIfAllAttributesComplete()) {
                    emitCurrentActiveNotification()
                }
                continue
            }

            if (bytes.size - offset < expectedLen) {
                // Wait for the next BLE MTU packet to deliver remaining UTF-8 bytes
                break
            }

            val utf8String = decodeCleanUtf8(bytes, offset, expectedLen)
            collectedAttributes[attrId] = utf8String
            offset += expectedLen

            currentAttributeId = null
            currentAttributeExpectedLen = -1

            if (checkIfAllAttributesComplete()) {
                emitCurrentActiveNotification()
            }
        }

        // Retain only unconsumed trailing bytes in the stream buffer
        if (offset > 0) {
            val remainingCount = bytes.size - offset
            rawStreamBuffer.reset()
            if (remainingCount > 0) {
                rawStreamBuffer.write(bytes, offset, remainingCount)
            }
        }
    }

    private fun checkIfAllAttributesComplete(): Boolean {
        return collectedAttributes.containsKey(ATTRIBUTE_ID_MESSAGE.toInt()) ||
            EXPECTED_ATTRIBUTES.all { collectedAttributes.containsKey(it) }
    }

    /**
     * Flushes any partially assembled notification when the BLE Data Source stream goes idle.
     */
    @Synchronized
    fun flushPendingPartialAttributes(): AssembledAncsNotification? {
        val uid = activeCommandUid ?: return null

        val pendingAttrId = currentAttributeId
        if (pendingAttrId != null && rawStreamBuffer.size() > 0) {
            val partialBytes = rawStreamBuffer.toByteArray()
            val takeLen = minOf(partialBytes.size, currentAttributeExpectedLen.coerceAtLeast(0))
            if (takeLen > 0) {
                collectedAttributes[pendingAttrId] = decodeCleanUtf8(partialBytes, 0, takeLen)
            }
        }

        if (collectedAttributes.isEmpty()) {
            resetCurrentNotificationState()
            rawStreamBuffer.reset()
            return null
        }

        val assembled = buildAssembledNotification(uid)
        resetCurrentNotificationState()
        rawStreamBuffer.reset()
        onNotificationAssembled(assembled)
        return assembled
    }

    @Synchronized
    fun hasPendingAssembly(): Boolean = activeCommandUid != null

    @Synchronized
    fun getActiveCommandUid(): Int? = activeCommandUid

    private fun decodeCleanUtf8(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        return String(bytes, offset, length, StandardCharsets.UTF_8)
            .trimEnd('\u0000')
            .replace("\u200E", "") // Strip iOS Left-to-Right mark around phone numbers/contacts
            .replace("\u200F", "")
            .replace("\u202A", "")
            .replace("\u202C", "")
            .trim()
    }

    /**
     * Heuristic helper: returns true if the string looks like a phone number
     * (digits, +, -, parentheses, spaces, dots) and contains no alphabetic letters.
     */
    private fun looksLikePhoneNumber(text: String): Boolean {
        if (text.isBlank()) return false
        val hasLetters = text.any { it.isLetter() }
        val digitCount = text.count { it.isDigit() }
        return !hasLetters && digitCount >= 3
    }

    /**
     * Returns true if the string contains alphabetic letters (e.g. a Contact Name).
     */
    private fun containsContactLetters(text: String): Boolean {
        return text.any { it.isLetter() }
    }

    private fun buildAssembledNotification(uid: Int): AssembledAncsNotification {
        val meta = sourceMetadataByUid[uid]
        val categoryId = meta?.categoryId ?: CATEGORY_ID_OTHER
        val rawAppId = collectedAttributes[ATTRIBUTE_ID_APP_IDENTIFIER.toInt()].orEmpty()
        val rawTitle = collectedAttributes[ATTRIBUTE_ID_TITLE.toInt()].orEmpty()
        val rawSubtitle = collectedAttributes[ATTRIBUTE_ID_SUBTITLE.toInt()].orEmpty()
        val rawMessage = collectedAttributes[ATTRIBUTE_ID_MESSAGE.toInt()].orEmpty()

        val friendlyAppName = resolveFriendlyAppName(rawAppId, categoryId)

        if (categoryId == CATEGORY_ID_INCOMING_CALL || categoryId == CATEGORY_ID_MISSED_CALL) {
            // Smart Contact Name vs Phone Number classification for iOS Calls:
            // Collect all non-blank candidate fields from Title (0x01), Subtitle (0x02), and Message (0x03)
            val candidates = listOf(rawTitle, rawSubtitle, rawMessage)
                .map { it.trim() }
                .filter {
                    it.isNotBlank() &&
                        !it.equals("Incoming Call", ignoreCase = true) &&
                        !it.equals("Missed Call", ignoreCase = true)
                }

            val contactNameCandidate = candidates.firstOrNull { containsContactLetters(it) }
            val phoneNumberCandidate = candidates.firstOrNull { looksLikePhoneNumber(it) }

            val resolvedCallerName = when {
                contactNameCandidate != null -> contactNameCandidate
                phoneNumberCandidate != null -> phoneNumberCandidate
                rawTitle.isNotBlank() -> rawTitle
                else -> if (categoryId == CATEGORY_ID_MISSED_CALL) "Missed Call" else "Incoming Call"
            }

            val resolvedPhoneSubtitle = when {
                phoneNumberCandidate != null && phoneNumberCandidate != resolvedCallerName ->
                    phoneNumberCandidate
                rawSubtitle.isNotBlank() && rawSubtitle != resolvedCallerName ->
                    rawSubtitle
                else -> ""
            }

            val resolvedCallMessage = when {
                rawMessage.isNotBlank() &&
                    rawMessage != resolvedCallerName &&
                    rawMessage != resolvedPhoneSubtitle -> rawMessage
                resolvedPhoneSubtitle.isNotBlank() -> "Caller ID: $resolvedPhoneSubtitle"
                else -> "iPhone Call ($friendlyAppName)"
            }

            return AssembledAncsNotification(
                notificationUid = uid,
                categoryId = categoryId,
                eventFlags = meta?.eventFlags ?: 0,
                appIdentifier = rawAppId.ifBlank { "com.apple.mobilephone" },
                appDisplayName = friendlyAppName,
                title = resolvedCallerName,
                subtitle = resolvedPhoneSubtitle,
                message = resolvedCallMessage,
                rawAppId = rawAppId,
                rawTitle = rawTitle,
                rawSubtitle = rawSubtitle,
                rawMessage = rawMessage,
                hasPositiveAction = meta?.hasPositiveAction ?: (categoryId == CATEGORY_ID_INCOMING_CALL),
                hasNegativeAction = meta?.hasNegativeAction ?: true
            )
        }

        // Standard Message / App Alert resolution (Prioritizing Attribute 0x03 Message Body)
        val resolvedTitle = when {
            rawTitle.isNotBlank() -> rawTitle
            rawSubtitle.isNotBlank() -> rawSubtitle
            else -> friendlyAppName
        }

        val resolvedSubtitle = when {
            rawTitle.isNotBlank() && rawSubtitle.isNotBlank() && rawSubtitle != rawTitle -> rawSubtitle
            else -> friendlyAppName
        }

        val resolvedMessage = when {
            rawMessage.isNotBlank() -> rawMessage
            rawSubtitle.isNotBlank() && rawSubtitle != resolvedTitle -> rawSubtitle
            rawTitle.isNotBlank() -> rawTitle
            else -> "New notification from $friendlyAppName"
        }

        return AssembledAncsNotification(
            notificationUid = uid,
            categoryId = categoryId,
            eventFlags = meta?.eventFlags ?: 0,
            appIdentifier = rawAppId.ifBlank { "com.apple.Notifications" },
            appDisplayName = friendlyAppName,
            title = resolvedTitle,
            subtitle = resolvedSubtitle,
            message = resolvedMessage,
            rawAppId = rawAppId,
            rawTitle = rawTitle,
            rawSubtitle = rawSubtitle,
            rawMessage = rawMessage,
            hasPositiveAction = meta?.hasPositiveAction ?: false,
            hasNegativeAction = meta?.hasNegativeAction ?: true
        )
    }

    private fun emitCurrentActiveNotification() {
        val uid = activeCommandUid ?: return
        val assembled = buildAssembledNotification(uid)
        resetCurrentNotificationState()
        onNotificationAssembled(assembled)
    }

    fun resolveFriendlyAppName(appId: String, categoryId: Int): String {
        val lower = appId.lowercase()
        return when {
            lower.contains("mobilephone") || lower.contains("incallservice") ||
                categoryId == CATEGORY_ID_INCOMING_CALL -> "Phone"
            categoryId == CATEGORY_ID_MISSED_CALL -> "Missed Call"
            lower.contains("mobilesms") -> "Messages"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("telegram") || lower.contains("telegraph") -> "Telegram"
            lower.contains("instagram") || lower.contains("burbn") -> "Instagram"
            lower.contains("gmail") || lower.contains("mobilemail") -> "Mail"
            lower.contains("slack") -> "Slack"
            lower.contains("teams") -> "Teams"
            lower.contains("discord") -> "Discord"
            lower.contains("messenger") || lower.contains("facebook") -> "Messenger"
            lower.contains("snapchat") -> "Snapchat"
            appId.isNotBlank() -> appId.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            else -> when (categoryId) {
                CATEGORY_ID_SOCIAL -> "Social Message"
                CATEGORY_ID_EMAIL -> "Mail"
                CATEGORY_ID_SCHEDULE -> "Calendar"
                else -> "iPhone Alert"
            }
        }
    }

    private fun resetCurrentNotificationState() {
        activeCommandUid = null
        currentAttributeId = null
        currentAttributeExpectedLen = -1
        collectedAttributes.clear()
    }

    @Synchronized
    fun resetParser() {
        rawStreamBuffer.reset()
        resetCurrentNotificationState()
    }
}
