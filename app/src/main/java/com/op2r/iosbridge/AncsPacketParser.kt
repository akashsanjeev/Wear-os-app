package com.op2r.iosbridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated ANCS (Apple Notification Center Service) Protocol Parser &
 * Deadlock-Proof Multi-Packet BLE MTU Fragmentation Reassembly State Machine.
 */
class AncsPacketParser(
    private val onNotificationAssembled: (AssembledAncsNotification) -> Unit
) {

    companion object {
        // EventID Constants
        const val EVENT_ID_NOTIFICATION_ADDED = 0
        const val EVENT_ID_NOTIFICATION_MODIFIED = 1
        const val EVENT_ID_NOTIFICATION_REMOVED = 2

        // EventFlags Bitmask Constants
        const val EVENT_FLAG_SILENT = 1 shl 0          // 0x01
        const val EVENT_FLAG_IMPORTANT = 1 shl 1       // 0x02
        const val EVENT_FLAG_PRE_EXISTING = 1 shl 2    // 0x04
        const val EVENT_FLAG_POSITIVE_ACTION = 1 shl 3 // 0x08
        const val EVENT_FLAG_NEGATIVE_ACTION = 1 shl 4 // 0x10

        // CategoryID Constants
        const val CATEGORY_ID_OTHER = 0
        const val CATEGORY_ID_INCOMING_CALL = 1
        const val CATEGORY_ID_MISSED_CALL = 2
        const val CATEGORY_ID_VOICEMAIL = 3
        const val CATEGORY_ID_SOCIAL = 4
        const val CATEGORY_ID_SCHEDULE = 5
        const val CATEGORY_ID_EMAIL = 6
        const val CATEGORY_ID_NEWS = 7
        const val CATEGORY_ID_HEALTH_AND_FITNESS = 8
        const val CATEGORY_ID_BUSINESS_AND_FINANCE = 9
        const val CATEGORY_ID_LOCATION = 10
        const val CATEGORY_ID_ENTERTAINMENT = 11

        // CommandID Constants
        const val COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES: Byte = 0x00
        const val COMMAND_ID_GET_APP_ATTRIBUTES: Byte = 0x01
        const val COMMAND_ID_PERFORM_NOTIFICATION_ACTION: Byte = 0x02

        // Notification AttributeID Constants
        const val ATTRIBUTE_ID_APP_IDENTIFIER: Int = 0x00
        const val ATTRIBUTE_ID_TITLE: Int = 0x01
        const val ATTRIBUTE_ID_SUBTITLE: Int = 0x02
        const val ATTRIBUTE_ID_MESSAGE: Int = 0x03
        const val ATTRIBUTE_ID_MESSAGE_SIZE: Int = 0x04
        const val ATTRIBUTE_ID_DATE: Int = 0x05
        const val ATTRIBUTE_ID_POSITIVE_ACTION_LABEL: Int = 0x06
        const val ATTRIBUTE_ID_NEGATIVE_ACTION_LABEL: Int = 0x07

        // ActionID Constants
        const val ACTION_ID_POSITIVE: Byte = 0x00
        const val ACTION_ID_NEGATIVE: Byte = 0x01

        // Max sanity bound for a single attribute payload (we request max 64B Title, 256B Message)
        private const val MAX_VALID_ATTRIBUTE_BYTES = 1024

        // Requested Attributes per Notification (AppIdentifier, Title, Message)
        private val EXPECTED_ATTRIBUTES = setOf(
            ATTRIBUTE_ID_APP_IDENTIFIER,
            ATTRIBUTE_ID_TITLE,
            ATTRIBUTE_ID_MESSAGE
        )

        private val IOS_BUNDLE_NAME_MAP = mapOf(
            "com.apple.MobileSMS" to "Messages",
            "com.apple.mobilephone" to "Phone",
            "com.apple.InCallService" to "Phone Call",
            "com.apple.facetime" to "FaceTime",
            "com.apple.mobilemail" to "Mail",
            "com.apple.mobilecal" to "Calendar",
            "com.apple.reminders" to "Reminders",
            "com.apple.Passbook" to "Apple Wallet",
            "net.whatsapp.WhatsApp" to "WhatsApp",
            "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
            "ph.telegra.Telegraph" to "Telegram",
            "org.whispersystems.signal" to "Signal",
            "com.burbn.instagram" to "Instagram",
            "com.facebook.Messenger" to "Messenger",
            "com.atebits.Tweetie2" to "X (Twitter)",
            "com.Slack" to "Slack",
            "com.hammerandchisel.discord" to "Discord",
            "com.google.Gmail" to "Gmail",
            "com.microsoft.Office.Outlook" to "Outlook",
            "com.microsoft.skype.teams" to "Teams",
            "com.spotify.client" to "Spotify",
            "com.google.ios.youtube" to "YouTube",
            "com.ubercab.UberClient" to "Uber"
        )

        /**
         * Maps raw iOS bundle identifier to a clean human-readable app title.
         */
        fun resolveAppDisplayName(bundleId: String): String {
            val cleaned = bundleId.trim()
            if (cleaned.isEmpty()) return "iPhone Alert"
            IOS_BUNDLE_NAME_MAP[cleaned]?.let { return it }
            val fallbackSegment = cleaned.substringAfterLast('.')
            return if (fallbackSegment.isNotBlank()) {
                fallbackSegment.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
            } else {
                cleaned
            }
        }

        /**
         * Resolves CategoryID integer into human-readable label.
         */
        fun resolveCategoryName(categoryId: Int): String {
            return when (categoryId) {
                CATEGORY_ID_INCOMING_CALL -> "Incoming Call"
                CATEGORY_ID_MISSED_CALL -> "Missed Call"
                CATEGORY_ID_VOICEMAIL -> "Voicemail"
                CATEGORY_ID_SOCIAL -> "Social / Message"
                CATEGORY_ID_SCHEDULE -> "Schedule"
                CATEGORY_ID_EMAIL -> "Email"
                CATEGORY_ID_NEWS -> "News"
                CATEGORY_ID_HEALTH_AND_FITNESS -> "Health & Fitness"
                CATEGORY_ID_BUSINESS_AND_FINANCE -> "Finance"
                CATEGORY_ID_LOCATION -> "Location"
                CATEGORY_ID_ENTERTAINMENT -> "Entertainment"
                else -> "iOS Notification"
            }
        }
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
        val appBundleId: String,
        val appDisplayName: String,
        val title: String,
        val message: String,
        val isFallbackOnly: Boolean = false
    ) {
        val isIncomingCall: Boolean get() = categoryId == CATEGORY_ID_INCOMING_CALL
        val isMissedCall: Boolean get() = categoryId == CATEGORY_ID_MISSED_CALL
        val isSilent: Boolean get() = (eventFlags and EVENT_FLAG_SILENT) != 0
    }

    private enum class ParserState {
        WAITING_FOR_COMMAND_HEADER,
        READING_ATTRIBUTE_HEADER,
        READING_ATTRIBUTE_PAYLOAD
    }

    // Metadata cache keyed by NotificationUID from the 8-byte Notification Source packet
    private val pendingEvents = ConcurrentHashMap<Int, NotificationSourceEvent>()

    // Stream buffer and state machine fields guarded by synchronization lock
    private val stateLock = Any()
    private val rawStreamBuffer = ByteArrayOutputStream()
    private var currentState = ParserState.WAITING_FOR_COMMAND_HEADER
    private var activeNotificationUid: Int = 0
    private var activeAttributeId: Int = 0
    private var activeAttributeLength: Int = 0
    private val collectedAttributes = mutableMapOf<Int, String>()

    /**
     * Parses an 8-byte ANCS Notification Source characteristic notification.
     * Byte 0: EventID
     * Byte 1: EventFlags
     * Byte 2: CategoryID
     * Byte 3: CategoryCount
     * Bytes 4..7: NotificationUID (uint32 Little-Endian)
     */
    fun parseNotificationSourcePayload(bytes: ByteArray): NotificationSourceEvent? {
        if (bytes.size < 8) return null
        val eventId = bytes[0].toInt() and 0xFF
        val eventFlags = bytes[1].toInt() and 0xFF
        val categoryId = bytes[2].toInt() and 0xFF
        val categoryCount = bytes[3].toInt() and 0xFF
        val notificationUid = ByteBuffer.wrap(bytes, 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

        val event = NotificationSourceEvent(
            eventId = eventId,
            eventFlags = eventFlags,
            categoryId = categoryId,
            categoryCount = categoryCount,
            notificationUid = notificationUid
        )

        if (eventId == EVENT_ID_NOTIFICATION_ADDED || eventId == EVENT_ID_NOTIFICATION_MODIFIED) {
            pendingEvents[notificationUid] = event
        } else if (eventId == EVENT_ID_NOTIFICATION_REMOVED) {
            pendingEvents.remove(notificationUid)
        }

        return event
    }

    /**
     * Builds a fallback notification directly from the 8-byte Notification Source event
     * so Incoming Calls and Messages still wake and alert the watch even if iOS Data Source
     * is delayed or restricted.
     */
    fun buildFallbackFromSourceEvent(event: NotificationSourceEvent): AssembledAncsNotification {
        val categoryLabel = resolveCategoryName(event.categoryId)
        val isCall = event.categoryId == CATEGORY_ID_INCOMING_CALL
        return AssembledAncsNotification(
            notificationUid = event.notificationUid,
            categoryId = event.categoryId,
            eventFlags = event.eventFlags,
            appBundleId = if (isCall) "com.apple.mobilephone" else "ios.ancs.category.${event.categoryId}",
            appDisplayName = if (isCall) "iPhone Call" else categoryLabel,
            title = if (isCall) "Incoming iPhone Call" else categoryLabel,
            message = if (isCall) {
                "Ringing on iPhone (UID #${event.notificationUid}) — Fetching Caller ID..."
            } else {
                "New $categoryLabel received from iPhone (UID #${event.notificationUid})"
            },
            isFallbackOnly = true
        )
    }

    /**
     * Resets the Data Source stream parser right before a new serialized Control Point (0x00)
     * attribute request is dispatched on the BLE wire, preventing any stale trailing bytes
     * from deadlocking the next notification.
     */
    fun prepareForAttributeRequest(expectedNotificationUid: Int) {
        synchronized(stateLock) {
            rawStreamBuffer.reset()
            collectedAttributes.clear()
            currentState = ParserState.WAITING_FOR_COMMAND_HEADER
            activeNotificationUid = expectedNotificationUid
            activeAttributeId = 0
            activeAttributeLength = 0
        }
    }

    /**
     * Builds the 11-byte Control Point request for CommandIDGetNotificationAttributes (0x00):
     * - CommandID: 0x00 (1 byte)
     * - NotificationUID: Little-Endian uint32 (4 bytes)
     * - AttributeIDAppIdentifier: 0x00 (1 byte, no length parameter)
     * - AttributeIDTitle: 0x01 + MaxLength 64 (0x40, 0x00 Little-Endian)
     * - AttributeIDMessage: 0x03 + MaxLength 256 (0x00, 0x01 Little-Endian)
     */
    fun buildGetNotificationAttributesCommand(notificationUid: Int): ByteArray {
        val buffer = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES)
        buffer.putInt(notificationUid)

        // Attribute 0: AppIdentifier (0x00) - no max length parameter in ANCS spec
        buffer.put(ATTRIBUTE_ID_APP_IDENTIFIER.toByte())

        // Attribute 1: Title (0x01) + 2-byte Little-Endian max length = 64 (0x40, 0x00)
        buffer.put(ATTRIBUTE_ID_TITLE.toByte())
        buffer.put(0x40.toByte())
        buffer.put(0x00.toByte())

        // Attribute 3: Message (0x03) + 2-byte Little-Endian max length = 256 (0x00, 0x01)
        buffer.put(ATTRIBUTE_ID_MESSAGE.toByte())
        buffer.put(0x00.toByte())
        buffer.put(0x01.toByte())

        return buffer.array()
    }

    /**
     * Builds the 6-byte Control Point request for CommandIDPerformNotificationAction (0x02):
     * - CommandID: 0x02 (1 byte)
     * - NotificationUID: Little-Endian uint32 (4 bytes)
     * - ActionID: 0x00 (Positive/Accept) or 0x01 (Negative/Decline) (1 byte)
     */
    fun buildPerformNotificationActionCommand(notificationUid: Int, actionId: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(COMMAND_ID_PERFORM_NOTIFICATION_ACTION)
        buffer.putInt(notificationUid)
        buffer.put(actionId)
        return buffer.array()
    }

    /**
     * Feeds a raw BLE packet from the ANCS Data Source characteristic (22EAC6E9-...)
     * into the multi-packet reassembly state machine.
     */
    fun onDataSourcePacketReceived(chunk: ByteArray) {
        if (chunk.isEmpty()) return

        val readyNotifications = mutableListOf<AssembledAncsNotification>()

        synchronized(stateLock) {
            rawStreamBuffer.write(chunk, 0, chunk.size)
            val currentBytes = rawStreamBuffer.toByteArray()
            var readOffset = 0

            loop@ while (readOffset < currentBytes.size) {
                val remaining = currentBytes.size - readOffset

                when (currentState) {
                    ParserState.WAITING_FOR_COMMAND_HEADER -> {
                        // Need 5 bytes: CommandID (1B) + NotificationUID (4B Little-Endian)
                        if (remaining < 5) break@loop

                        val commandId = currentBytes[readOffset]
                        if (commandId != COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES) {
                            readOffset += 1
                            continue@loop
                        }

                        activeNotificationUid = ByteBuffer.wrap(currentBytes, readOffset + 1, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                        collectedAttributes.clear()
                        readOffset += 5
                        currentState = ParserState.READING_ATTRIBUTE_HEADER
                    }

                    ParserState.READING_ATTRIBUTE_HEADER -> {
                        // Need 3 bytes: AttributeID (1B) + AttributeLength (2B Little-Endian)
                        if (remaining < 3) break@loop

                        val attrId = currentBytes[readOffset].toInt() and 0xFF
                        val lenLow = currentBytes[readOffset + 1].toInt() and 0xFF
                        val lenHigh = currentBytes[readOffset + 2].toInt() and 0xFF
                        val attrLen = lenLow or (lenHigh shl 8)

                        // Sanity check: prevent corrupted stream from deadlocking on bogus length
                        if (attrId !in EXPECTED_ATTRIBUTES || attrLen > MAX_VALID_ATTRIBUTE_BYTES) {
                            // Emit partial attributes if we already decoded Title or AppID, then reset
                            if (collectedAttributes.isNotEmpty()) {
                                emitCurrentAttributes(readyNotifications)
                            } else {
                                currentState = ParserState.WAITING_FOR_COMMAND_HEADER
                            }
                            readOffset += 1
                            continue@loop
                        }

                        activeAttributeId = attrId
                        activeAttributeLength = attrLen
                        readOffset += 3

                        if (activeAttributeLength == 0) {
                            collectedAttributes[activeAttributeId] = ""
                            checkIfAllAttributesComplete(readyNotifications)
                        } else {
                            currentState = ParserState.READING_ATTRIBUTE_PAYLOAD
                        }
                    }

                    ParserState.READING_ATTRIBUTE_PAYLOAD -> {
                        // Wait until all activeAttributeLength UTF-8 bytes have arrived across MTU chunks
                        if (remaining < activeAttributeLength) break@loop

                        val utf8String = String(
                            currentBytes,
                            readOffset,
                            activeAttributeLength,
                            StandardCharsets.UTF_8
                        ).trimEnd('\u0000')

                        collectedAttributes[activeAttributeId] = utf8String
                        readOffset += activeAttributeLength
                        currentState = ParserState.READING_ATTRIBUTE_HEADER

                        checkIfAllAttributesComplete(readyNotifications)
                    }
                }
            }

            // Compact buffer to retain only unconsumed trailing bytes for the next BLE packet
            rawStreamBuffer.reset()
            if (readOffset < currentBytes.size) {
                rawStreamBuffer.write(currentBytes, readOffset, currentBytes.size - readOffset)
            }
        }

        // Dispatch completed notifications outside synchronized lock
        for (notification in readyNotifications) {
            onNotificationAssembled(notification)
        }
    }

    private fun checkIfAllAttributesComplete(outList: MutableList<AssembledAncsNotification>) {
        if (collectedAttributes.keys.containsAll(EXPECTED_ATTRIBUTES)) {
            emitCurrentAttributes(outList)
        }
    }

    private fun emitCurrentAttributes(outList: MutableList<AssembledAncsNotification>) {
        val uid = activeNotificationUid
        val sourceEvent = pendingEvents[uid]
        val bundleId = collectedAttributes[ATTRIBUTE_ID_APP_IDENTIFIER].orEmpty()
        val appName = resolveAppDisplayName(bundleId)
        val rawTitle = collectedAttributes[ATTRIBUTE_ID_TITLE].orEmpty()
        val rawMessage = collectedAttributes[ATTRIBUTE_ID_MESSAGE].orEmpty()

        val finalTitle = when {
            rawTitle.isNotBlank() -> rawTitle
            sourceEvent?.categoryId == CATEGORY_ID_INCOMING_CALL -> "Incoming iPhone Call"
            sourceEvent?.categoryId == CATEGORY_ID_MISSED_CALL -> "Missed Call"
            else -> appName
        }

        val finalMessage = when {
            rawMessage.isNotBlank() -> rawMessage
            sourceEvent?.categoryId == CATEGORY_ID_INCOMING_CALL -> "Incoming call on iPhone via ANCS"
            sourceEvent?.categoryId == CATEGORY_ID_MISSED_CALL -> "Missed call on iPhone"
            else -> "Notification from $appName"
        }

        outList.add(
            AssembledAncsNotification(
                notificationUid = uid,
                categoryId = sourceEvent?.categoryId ?: CATEGORY_ID_OTHER,
                eventFlags = sourceEvent?.eventFlags ?: 0,
                appBundleId = bundleId,
                appDisplayName = appName,
                title = finalTitle,
                message = finalMessage,
                isFallbackOnly = false
            )
        )

        collectedAttributes.clear()
        currentState = ParserState.WAITING_FOR_COMMAND_HEADER
    }

    /**
     * Resets parser state upon BLE disconnect or user reset.
     */
    fun reset() {
        synchronized(stateLock) {
            rawStreamBuffer.reset()
            collectedAttributes.clear()
            currentState = ParserState.WAITING_FOR_COMMAND_HEADER
            activeNotificationUid = 0
            activeAttributeId = 0
            activeAttributeLength = 0
        }
        pendingEvents.clear()
    }
}
