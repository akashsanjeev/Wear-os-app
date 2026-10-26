export interface SimulatedAncsPreset {
  id: string;
  label: string;
  eventId: 0 | 1 | 2;
  eventFlags: number;
  categoryId: number;
  categoryCount: number;
  appBundleId: string;
  title: string;
  message: string;
}

export interface HexPacketLog {
  id: string;
  timestamp: string;
  direction: 'iOS -> Watch' | 'Watch -> iOS' | 'Watch Internal';
  characteristic: 'Notification Source (9FBF...)' | 'Control Point (69D1...)' | 'Data Source (22EA...)' | 'GATT / System';
  summary: string;
  hexBytes: string;
  byteCount: number;
}

export interface WatchAlertItem {
  notificationUid: number;
  categoryId: number;
  isIncomingCall: boolean;
  isPreExisting: boolean;
  appBundleId: string;
  appDisplayName: string;
  title: string;
  message: string;
  timestamp: string;
  wakeBehavior: 'Screen Wake + Continuous Call Vibe' | 'Screen Wake + Full-Screen Message Card';
  callResolution?: 'Accepted (0x00)' | 'Declined (0x01)';
}

const IOS_BUNDLE_MAP: Record<string, string> = {
  'com.apple.MobileSMS': 'Messages',
  'com.apple.mobilephone': 'Phone',
  'com.apple.facetime': 'FaceTime',
  'com.apple.mobilemail': 'Mail',
  'net.whatsapp.WhatsApp': 'WhatsApp',
  'ph.telegra.Telegraph': 'Telegram',
  'com.burbn.instagram': 'Instagram',
  'com.Slack': 'Slack',
  'com.google.Gmail': 'Gmail',
};

export function resolveBundleDisplayName(bundleId: string): string {
  if (IOS_BUNDLE_MAP[bundleId]) return IOS_BUNDLE_MAP[bundleId];
  const tail = bundleId.split('.').pop() || bundleId;
  return tail.charAt(0).toUpperCase() + tail.slice(1);
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).toUpperCase().padStart(2, '0'))
    .join(' ');
}

export function writeUint32LE(value: number): number[] {
  return [
    value & 0xff,
    (value >>> 8) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 24) & 0xff,
  ];
}

export function writeUint16LE(value: number): number[] {
  return [value & 0xff, (value >>> 8) & 0xff];
}

/**
 * Builds the 8-byte ANCS Notification Source packet:
 * Byte 0: EventID (0=Added, 1=Modified, 2=Removed)
 * Byte 1: EventFlags (bit 0=Silent, bit 2=PreExisting, bit 3=Positive, bit 4=Negative)
 * Byte 2: CategoryID (1=Incoming Call, 2=Missed Call, 4=Social)
 * Byte 3: CategoryCount
 * Bytes 4..7: NotificationUID (Little-Endian uint32)
 */
export function buildNotificationSource8BytePacket(
  eventId: number,
  eventFlags: number,
  categoryId: number,
  categoryCount: number,
  notificationUid: number
): Uint8Array {
  return new Uint8Array([
    eventId & 0xff,
    eventFlags & 0xff,
    categoryId & 0xff,
    categoryCount & 0xff,
    ...writeUint32LE(notificationUid),
  ]);
}

/**
 * Builds the 11-byte Control Point GetNotificationAttributes (0x00) request:
 * [0x00, UID(4B LE), 0x00(AppID), 0x01(Title), 0x40, 0x00(64B), 0x03(Message), 0x00, 0x01(256B)]
 */
export function buildControlPointGetAttributesRequest(notificationUid: number): Uint8Array {
  return new Uint8Array([
    0x00,
    ...writeUint32LE(notificationUid),
    0x00,
    0x01,
    0x40,
    0x00,
    0x03,
    0x00,
    0x01,
  ]);
}

/**
 * Builds the 6-byte Control Point PerformNotificationAction (0x02) request:
 * [0x02, UID(4B LE), ActionID(0x00=Positive/Accept, 0x01=Negative/Decline)]
 */
export function buildControlPointPerformActionRequest(
  notificationUid: number,
  actionId: 0x00 | 0x01
): Uint8Array {
  return new Uint8Array([0x02, ...writeUint32LE(notificationUid), actionId]);
}

/**
 * Builds the raw ANCS Data Source response stream and slices it into BLE MTU packets
 * (payload per GATT Notification = mtu - 3 bytes ATT header).
 */
export function buildFragmentedDataSourceChunks(
  notificationUid: number,
  appBundleId: string,
  title: string,
  message: string,
  mtu: number
): Uint8Array[] {
  const encoder = new TextEncoder();
  const appBytes = encoder.encode(appBundleId);
  const titleBytes = encoder.encode(title).slice(0, 64);
  const msgBytes = encoder.encode(message).slice(0, 256);

  const fullStream: number[] = [
    0x00, // CommandIDGetNotificationAttributes
    ...writeUint32LE(notificationUid),
    // Attribute 0: AppIdentifier (0x00) + 2B length LE + UTF-8 bytes
    0x00,
    ...writeUint16LE(appBytes.length),
    ...Array.from(appBytes),
    // Attribute 1: Title (0x01) + 2B length LE + UTF-8 bytes
    0x01,
    ...writeUint16LE(titleBytes.length),
    ...Array.from(titleBytes),
    // Attribute 3: Message (0x03) + 2B length LE + UTF-8 bytes
    0x03,
    ...writeUint16LE(msgBytes.length),
    ...Array.from(msgBytes),
  ];

  const maxAttPayload = Math.max(20, mtu - 3);
  const chunks: Uint8Array[] = [];
  for (let i = 0; i < fullStream.length; i += maxAttPayload) {
    chunks.push(new Uint8Array(fullStream.slice(i, i + maxAttPayload)));
  }
  return chunks;
}

export const ANCS_SIMULATOR_PRESETS: SimulatedAncsPreset[] = [
  {
    id: 'whatsapp-msg',
    label: 'WhatsApp Message (Wakes Screen + Full Message)',
    eventId: 0,
    eventFlags: 1 << 3,
    categoryId: 4,
    categoryCount: 3,
    appBundleId: 'net.whatsapp.WhatsApp',
    title: 'Alex Rivera',
    message: 'Hey! Are we still meeting at 6:30 PM for coffee? Let me know when your OnePlus Watch 2R gets this over BLE ANCS!',
  },
  {
    id: 'imessage-sms',
    label: 'Apple iMessage (com.apple.MobileSMS)',
    eventId: 0,
    eventFlags: 1 << 3,
    categoryId: 4,
    categoryCount: 2,
    appBundleId: 'com.apple.MobileSMS',
    title: '2FA Verification',
    message: 'Your Apple ID verification code is 849204. Do not share this code with anyone.',
  },
  {
    id: 'incoming-call',
    label: 'Incoming iPhone Call (CategoryID = 1)',
    eventId: 0,
    eventFlags: (1 << 1) | (1 << 3) | (1 << 4), // Important + PositiveAction + NegativeAction
    categoryId: 1,
    categoryCount: 1,
    appBundleId: 'com.apple.mobilephone',
    title: 'Sarah Jenkins (Mobile)',
    message: '+1 (415) 890-4321 · Incoming iPhone Call',
  },
  {
    id: 'pre-existing-alert',
    label: 'Pre-Existing iOS Alert (EventFlag 1<<2)',
    eventId: 0,
    eventFlags: 1 << 2, // PreExisting flag set
    categoryId: 6,
    categoryCount: 5,
    appBundleId: 'com.google.Gmail',
    title: 'GitHub Actions CI',
    message: '[OP2R-iOS-Bridge] Workflow run build-apk succeeded in 1m 42s (Pre-existing alert).',
  },
];
