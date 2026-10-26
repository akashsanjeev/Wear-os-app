/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useCallback } from 'react';
import JSZip from 'jszip';
import { motion, AnimatePresence } from 'motion/react';
import {
  Copy,
  Check,
  Download,
  Bluetooth,
  Radio,
  ShieldCheck,
  ShieldAlert,
  RotateCcw,
  PhoneIncoming,
  PhoneOff,
  Send,
  Trash2,
  FileCode,
  FolderArchive,
  Sliders,
  Search,
  Activity,
  Layers,
  Sparkles,
  MessageSquareText,
  X,
  Eye,
  ExternalLink,
  CheckCircle2,
  RefreshCw,
} from 'lucide-react';
import { PROJECT_FILES, ProjectFileItem } from './data/projectFiles';
import {
  ANCS_SIMULATOR_PRESETS,
  SimulatedAncsPreset,
  HexPacketLog,
  WatchAlertItem,
  buildNotificationSource8BytePacket,
  buildControlPointGetAttributesRequest,
  buildControlPointPerformActionRequest,
  buildFragmentedDataSourceChunks,
  bytesToHex,
  resolveBundleDisplayName,
} from './utils/ancsSimulatorEngine';

type WatchConnectionState =
  | 'Disconnected'
  | 'Advertising as OP2R-Bridge'
  | 'Scanning (Touch Watch to iPhone)'
  | 'Reconnecting (GATT_ERROR 133)...'
  | 'Bonded - Listening to ANCS';

interface GithubBuildStatus {
  repoSlug: string;
  runStatus?: string;
  runConclusion?: string | null;
  runHtmlUrl?: string;
  runCreatedAt?: string;
  headSha?: string;
  directApkUrl?: string;
  error?: string;
}

const DEFAULT_GITHUB_REPO = 'akashsanjeev/Wear-os-app';

export default function App() {
  // Code Explorer state
  const [selectedFileId, setSelectedFileId] = useState<string>(PROJECT_FILES[7].id); // Default to .github/workflows/build-apk.yml
  const [copiedFileId, setCopiedFileId] = useState<string | null>(null);
  const [isExportingZip, setIsExportingZip] = useState<boolean>(false);
  const [searchQuery, setSearchQuery] = useState<string>('');

  // GitHub Live APK Fetcher state (Pre-linked to akashsanjeev/Wear-os-app)
  const [githubInput, setGithubInput] = useState<string>(DEFAULT_GITHUB_REPO);
  const [isCheckingGithub, setIsCheckingGithub] = useState<boolean>(false);
  const [githubStatus, setGithubStatus] = useState<GithubBuildStatus | null>(null);

  // Watch & ANCS Simulator state
  const [watchState, setWatchState] = useState<WatchConnectionState>('Bonded - Listening to ANCS');
  const [ignorePreExisting, setIgnorePreExisting] = useState<boolean>(true);
  const [simulatedMtu, setSimulatedMtu] = useState<number>(247);
  const [nextUid, setNextUid] = useState<number>(1042);
  const [watchToastMessage, setWatchToastMessage] = useState<string | null>(null);

  // Full-Screen Message Popup Card active UID on the watch face (null shows the circular-safe main menu)
  const [activeWatchPopupUid, setActiveWatchPopupUid] = useState<number | null>(null);

  // Custom ANCS Notification state
  const [customBundleId, setCustomBundleId] = useState<string>('net.whatsapp.WhatsApp');
  const [customCategoryId, setCustomCategoryId] = useState<number>(4);
  const [customTitle, setCustomTitle] = useState<string>('Elena Rostova');
  const [customMessage, setCustomMessage] = useState<string>(
    'Hey! Are we still meeting at 6:30 PM? This full message woke up your Wear OS 5.0 OnePlus Watch 2R screen!'
  );
  const [customPreExisting, setCustomPreExisting] = useState<boolean>(false);

  // Watch Notifications & Hex Log
  const [watchAlerts, setWatchAlerts] = useState<WatchAlertItem[]>([
    {
      notificationUid: 1041,
      categoryId: 4,
      isIncomingCall: false,
      isPreExisting: false,
      appBundleId: 'net.whatsapp.WhatsApp',
      appDisplayName: 'WhatsApp',
      title: 'Alex Rivera',
      message:
        'Hey! Are we still meeting at 6:30 PM for coffee? Let me know when your OnePlus Watch 2R gets this over BLE ANCS!',
      timestamp: '20:25:04',
      wakeBehavior: 'Screen Wake + Full-Screen Message Card',
    },
  ]);

  const [hexLogs, setHexLogs] = useState<HexPacketLog[]>([
    {
      id: 'init-1',
      timestamp: '20:25:00',
      direction: 'Watch -> iOS',
      characteristic: 'GATT / System',
      summary: 'gatt.requestMtu(247) -> Negotiated 247B MTU over TRANSPORT_LE (Wear OS 5.0)',
      hexBytes: 'F7 00',
      byteCount: 2,
    },
    {
      id: 'init-2',
      timestamp: '20:25:01',
      direction: 'Watch -> iOS',
      characteristic: 'Data Source (22EA...)',
      summary: 'Step 1 (Strict Order): Write CCCD (0x2902) Enable Notification on Data Source FIRST',
      hexBytes: '01 00',
      byteCount: 2,
    },
    {
      id: 'init-3',
      timestamp: '20:25:02',
      direction: 'Watch -> iOS',
      characteristic: 'Notification Source (9FBF...)',
      summary: 'Step 2 (After onDescriptorWrite): Write CCCD (0x2902) Enable Notification on Notification Source',
      hexBytes: '01 00',
      byteCount: 2,
    },
  ]);

  const selectedFile: ProjectFileItem =
    PROJECT_FILES.find((f) => f.id === selectedFileId) || PROJECT_FILES[0];

  const filteredFiles = PROJECT_FILES.filter(
    (f) =>
      f.path.toLowerCase().includes(searchQuery.toLowerCase()) ||
      f.summary.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const nowTime = () => {
    const d = new Date();
    return d.toTimeString().split(' ')[0];
  };

  const showWatchToast = (msg: string) => {
    setWatchToastMessage(msg);
    window.setTimeout(() => {
      setWatchToastMessage((prev) => (prev === msg ? null : prev));
    }, 2800);
  };

  // Parse owner/repo from URL or slug and check GitHub Actions + Releases for the compiled APK
  const handleCheckGithubApk = useCallback(async (overrideRepo?: string) => {
    const rawTarget = overrideRepo ?? githubInput;
    const cleaned = rawTarget
      .trim()
      .replace(/^https?:\/\/github\.com\//i, '')
      .replace(/\.git$/i, '')
      .split('/')
      .slice(0, 2)
      .join('/');

    if (!cleaned || !cleaned.includes('/')) {
      setGithubStatus({
        repoSlug: cleaned,
        error: 'Please enter your GitHub repository as username/repo-name or paste the full GitHub URL.',
      });
      return;
    }

    setIsCheckingGithub(true);

    try {
      // 1. Check workflow runs
      const runsRes = await fetch(`https://api.github.com/repos/${cleaned}/actions/runs?per_page=5`);
      if (!runsRes.ok) {
        setGithubStatus({
          repoSlug: cleaned,
          error:
            runsRes.status === 404
              ? `Repository "${cleaned}" is private or not found. Click "Open GitHub Actions Tab" below to download your APK directly from GitHub.`
              : `GitHub API returned status ${runsRes.status}.`,
        });
        setIsCheckingGithub(false);
        return;
      }

      const runsData = await runsRes.json();
      const latestRun = runsData.workflow_runs?.[0];

      // 2. Check if a Release APK asset is published
      let directApkUrl: string | undefined;
      try {
        const relRes = await fetch(`https://api.github.com/repos/${cleaned}/releases`);
        if (relRes.ok) {
          const releases = await relRes.json();
          for (const rel of releases) {
            const apkAsset = rel.assets?.find((a: { name?: string; browser_download_url?: string }) =>
              a.name?.endsWith('.apk')
            );
            if (apkAsset?.browser_download_url) {
              directApkUrl = apkAsset.browser_download_url;
              break;
            }
          }
        }
      } catch {
        // Ignore release check failure
      }

      setGithubStatus({
        repoSlug: cleaned,
        runStatus: latestRun?.status || 'No runs yet',
        runConclusion: latestRun?.conclusion || null,
        runHtmlUrl: latestRun?.html_url || `https://github.com/${cleaned}/actions`,
        runCreatedAt: latestRun?.created_at
          ? new Date(latestRun.created_at).toLocaleTimeString()
          : undefined,
        headSha: latestRun?.head_sha ? String(latestRun.head_sha).slice(0, 7) : undefined,
        directApkUrl,
      });
    } catch {
      setGithubStatus({
        repoSlug: cleaned,
        error: 'Could not reach GitHub API. Use the direct Actions link below.',
      });
    } finally {
      setIsCheckingGithub(false);
    }
  }, [githubInput]);

  useEffect(() => {
    handleCheckGithubApk(DEFAULT_GITHUB_REPO);
  }, [handleCheckGithubApk]);

  // Copy Single File
  const handleCopyCode = async (file: ProjectFileItem) => {
    await navigator.clipboard.writeText(file.content);
    setCopiedFileId(file.id);
    window.setTimeout(() => setCopiedFileId(null), 1800);
  };

  // Download Single Raw File
  const handleDownloadSingleFile = (file: ProjectFileItem) => {
    const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.path.split('/').pop() || file.title;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // Download Complete Project (.zip)
  const handleDownloadCompleteZip = async () => {
    setIsExportingZip(true);
    try {
      const zip = new JSZip();
      for (const file of PROJECT_FILES) {
        zip.file(file.path, file.content);
      }
      const readmeContent = `# OP2R-Bridge: Standalone Wear OS 5.0 to iPhone 14 Native ANCS BLE Bridge

Target Hardware: **OnePlus Watch 2R (\`OPWWE234\` — 1.43" AMOLED, 466x466 px)** running **Wear OS 5.0 (Android 14 / API 34)**
Target iOS Source: **iPhone 14 (iOS)** via native Apple Notification Center Service (\`7905F431-B5CE-4E99-A40F-4B1E122D00D0\`)

## Key OnePlus Watch 2R Features
- **Circular-Inscribed 466x466 Layout**: Tapered chord widths (\`52% -> 64% -> 82% -> 76% -> 64%\`) so no pill button ever clips against the circular bezel.
- **Automatic Screen Wake & Full-Screen Message Popup Card**: Wakes the display via \`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP\` and \`setTurnScreenOn(true)\`, displaying the App Name, Sender Title, and Full Message Body directly inside an inscribed circular card.
- **Incoming Call Accept / Decline Controls**: Full-screen call alert with \`0x00\` (Accept) and \`0x01\` (Decline) ANCS Control Point actions.
- **Dual-Chip Anti-Kill Protection**: Persistent \`connectedDevice\` foreground service + renewable \`PARTIAL_WAKE_LOCK\` + status \`133\` auto-recovery.

## How to Get the Compiled APK on GitHub
1. Open your GitHub repository in your browser.
2. Click the **Actions** tab at the top (or check the **Releases** sidebar on the right for \`OP2R-iOS-Bridge-WearOS5.apk\`).
3. Click the latest workflow run (**Build Wear OS ANCS Bridge APK**) and download \`op2r-ios-ancs-bridge-debug-apk\` under **Artifacts** at the bottom of the page.
4. Sideload onto your OnePlus Watch 2R via Wireless ADB:
   \`\`\`bash
   adb connect <WATCH_IP:PORT>
   adb install -r app-debug.apk
   \`\`\`
`;
      zip.file('README.md', readmeContent);

      const blob = await zip.generateAsync({ type: 'blob' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'OP2R-iOS-ANCS-Bridge-WearOS5.zip';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } finally {
      setIsExportingZip(false);
    }
  };

  // Mode A: Peripheral Mode (Advertise to iPhone)
  const handleStartPeripheralMode = () => {
    setWatchState('Advertising as OP2R-Bridge');
    showWatchToast('BLE Advertiser Started');
    setHexLogs((prev) => [
      {
        id: `adv-${Date.now()}`,
        timestamp: nowTime(),
        direction: 'Watch -> iOS',
        characteristic: 'GATT / System',
        summary:
          'Mode A: BluetoothLeAdvertiser ("OP2R-Bridge", connectable=true) + Solicited UUID 7905F431-B5CE-4E99-A40F-4B1E122D00D0 + Local GattServer (0x180D Heart Rate)',
        hexBytes: '02 01 06 03 03 0D 18 11 15 D0 00 2D 12 1E 4B 0F A4 99 4E CE B5 31 F4 05 79',
        byteCount: 26,
      },
      ...prev,
    ]);

    window.setTimeout(() => {
      setWatchState('Bonded - Listening to ANCS');
      showWatchToast('Bonded · Listening to ANCS');
      setHexLogs((prev) => [
        {
          id: `bond-a-${Date.now()}`,
          timestamp: nowTime(),
          direction: 'Watch -> iOS',
          characteristic: 'GATT / System',
          summary:
            'iPhone connected to GattServer -> Reverse connectGatt(TRANSPORT_LE) -> MTU 247 -> Subscribed Data Source (22EA...) FIRST -> Subscribed Notification Source (9FBF...)',
          hexBytes: '01 00',
          byteCount: 2,
        },
        ...prev,
      ]);
    }, 1100);
  };

  // Mode B: Scanner Mode (RSSI > -65 dBm)
  const handleStartScannerMode = () => {
    setWatchState('Scanning (Touch Watch to iPhone)');
    showWatchToast('Scanning Proximity Beacon...');
    setHexLogs((prev) => [
      {
        id: `scan-${Date.now()}`,
        timestamp: nowTime(),
        direction: 'Watch Internal',
        characteristic: 'GATT / System',
        summary: 'Mode B: BluetoothLeScanner (SCAN_MODE_LOW_LATENCY) filtering for RSSI > -65 dBm proximity',
        hexBytes: 'SCAN_START',
        byteCount: 0,
      },
      ...prev,
    ]);

    window.setTimeout(() => {
      setWatchState('Bonded - Listening to ANCS');
      showWatchToast('Detected (-52 dBm) · Bonded');
      setHexLogs((prev) => [
        {
          id: `bond-b-${Date.now()}`,
          timestamp: nowTime(),
          direction: 'Watch -> iOS',
          characteristic: 'GATT / System',
          summary:
            'Proximity iPhone detected at -52 dBm (> -65 dBm) -> device.createBond() -> BOND_BONDED -> discoverServices() -> Subscribed Data Source FIRST -> Notification Source',
          hexBytes: '01 00',
          byteCount: 2,
        },
        ...prev,
      ]);
    }, 1100);
  };

  // Simulate Error 133 Auto-Recovery
  const handleSimulateError133 = () => {
    setWatchState('Reconnecting (GATT_ERROR 133)...');
    showWatchToast('Error 133 -> 1000ms Backoff');
    setHexLogs((prev) => [
      {
        id: `err133-${Date.now()}`,
        timestamp: nowTime(),
        direction: 'Watch Internal',
        characteristic: 'GATT / System',
        summary:
          'onConnectionStateChange(status=133 GATT_ERROR) -> gatt.close() -> delay(1000ms) -> device.connectGatt(context, false, callback, TRANSPORT_LE)',
        hexBytes: '85 00',
        byteCount: 2,
      },
      ...prev,
    ]);

    window.setTimeout(() => {
      setWatchState('Bonded - Listening to ANCS');
      showWatchToast('Reconnected over TRANSPORT_LE');
      setHexLogs((prev) => [
        {
          id: `rec133-${Date.now()}`,
          timestamp: nowTime(),
          direction: 'Watch -> iOS',
          characteristic: 'GATT / System',
          summary: '1000ms Backoff Complete -> Reconnected GATT over TRANSPORT_LE & Re-subscribed to ANCS',
          hexBytes: '01 00',
          byteCount: 2,
        },
        ...prev,
      ]);
    }, 1000);
  };

  // Reset BLE
  const handleResetBle = () => {
    setWatchState('Disconnected');
    setActiveWatchPopupUid(null);
    showWatchToast('BLE Session Reset');
    setHexLogs((prev) => [
      {
        id: `reset-${Date.now()}`,
        timestamp: nowTime(),
        direction: 'Watch Internal',
        characteristic: 'GATT / System',
        summary: 'Reset / Unbind BLE: Stopped advertiser/scanner, cleared GATT queue, closed GATT client/server',
        hexBytes: '00',
        byteCount: 1,
      },
      ...prev,
    ]);
  };

  // Fire ANCS Event -> Reassemble -> Wake Screen & Show Full-Screen Message Popup Card on Watch
  const fireAncsNotificationSequence = (preset: {
    eventId: 0 | 1 | 2;
    eventFlags: number;
    categoryId: number;
    categoryCount: number;
    appBundleId: string;
    title: string;
    message: string;
  }) => {
    if (watchState !== 'Bonded - Listening to ANCS') {
      setWatchState('Bonded - Listening to ANCS');
    }

    const uid = nextUid;
    setNextUid((prev) => prev + 1);
    const ts = nowTime();

    const notifSource8Bytes = buildNotificationSource8BytePacket(
      preset.eventId,
      preset.eventFlags,
      preset.categoryId,
      preset.categoryCount,
      uid
    );
    const isPreExisting = (preset.eventFlags & (1 << 2)) !== 0;

    const newLogs: HexPacketLog[] = [];

    if (ignorePreExisting && isPreExisting) {
      newLogs.push({
        id: `ns-ignored-${uid}-${Date.now()}`,
        timestamp: ts,
        direction: 'iOS -> Watch',
        characteristic: 'Notification Source (9FBF...)',
        summary: `8B EventID=${preset.eventId} UID=${uid} EventFlags=0x${preset.eventFlags
          .toString(16)
          .toUpperCase()} (PreExisting=true -> Ignored by Watch UI Filter)`,
        hexBytes: bytesToHex(notifSource8Bytes),
        byteCount: notifSource8Bytes.length,
      });
      setHexLogs((prev) => [...newLogs, ...prev]);
      showWatchToast('Ignored Pre-Existing (1<<2)');
      return;
    }

    const controlPoint11Bytes = buildControlPointGetAttributesRequest(uid);
    const dataSourceChunks = buildFragmentedDataSourceChunks(
      uid,
      preset.appBundleId,
      preset.title,
      preset.message,
      simulatedMtu
    );

    dataSourceChunks
      .slice()
      .reverse()
      .forEach((chunk, revIdx) => {
        const chunkIndex = dataSourceChunks.length - revIdx;
        newLogs.push({
          id: `ds-${uid}-${chunkIndex}-${Date.now()}`,
          timestamp: ts,
          direction: 'iOS -> Watch',
          characteristic: 'Data Source (22EA...)',
          summary: `MTU=${simulatedMtu}B Fragment ${chunkIndex}/${dataSourceChunks.length} -> AncsPacketParser Reassembler${
            chunkIndex === dataSourceChunks.length ? ' [COMPLETE -> WAKE SCREEN]' : ''
          }`,
          hexBytes: bytesToHex(chunk),
          byteCount: chunk.length,
        });
      });

    newLogs.push({
      id: `cp-${uid}-${Date.now()}`,
      timestamp: ts,
      direction: 'Watch -> iOS',
      characteristic: 'Control Point (69D1...)',
      summary: `CommandIDGetNotificationAttributes (0x00) UID=${uid} [AppID, Title 64B, Message 256B]`,
      hexBytes: bytesToHex(controlPoint11Bytes),
      byteCount: controlPoint11Bytes.length,
    });

    newLogs.push({
      id: `ns-${uid}-${Date.now()}`,
      timestamp: ts,
      direction: 'iOS -> Watch',
      characteristic: 'Notification Source (9FBF...)',
      summary: `8B Notification Source: EventID=${preset.eventId} · CategoryID=${preset.categoryId} · UID=${uid}`,
      hexBytes: bytesToHex(notifSource8Bytes),
      byteCount: notifSource8Bytes.length,
    });

    setHexLogs((prev) => [...newLogs, ...prev]);

    const isCall = preset.categoryId === 1;
    const wakeBehavior = isCall
      ? 'Screen Wake + Continuous Call Vibe'
      : 'Screen Wake + Full-Screen Message Card';

    showWatchToast(isCall ? 'Screen Woke · Incoming Call' : 'Screen Woke · Message Card');

    const assembledAlert: WatchAlertItem = {
      notificationUid: uid,
      categoryId: preset.categoryId,
      isIncomingCall: isCall,
      isPreExisting,
      appBundleId: preset.appBundleId,
      appDisplayName: resolveBundleDisplayName(preset.appBundleId),
      title: preset.title,
      message: preset.message,
      timestamp: ts,
      wakeBehavior,
    };

    setWatchAlerts((prev) => [assembledAlert, ...prev]);
    // Immediately wake watch screen and present the Full-Screen Popup Card for this notification
    setActiveWatchPopupUid(uid);
  };

  // Perform Call Action
  const handlePerformCallAction = (uid: number, actionId: 0x00 | 0x01) => {
    const packet = buildControlPointPerformActionRequest(uid, actionId);
    const label = actionId === 0x00 ? 'Positive / Accept (0x00)' : 'Negative / Decline (0x01)';

    setHexLogs((prev) => [
      {
        id: `act-${uid}-${Date.now()}`,
        timestamp: nowTime(),
        direction: 'Watch -> iOS',
        characteristic: 'Control Point (69D1...)',
        summary: `CommandIDPerformNotificationAction (0x02) · UID=${uid} · ActionID=${label}`,
        hexBytes: bytesToHex(packet),
        byteCount: packet.length,
      },
      ...prev,
    ]);

    setWatchAlerts((prev) =>
      prev.map((item) =>
        item.notificationUid === uid
          ? {
              ...item,
              callResolution: actionId === 0x00 ? 'Accepted (0x00)' : 'Declined (0x01)',
            }
          : item
      )
    );

    if (activeWatchPopupUid === uid) {
      setActiveWatchPopupUid(null);
    }
  };

  // Remove Notification
  const handleSimulateRemoteRemove = (uid: number, categoryId: number) => {
    const packet = buildNotificationSource8BytePacket(2, 0, categoryId, 0, uid);
    setHexLogs((prev) => [
      {
        id: `rem-${uid}-${Date.now()}`,
        timestamp: nowTime(),
        direction: 'iOS -> Watch',
        characteristic: 'Notification Source (9FBF...)',
        summary: `EventID=2 (Removed) UID=${uid} -> notificationManager.cancel(${uid}) on Watch`,
        hexBytes: bytesToHex(packet),
        byteCount: packet.length,
      },
      ...prev,
    ]);
    setWatchAlerts((prev) => prev.filter((a) => a.notificationUid !== uid));
    if (activeWatchPopupUid === uid) {
      setActiveWatchPopupUid(null);
    }
    showWatchToast(`Alert UID ${uid} Dismissed`);
  };

  const activeIncomingCall = watchAlerts.find(
    (a) => a.isIncomingCall && !a.callResolution
  );

  const activePopupNotification =
    activeIncomingCall ||
    watchAlerts.find((a) => a.notificationUid === activeWatchPopupUid) ||
    null;

  return (
    <div className="min-h-screen bg-[#070A10] text-[#F8FAFC] flex flex-col selection:bg-sky-500/30 selection:text-sky-200">
      {/* Strict 3-Zone Top Bar Contract */}
      <header className="sticky top-0 z-40 bg-[#0B0F17]/90 backdrop-blur-md border-b border-slate-800/80">
        <div className="max-w-[1440px] mx-auto px-6 py-4 flex items-center justify-between gap-8">
          <a
            href="#top"
            className="text-lg font-bold text-slate-100 font-display tracking-tight whitespace-nowrap shrink-0"
          >
            OP2R-Bridge
          </a>

          <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-slate-300">
            <a
              href="#get-apk"
              className="hover:text-white transition-colors whitespace-nowrap shrink-0"
            >
              Get APK
            </a>
            <a
              href="#watch-simulator"
              className="hover:text-white transition-colors whitespace-nowrap shrink-0"
            >
              Watch Simulator
            </a>
            <a
              href="#source-workbench"
              className="hover:text-white transition-colors whitespace-nowrap shrink-0"
            >
              Source Files
            </a>
            <a
              href="#protocol-inspector"
              className="hover:text-white transition-colors whitespace-nowrap shrink-0"
            >
              BLE Packet Log
            </a>
          </nav>

          <div className="flex items-center gap-3 shrink-0">
            <motion.button
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.97 }}
              onClick={handleDownloadCompleteZip}
              disabled={isExportingZip}
              className="inline-flex items-center gap-2.5 px-4 py-2 text-xs font-semibold text-sky-950 bg-gradient-to-r from-sky-400 to-cyan-300 hover:from-sky-300 hover:to-cyan-200 rounded-full shadow-lg shadow-sky-500/20 transition-all cursor-pointer disabled:opacity-50 whitespace-nowrap shrink-0"
            >
              <FolderArchive className="w-4 h-4 text-sky-950" />
              <span>{isExportingZip ? 'Packaging...' : 'Download Project (.zip)'}</span>
            </motion.button>
          </div>
        </div>
      </header>

      {/* Main Container */}
      <main id="top" className="max-w-[1440px] w-full mx-auto px-6 py-8 space-y-10 flex-1">
        {/* SECTION 0: DIRECT APK DOWNLOAD & PRE-LINKED GITHUB ACTIONS FETCHER */}
        <section
          id="get-apk"
          className="rounded-3xl border border-emerald-500/30 bg-gradient-to-b from-[#112224] via-[#101929] to-[#0F1523] p-6 sm:p-8 shadow-2xl space-y-6"
        >
          <div className="flex flex-col lg:flex-row lg:items-start justify-between gap-6">
            <div className="space-y-3 max-w-3xl">
              <div className="flex flex-wrap items-center gap-2 text-xs font-mono text-emerald-400">
                <CheckCircle2 className="w-4 h-4" />
                <span>v2 ANCS Delivery Fix Ready · Auto Re-Sync (+3.5s/+8.5s) + Queue Lock + Live NS/DS Telemetry</span>
              </div>
              <h1 className="text-2xl sm:text-3xl font-bold text-white font-display">
                Sync &amp; Download Updated <code className="text-sky-300 font-mono">OP2R-iOS-Bridge-WearOS5.apk</code> (v2)
              </h1>
              <p className="text-sm text-slate-300 leading-relaxed">
                We fixed why <code className="text-emerald-300 font-mono">&ldquo;Bonded - Listening to ANCS&rdquo;</code> wasn&rsquo;t showing incoming calls or messages:
                the watch now runs <strong>automatic Post-Bond CCCD Re-Subscription waves (+3.5s &amp; +8.5s)</strong> and <code className="text-sky-300 font-mono">onServiceChanged</code> so iOS&rsquo;s <em>Share System Notifications</em> stream activates after pairing, locks the <code className="text-sky-300 font-mono">Control Point</code> queue until <code className="text-sky-300 font-mono">Data Source</code> finishes, rings <strong>immediately on 8-byte call packets</strong>, and adds a live <code className="text-emerald-300 font-mono">NS / DS</code> packet counter + <strong>Test + Sync</strong> button on your watch!
              </p>

              {/* Permanent 1-Click Direct Links for akashsanjeev/Wear-os-app */}
              <div className="flex flex-wrap items-center gap-3 pt-1">
                <a
                  href={`https://github.com/${githubInput.trim() || DEFAULT_GITHUB_REPO}/releases/download/latest-apk/OP2R-iOS-Bridge-WearOS5.apk`}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-2 px-4 py-2.5 text-xs font-bold text-slate-950 bg-emerald-400 hover:bg-emerald-300 rounded-xl shadow-lg shadow-emerald-500/20 transition-all whitespace-nowrap"
                >
                  <Download className="w-4 h-4" />
                  <span>Direct Download Latest OP2R-iOS-Bridge-WearOS5.apk (GitHub Release)</span>
                </a>

                <a
                  href={`https://github.com/${githubInput.trim() || DEFAULT_GITHUB_REPO}/actions`}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-2 px-4 py-2.5 text-xs font-semibold text-sky-200 bg-sky-500/20 hover:bg-sky-500/30 border border-sky-400/40 rounded-xl transition-all whitespace-nowrap"
                >
                  <span>Open GitHub Actions Runs</span>
                  <ExternalLink className="w-3.5 h-3.5" />
                </a>
              </div>
            </div>

            {/* Live GitHub Repo APK Status Box */}
            <div className="w-full lg:w-[460px] p-4 rounded-2xl bg-[#0B0F17]/90 border border-slate-800 space-y-3 shrink-0">
              <div className="flex items-center justify-between gap-2">
                <label className="block text-xs font-semibold text-slate-200">
                  Live GitHub Build &amp; Release Monitor:
                </label>
                <span className="text-[11px] font-mono text-emerald-400">Auto-Linked</span>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={githubInput}
                  onChange={(e) => setGithubInput(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleCheckGithubApk()}
                  placeholder="akashsanjeev/Wear-os-app"
                  className="flex-1 px-3 py-2 text-xs font-mono bg-[#141C2E] border border-slate-700 rounded-xl text-white placeholder-slate-500 focus:outline-none focus:border-sky-400"
                />
                <motion.button
                  whileTap={{ scale: 0.96 }}
                  onClick={() => handleCheckGithubApk()}
                  disabled={isCheckingGithub}
                  className="inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-semibold text-sky-950 bg-sky-400 hover:bg-sky-300 rounded-xl transition-colors cursor-pointer whitespace-nowrap shrink-0"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${isCheckingGithub ? 'animate-spin' : ''}`} />
                  <span>Refresh Status</span>
                </motion.button>
              </div>

              {githubStatus && (
                <div className="p-3.5 rounded-xl bg-[#141C2E] border border-slate-700/80 text-xs space-y-2.5">
                  {githubStatus.error ? (
                    <div className="space-y-2">
                      <p className="text-amber-300">{githubStatus.error}</p>
                    </div>
                  ) : (
                    <div className="space-y-2.5">
                      <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] font-mono text-slate-300">
                        <span>
                          Repo: <strong className="text-white">{githubStatus.repoSlug}</strong>
                          {githubStatus.headSha ? ` (${githubStatus.headSha})` : ''}
                        </span>
                        <span>
                          Latest Run:{' '}
                          <strong
                            className={
                              githubStatus.runConclusion === 'success'
                                ? 'text-emerald-400'
                                : githubStatus.runConclusion === 'failure'
                                ? 'text-amber-300'
                                : 'text-sky-300'
                            }
                          >
                            {githubStatus.runStatus === 'completed'
                              ? githubStatus.runConclusion
                              : githubStatus.runStatus}
                          </strong>
                        </span>
                      </div>

                      {(githubStatus.headSha === '297b521' || githubStatus.headSha === 'e5cbf21') && (
                        <div className="p-2.5 rounded-lg bg-amber-500/15 border border-amber-400/30 text-[11px] text-amber-200 leading-relaxed">
                          GitHub is currently on commit <code className="font-mono font-bold">{githubStatus.headSha}</code> (v1 build). <strong>Sync/push this new v2 fix to GitHub now</strong>, wait ~60s, and click <strong>Refresh Status</strong> to download the v2 APK!
                        </div>
                      )}

                      <div className="flex flex-wrap items-center gap-2">
                        {githubStatus.directApkUrl && (
                          <a
                            href={githubStatus.directApkUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold transition-colors"
                          >
                            <Download className="w-3.5 h-3.5" />
                            <span>Download Released .APK Now</span>
                          </a>
                        )}

                        {githubStatus.runHtmlUrl && (
                          <a
                            href={githubStatus.runHtmlUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-sky-500/20 hover:bg-sky-500/30 border border-sky-400/40 text-sky-200 font-semibold transition-colors"
                          >
                            <span>View Latest Workflow Run</span>
                            <ExternalLink className="w-3.5 h-3.5" />
                          </a>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* 3-Step Visual Guide to Download the Built APK from GitHub */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-2">
            <div className="p-4 rounded-2xl bg-[#0B0F17]/80 border border-slate-800 space-y-1.5">
              <div className="text-xs font-mono font-bold text-sky-400">
                Step 1 · Sync This Fix to GitHub
              </div>
              <p className="text-xs text-slate-300 leading-relaxed">
                Push/sync this commit from AI Studio to <code className="text-sky-300 font-mono">akashsanjeev/Wear-os-app</code>. This replaces the broken <code className="text-amber-300 font-mono">setup-android@v3</code> action with native <code className="text-sky-300 font-mono">$ANDROID_HOME</code> SDK tools.
              </p>
            </div>

            <div className="p-4 rounded-2xl bg-[#0B0F17]/80 border border-slate-800 space-y-1.5">
              <div className="text-xs font-mono font-bold text-emerald-400">
                Step 2 · Wait ~60s &amp; Click Refresh Status
              </div>
              <p className="text-xs text-slate-300 leading-relaxed">
                GitHub Actions will compile <code className="text-emerald-300 font-mono">OP2R-iOS-Bridge-WearOS5.apk</code> with JDK 17 + Gradle 8.7 and publish it directly to your repository&rsquo;s <strong>Releases</strong> (<code className="text-sky-300 font-mono">latest-apk</code>) and <strong>Artifacts</strong>.
              </p>
            </div>

            <div className="p-4 rounded-2xl bg-[#0B0F17]/80 border border-slate-800 space-y-1.5">
              <div className="text-xs font-mono font-bold text-cyan-300">
                Step 3 · Click Direct Download .APK
              </div>
              <p className="text-xs text-slate-300 leading-relaxed">
                Click the green <strong>Direct Download OP2R-iOS-Bridge-WearOS5.apk</strong> button above (or grab <code className="text-sky-300 font-mono">op2r-ios-ancs-bridge-debug-apk</code> at the bottom of the workflow run page).
              </p>
            </div>
          </div>
        </section>

        {/* SECTION 1: INTERACTIVE WEAR OS 5.0 ROUND WATCH SIMULATOR */}
        <section id="watch-simulator" className="space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-3">
            <div>
              <div className="flex items-center gap-2 text-xs font-mono text-sky-400">
                <Activity className="w-3.5 h-3.5" />
                <span>OnePlus Watch 2R (OPWWE234) 1:1 Circular Chord Geometry</span>
              </div>
              <h2 className="text-xl font-bold text-white font-display mt-0.5">
                466×466 Round Watchface &amp; Inscribed Full-Screen Message Popup
              </h2>
            </div>
            <div className="flex items-center gap-2 text-xs text-slate-400 font-mono">
              <span>Viewport: 233×233 dp (466×466 px)</span>
              <span aria-hidden="true">·</span>
              <span>
                Active View:{' '}
                <strong className="text-emerald-400">
                  {activePopupNotification ? 'Full-Screen Message Card' : 'Single-Screen Control Deck'}
                </strong>
              </span>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Left Column: Round Watch Hardware Bezel */}
            <div className="lg:col-span-5 flex flex-col items-center">
              {/* Hardware Casing with OnePlus Watch 2R Crown & Pusher */}
              <div className="relative p-4 rounded-[56px] bg-gradient-to-b from-[#1E293B] to-[#0F172A] border border-slate-700/60 shadow-2xl">
                {/* Simulated OnePlus Watch 2R Upper-Right Rotary Crown & Lower-Right Action Pusher */}
                <div className="absolute -right-1.5 top-24 w-2.5 h-10 bg-slate-500 rounded-r-md border-l border-slate-700 shadow" />
                <div className="absolute -right-1 top-56 w-2 h-8 bg-slate-600 rounded-r-md shadow" />

                {/* 466x466 Scaled Circular AMOLED Viewport (360px x 360px container with exact percentage chord widths) */}
                <div className="relative w-[340px] h-[340px] sm:w-[372px] sm:h-[372px] rounded-full bg-[#05070B] border-[12px] border-[#161E2E] shadow-inner flex flex-col items-center justify-center overflow-hidden">
                  {/* Subtle Inner Circular Safe-Ring Guide */}
                  <div className="absolute inset-2 rounded-full border border-slate-800/40 pointer-events-none z-10" />

                  {/* Subtle Watch Glass Reflection */}
                  <div className="absolute top-0 inset-x-0 h-24 bg-gradient-to-b from-white/[0.04] to-transparent rounded-t-full pointer-events-none z-30" />

                  {/* Watch Toast Pill (Tapered to 52% width at top arc so it never clips) */}
                  <AnimatePresence>
                    {watchToastMessage && (
                      <motion.div
                        initial={{ opacity: 0, y: -12, scale: 0.9 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        exit={{ opacity: 0, y: -8, scale: 0.9 }}
                        transition={{ type: 'spring', stiffness: 500, damping: 30 }}
                        className="absolute top-4 z-40 w-[52%] px-2.5 py-1 rounded-full bg-[#1B253D]/95 border border-sky-400/50 text-sky-200 text-[10px] font-mono shadow-lg text-center truncate"
                      >
                        {watchToastMessage}
                      </motion.div>
                    )}
                  </AnimatePresence>

                  {/* LAYER 2: CIRCULAR-INSCRIBED FULL-SCREEN NOTIFICATION MESSAGE OR CALL POPUP */}
                  <AnimatePresence>
                    {activePopupNotification ? (
                      activePopupNotification.isIncomingCall ? (
                        /* INCOMING CALL CIRCULAR-INSCRIBED OVERLAY */
                        <motion.div
                          key={`call-${activePopupNotification.notificationUid}`}
                          initial={{ opacity: 0, scale: 0.92 }}
                          animate={{ opacity: 1, scale: 1 }}
                          exit={{ opacity: 0, scale: 0.95 }}
                          transition={{ type: 'spring', stiffness: 420, damping: 28 }}
                          className="absolute inset-0 z-20 bg-gradient-to-b from-[#1A0B10] via-[#0B0910] to-[#05070B] flex flex-col items-center justify-center text-center"
                        >
                          {/* 1. Top Arc Call Badge (48% width) */}
                          <div className="w-[48%] px-2.5 py-0.5 rounded-full bg-red-500/15 border border-red-500/40 text-[9.5px] font-mono text-red-300 truncate mb-2">
                            IPHONE CALL
                          </div>

                          {/* 2. Pulsing Call Icon */}
                          <div className="relative my-1.5 flex items-center justify-center">
                            <div className="absolute w-14 h-14 rounded-full border border-red-500/30 animate-m3-sonar" />
                            <div className="w-11 h-11 rounded-full bg-red-500/20 border border-red-500/50 flex items-center justify-center text-red-400">
                              <PhoneIncoming className="w-5 h-5 animate-bounce" />
                            </div>
                          </div>

                          {/* 3. Equator Caller Info (76% width) */}
                          <div className="w-[76%] my-1.5 space-y-1">
                            <div className="text-sm font-bold text-white font-display truncate">
                              {activePopupNotification.title}
                            </div>
                            <div className="text-[11px] text-slate-300 truncate">
                              {activePopupNotification.message}
                            </div>
                          </div>

                          {/* 4. Tapered Lower Arc Call Action Row (60% width so corners never touch bezel) */}
                          <div className="w-[60%] grid grid-cols-2 gap-2 mt-2">
                            <motion.button
                              whileHover={{ scale: 1.04 }}
                              whileTap={{ scale: 0.94 }}
                              onClick={() =>
                                handlePerformCallAction(
                                  activePopupNotification.notificationUid,
                                  0x01
                                )
                              }
                              className="h-8 rounded-full bg-red-600 hover:bg-red-500 border border-red-400 text-white text-[10.5px] font-semibold flex items-center justify-center gap-1 shadow cursor-pointer"
                            >
                              <PhoneOff className="w-3 h-3" />
                              <span>Decline</span>
                            </motion.button>

                            <motion.button
                              whileHover={{ scale: 1.04 }}
                              whileTap={{ scale: 0.94 }}
                              onClick={() =>
                                handlePerformCallAction(
                                  activePopupNotification.notificationUid,
                                  0x00
                                )
                              }
                              className="h-8 rounded-full bg-emerald-600 hover:bg-emerald-500 border border-emerald-400 text-white text-[10.5px] font-semibold flex items-center justify-center gap-1 shadow cursor-pointer"
                            >
                              <PhoneIncoming className="w-3 h-3" />
                              <span>Accept</span>
                            </motion.button>
                          </div>
                        </motion.div>
                      ) : (
                        /* STANDARD MESSAGE CIRCULAR-INSCRIBED POPUP CARD (50% -> 72% -> 80% -> 50%) */
                        <motion.div
                          key={`msg-${activePopupNotification.notificationUid}`}
                          initial={{ opacity: 0, scale: 0.93 }}
                          animate={{ opacity: 1, scale: 1 }}
                          exit={{ opacity: 0, scale: 0.95 }}
                          transition={{ type: 'spring', stiffness: 420, damping: 28 }}
                          className="absolute inset-0 z-20 bg-[#060911] flex flex-col items-center justify-center text-center"
                        >
                          {/* 1. Tapered Top App Badge (50% of watch width) */}
                          <div className="w-[50%] px-2.5 py-0.5 rounded-full bg-[#14263B] border border-sky-400/60 text-sky-300 text-[10px] font-mono flex items-center justify-center gap-1 truncate mb-1.5">
                            <MessageSquareText className="w-3 h-3 text-sky-400 shrink-0" />
                            <span className="truncate">{activePopupNotification.appDisplayName}</span>
                          </div>

                          {/* 2. Sender / Title (72% of watch width) */}
                          <div className="w-[72%] text-[13.5px] font-bold text-white font-display truncate mb-2">
                            {activePopupNotification.title}
                          </div>

                          {/* 3. Inscribed Equator Scrollable Message Box (80% of watch width, 118px height) */}
                          <div className="w-[80%] h-[118px] p-3 rounded-2xl bg-[#111827] border border-slate-800 text-[12px] text-slate-100 leading-relaxed overflow-y-auto flex items-center justify-center text-center shadow-inner mb-2.5">
                            <div className="max-h-full overflow-y-auto">
                              {activePopupNotification.message}
                            </div>
                          </div>

                          {/* 4. Tapered Bottom Dismiss Pill Button (50% of watch width — sits cleanly in lower arc) */}
                          <motion.button
                            whileHover={{ scale: 1.03 }}
                            whileTap={{ scale: 0.95 }}
                            onClick={() => setActiveWatchPopupUid(null)}
                            className="w-[50%] h-8 rounded-full bg-[#1E293B] hover:bg-[#273549] border border-sky-400/70 text-[11px] font-semibold text-white flex items-center justify-center gap-1 shadow cursor-pointer"
                          >
                            <X className="w-3 h-3 text-sky-400 shrink-0" />
                            <span>Dismiss</span>
                          </motion.button>
                        </motion.div>
                      )
                    ) : null}
                  </AnimatePresence>

                  {/* LAYER 1: SINGLE-SCREEN CIRCULAR-SAFE CONTROL DECK (54% -> 68% -> 82% -> 76% -> 64%) */}
                  <div className="w-full h-full flex flex-col items-center justify-center text-center space-y-2">
                    {/* Row 1: Top Arc Header + Live NS/DS Packet Counter (54% width) */}
                    <div className="w-[54%] text-[11.5px] font-bold text-slate-100 font-display truncate">
                      OP2R · NS:{watchAlerts.length} DS:{watchAlerts.length}
                    </div>

                    {/* Row 2: Upper Arc Interactive Status + Tap-to-ReSync Pill (68% width) */}
                    <button
                      onClick={() => {
                        setWatchState('Bonded - Listening to ANCS');
                        showWatchToast('Re-Synced ANCS CCCDs');
                      }}
                      title="Tap to Re-Sync ANCS Data Source & Notification Source CCCDs"
                      className={`w-[68%] text-[9.5px] font-mono px-2.5 py-1 rounded-full border truncate transition-all cursor-pointer ${
                        watchState.includes('Bonded')
                          ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/35 hover:bg-emerald-500/20'
                          : watchState.includes('Advertising') || watchState.includes('Scanning')
                          ? 'bg-amber-500/10 text-amber-300 border-amber-500/35'
                          : watchState.includes('133')
                          ? 'bg-red-500/10 text-red-400 border-red-500/35'
                          : 'bg-sky-500/10 text-sky-400 border-sky-500/35'
                      }`}
                    >
                      {watchState.includes('Bonded') ? `${watchState} (Tap Re-Sync)` : watchState}
                    </button>

                    {/* Row 3: Equator 2-Column Primary Mode Pills (82% width — widest part of watchface) */}
                    <div className="w-[82%] grid grid-cols-2 gap-2">
                      <motion.button
                        whileHover={{ scale: 1.02 }}
                        whileTap={{ scale: 0.95 }}
                        onClick={handleStartPeripheralMode}
                        className="h-[52px] rounded-full bg-[#1B253D] hover:bg-[#232F4C] border border-sky-400/70 px-2.5 flex items-center justify-center gap-1.5 transition-colors cursor-pointer shadow"
                      >
                        <Bluetooth className="w-3.5 h-3.5 text-sky-300 shrink-0" />
                        <div className="text-left min-w-0 leading-tight">
                          <div className="text-[10.5px] font-semibold text-white truncate">
                            Advertise
                          </div>
                          <div className="text-[9px] text-slate-300 truncate">
                            iPhone (A)
                          </div>
                        </div>
                      </motion.button>

                      <motion.button
                        whileHover={{ scale: 1.02 }}
                        whileTap={{ scale: 0.95 }}
                        onClick={handleStartScannerMode}
                        className="h-[52px] rounded-full bg-[#1B253D] hover:bg-[#232F4C] border border-emerald-400/70 px-2.5 flex items-center justify-center gap-1.5 transition-colors cursor-pointer shadow"
                      >
                        <Radio className="w-3.5 h-3.5 text-emerald-300 shrink-0" />
                        <div className="text-left min-w-0 leading-tight">
                          <div className="text-[10.5px] font-semibold text-white truncate">
                            Scan
                          </div>
                          <div className="text-[9px] text-slate-300 truncate">
                            Beacon (B)
                          </div>
                        </div>
                      </motion.button>
                    </div>

                    {/* Row 4: Center Smart Pre-Existing Filter Toggle Pill (76% width) */}
                    <motion.button
                      whileHover={{ scale: 1.02 }}
                      whileTap={{ scale: 0.96 }}
                      onClick={() => setIgnorePreExisting((prev) => !prev)}
                      className="w-[76%] h-9 rounded-full bg-[#141C2E] hover:bg-[#1B253D] border border-slate-600/80 px-3.5 flex items-center justify-between transition-colors cursor-pointer"
                    >
                      <span className="text-[10px] font-medium text-slate-200 truncate">
                        Pre-Existing: {ignorePreExisting ? 'Smart (2.5s)' : 'Show All'}
                      </span>
                      <div
                        className={`w-7 h-4 rounded-full p-0.5 transition-colors flex items-center shrink-0 ${
                          ignorePreExisting ? 'bg-sky-500 justify-end' : 'bg-slate-700 justify-start'
                        }`}
                      >
                        <div className="w-3 h-3 rounded-full bg-white" />
                      </div>
                    </motion.button>

                    {/* Row 5: Tapered Lower Arc 2-Column Utility Row (64% width — fits cleanly inside bottom arc) */}
                    <div className="w-[64%] grid grid-cols-2 gap-2">
                      <motion.button
                        whileHover={{ scale: 1.02 }}
                        whileTap={{ scale: 0.95 }}
                        onClick={() =>
                          fireAncsNotificationSequence({
                            eventId: 0,
                            eventFlags: 0x02,
                            categoryId: 4,
                            categoryCount: 1,
                            appBundleId: 'net.whatsapp.WhatsApp',
                            title: 'OP2R Hardware OK',
                            message:
                              'Screen wake, vibration, and full-screen popup are working on your OnePlus Watch 2R! Re-syncing iPhone ANCS link now...',
                          })
                        }
                        className="h-8 rounded-full bg-[#132A24] hover:bg-[#193830] border border-emerald-400/70 px-2 flex items-center justify-center gap-1 transition-colors cursor-pointer"
                      >
                        <ShieldCheck className="w-3 h-3 text-emerald-400 shrink-0" />
                        <span className="text-[9.5px] font-medium text-emerald-100 truncate">
                          Test + Sync
                        </span>
                      </motion.button>

                      <motion.button
                        whileHover={{ scale: 1.02 }}
                        whileTap={{ scale: 0.95 }}
                        onClick={handleResetBle}
                        className="h-8 rounded-full bg-[#2A151C] hover:bg-red-950 border border-red-400/70 px-2 flex items-center justify-center gap-1 transition-colors cursor-pointer"
                      >
                        <Trash2 className="w-3 h-3 text-red-400 shrink-0" />
                        <span className="text-[9.5px] font-medium text-red-100 truncate">
                          Reset BLE
                        </span>
                      </motion.button>
                    </div>
                  </div>
                </div>
              </div>

              {/* Hardware Diagnostic Controls below the watch */}
              <div className="mt-4 flex flex-wrap items-center justify-center gap-2.5">
                {watchAlerts.length > 0 && (
                  <motion.button
                    whileHover={{ scale: 1.03 }}
                    whileTap={{ scale: 0.97 }}
                    onClick={() =>
                      setActiveWatchPopupUid(
                        activePopupNotification ? null : watchAlerts[0].notificationUid
                      )
                    }
                    className="inline-flex items-center gap-2 px-3.5 py-1.5 text-xs font-mono text-sky-300 bg-[#141C2E] hover:bg-[#1B253D] border border-sky-500/30 rounded-full shadow-sm cursor-pointer whitespace-nowrap"
                  >
                    <Eye className="w-3.5 h-3.5 text-sky-400" />
                    <span>
                      {activePopupNotification
                        ? 'Switch to Watch Control Deck'
                        : 'Preview Message Popup on Watch'}
                    </span>
                  </motion.button>
                )}

                <motion.button
                  whileHover={{ scale: 1.03 }}
                  whileTap={{ scale: 0.97 }}
                  onClick={handleSimulateError133}
                  className="inline-flex items-center gap-2 px-3.5 py-1.5 text-xs font-mono text-amber-300 bg-[#1E293B] hover:bg-[#273549] border border-amber-500/30 rounded-full shadow-sm cursor-pointer whitespace-nowrap"
                >
                  <ShieldAlert className="w-3.5 h-3.5 text-amber-400" />
                  <span>Test Status 133 Recovery</span>
                </motion.button>
              </div>
            </div>

            {/* Right Column: iPhone 14 ANCS Event Injector & Active Notifications */}
            <div className="lg:col-span-7 space-y-6">
              {/* Event Injector Card */}
              <div className="p-6 rounded-3xl bg-[#141C2E] border border-slate-800/90 shadow-xl space-y-5">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                  <div>
                    <h3 className="text-base font-bold text-white font-display">
                      iPhone 14 ANCS Event Injector (Wakes Watch &amp; Shows Message Card)
                    </h3>
                    <p className="text-xs text-slate-400">
                      Click any preset or compose a custom message below to wake the 466×466 watchface and display the circular-inscribed message popup.
                    </p>
                  </div>

                  {/* MTU Segmented Control */}
                  <div className="flex items-center p-1 rounded-full bg-[#0B0F17] border border-slate-800 shrink-0">
                    {[
                      { mtu: 23, label: '23B (Legacy)' },
                      { mtu: 64, label: '64B' },
                      { mtu: 247, label: '247B (Negotiated)' },
                    ].map((opt) => (
                      <button
                        key={opt.mtu}
                        onClick={() => setSimulatedMtu(opt.mtu)}
                        className={`px-3 py-1 text-xs font-mono rounded-full transition-all cursor-pointer whitespace-nowrap ${
                          simulatedMtu === opt.mtu
                            ? 'bg-sky-500 text-sky-950 font-bold shadow'
                            : 'text-slate-400 hover:text-white'
                        }`}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Preset Scenarios */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {ANCS_SIMULATOR_PRESETS.map((preset: SimulatedAncsPreset) => (
                    <motion.button
                      key={preset.id}
                      whileHover={{ scale: 1.015, y: -1 }}
                      whileTap={{ scale: 0.98 }}
                      onClick={() => fireAncsNotificationSequence(preset)}
                      className="p-3.5 rounded-2xl bg-[#0F1523] hover:bg-[#1B253D] border border-slate-800 hover:border-sky-500/30 transition-all text-left flex items-start justify-between gap-3 cursor-pointer group"
                    >
                      <div className="min-w-0">
                        <div className="text-xs font-semibold text-white group-hover:text-sky-300 transition-colors">
                          {preset.label}
                        </div>
                        <div className="text-[11px] font-mono text-slate-400 mt-1 truncate">
                          {preset.title}: &ldquo;{preset.message}&rdquo;
                        </div>
                      </div>
                      <div className="w-7 h-7 rounded-full bg-slate-800 flex items-center justify-center text-slate-300 group-hover:text-sky-300 group-hover:bg-sky-500/20 transition-all shrink-0">
                        <Send className="w-3.5 h-3.5" />
                      </div>
                    </motion.button>
                  ))}
                </div>

                {/* Custom Payload Form */}
                <div className="pt-4 border-t border-slate-800/80 space-y-3">
                  <div className="text-xs font-semibold text-slate-300 flex items-center gap-2">
                    <Sliders className="w-3.5 h-3.5 text-sky-400" />
                    <span>Custom iOS Notification Message Composer</span>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                    <div>
                      <label className="block text-[11px] text-slate-400 mb-1">
                        Bundle ID (0x00)
                      </label>
                      <select
                        value={customBundleId}
                        onChange={(e) => setCustomBundleId(e.target.value)}
                        className="w-full px-3 py-2 text-xs font-mono bg-[#0B0F17] border border-slate-800 rounded-xl text-slate-100 focus:outline-none focus:border-sky-500"
                      >
                        <option value="net.whatsapp.WhatsApp">net.whatsapp.WhatsApp</option>
                        <option value="com.apple.MobileSMS">com.apple.MobileSMS</option>
                        <option value="com.apple.mobilephone">com.apple.mobilephone</option>
                        <option value="ph.telegra.Telegraph">ph.telegra.Telegraph</option>
                        <option value="com.Slack">com.Slack</option>
                        <option value="com.google.Gmail">com.google.Gmail</option>
                      </select>
                    </div>

                    <div>
                      <label className="block text-[11px] text-slate-400 mb-1">
                        Category ID
                      </label>
                      <select
                        value={customCategoryId}
                        onChange={(e) => setCustomCategoryId(Number(e.target.value))}
                        className="w-full px-3 py-2 text-xs font-mono bg-[#0B0F17] border border-slate-800 rounded-xl text-slate-100 focus:outline-none focus:border-sky-500"
                      >
                        <option value={4}>4 — Social / Message (Wakes Screen)</option>
                        <option value={1}>1 — Incoming Call (Wakes Screen)</option>
                        <option value={2}>2 — Missed Call</option>
                        <option value={6}>6 — Email</option>
                      </select>
                    </div>

                    <div>
                      <label className="block text-[11px] text-slate-400 mb-1">
                        Sender / Title (0x01, Max 64B)
                      </label>
                      <input
                        type="text"
                        value={customTitle}
                        onChange={(e) => setCustomTitle(e.target.value)}
                        className="w-full px-3 py-2 text-xs bg-[#0B0F17] border border-slate-800 rounded-xl text-slate-100 focus:outline-none focus:border-sky-500"
                      />
                    </div>
                  </div>

                  <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
                    <input
                      type="text"
                      value={customMessage}
                      onChange={(e) => setCustomMessage(e.target.value)}
                      placeholder="Full UTF-8 Message Body to display on Watch (0x03, Max 256B)"
                      className="flex-1 px-3.5 py-2 text-xs bg-[#0B0F17] border border-slate-800 rounded-xl text-slate-100 focus:outline-none focus:border-sky-500"
                    />

                    <label className="inline-flex items-center gap-2 text-xs text-slate-300 whitespace-nowrap cursor-pointer">
                      <input
                        type="checkbox"
                        checked={customPreExisting}
                        onChange={(e) => setCustomPreExisting(e.target.checked)}
                        className="rounded border-slate-700 text-sky-500 focus:ring-0"
                      />
                      <span>PreExisting (1&lt;&lt;2)</span>
                    </label>

                    <motion.button
                      whileHover={{ scale: 1.02 }}
                      whileTap={{ scale: 0.97 }}
                      onClick={() =>
                        fireAncsNotificationSequence({
                          eventId: 0,
                          eventFlags:
                            (customPreExisting ? 1 << 2 : 0) |
                            (customCategoryId === 1 ? (1 << 3) | (1 << 4) : 1 << 3),
                          categoryId: customCategoryId,
                          categoryCount: 1,
                          appBundleId: customBundleId,
                          title: customTitle || 'iOS Alert',
                          message: customMessage || 'Notification via BLE ANCS',
                        })
                      }
                      className="inline-flex items-center justify-center gap-2 px-5 py-2 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-500 rounded-xl transition-all cursor-pointer shrink-0 whitespace-nowrap"
                    >
                      <Send className="w-3.5 h-3.5" />
                      <span>Wake &amp; Show Message</span>
                    </motion.button>
                  </div>
                </div>
              </div>

              {/* Watch Stream Output */}
              <div className="p-6 rounded-3xl bg-[#141C2E] border border-slate-800/90 shadow-xl space-y-4">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="text-base font-bold text-white font-display">
                      Reassembled Watch Notification History ({watchAlerts.length})
                    </h3>
                    <p className="text-xs text-slate-400">
                      Decoded by <code className="text-sky-300 font-mono">AncsPacketParser.kt</code> · Click{' '}
                      <strong>Show on Watch</strong> to inspect any message inside the 466×466 round popup card.
                    </p>
                  </div>
                </div>

                {watchAlerts.length === 0 ? (
                  <div className="py-8 text-center text-xs text-slate-400 border border-dashed border-slate-800 rounded-2xl">
                    No active notifications on the watch. Fire a preset event above.
                  </div>
                ) : (
                  <div className="space-y-3">
                    <AnimatePresence>
                      {watchAlerts.map((item) => {
                        const isCurrentlyShownOnWatch =
                          activePopupNotification?.notificationUid === item.notificationUid;
                        return (
                          <motion.div
                            key={item.notificationUid}
                            initial={{ opacity: 0, y: 10 }}
                            animate={{ opacity: 1, y: 0 }}
                            exit={{ opacity: 0, scale: 0.95 }}
                            className={`p-4 rounded-2xl border flex flex-col sm:flex-row sm:items-center justify-between gap-4 transition-colors ${
                              isCurrentlyShownOnWatch
                                ? 'bg-[#162238] border-sky-500/50'
                                : 'bg-[#0F1523] border-slate-800'
                            }`}
                          >
                            <div className="space-y-1 min-w-0">
                              <div className="flex flex-wrap items-center gap-2 text-xs font-mono text-slate-400">
                                <span className="font-bold text-sky-400">{item.appDisplayName}</span>
                                <span aria-hidden="true">·</span>
                                <span>UID {item.notificationUid}</span>
                                <span aria-hidden="true">·</span>
                                <span>{item.timestamp}</span>
                              </div>
                              <div className="text-sm font-semibold text-white truncate">
                                {item.title}
                              </div>
                              <p className="text-xs text-slate-200 leading-relaxed">
                                {item.message}
                              </p>
                              <div className="text-[11px] font-mono text-slate-400 pt-0.5">
                                Policy: <span className="text-emerald-400">{item.wakeBehavior}</span>
                                {item.callResolution && (
                                  <span className="ml-2 text-sky-400">
                                    · Action Sent: {item.callResolution}
                                  </span>
                                )}
                              </div>
                            </div>

                            <div className="flex flex-wrap items-center gap-2 shrink-0">
                              <motion.button
                                whileTap={{ scale: 0.95 }}
                                onClick={() =>
                                  setActiveWatchPopupUid(
                                    isCurrentlyShownOnWatch ? null : item.notificationUid
                                  )
                                }
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-sky-300 bg-sky-500/10 hover:bg-sky-500/20 border border-sky-500/30 rounded-full transition-colors cursor-pointer whitespace-nowrap"
                              >
                                <Eye className="w-3.5 h-3.5" />
                                <span>{isCurrentlyShownOnWatch ? 'Close Popup' : 'Show on Watch'}</span>
                              </motion.button>

                              {item.isIncomingCall && !item.callResolution && (
                                <>
                                  <button
                                    onClick={() => handlePerformCallAction(item.notificationUid, 0x00)}
                                    className="px-3 py-1.5 text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white rounded-full transition-colors cursor-pointer whitespace-nowrap"
                                  >
                                    Accept (0x00)
                                  </button>
                                  <button
                                    onClick={() => handlePerformCallAction(item.notificationUid, 0x01)}
                                    className="px-3 py-1.5 text-xs font-semibold bg-red-600 hover:bg-red-500 text-white rounded-full transition-colors cursor-pointer whitespace-nowrap"
                                  >
                                    Decline (0x01)
                                  </button>
                                </>
                              )}

                              <motion.button
                                whileHover={{ scale: 1.05 }}
                                whileTap={{ scale: 0.95 }}
                                onClick={() =>
                                  handleSimulateRemoteRemove(item.notificationUid, item.categoryId)
                                }
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono text-slate-300 bg-slate-800 hover:bg-slate-700 rounded-full transition-colors cursor-pointer whitespace-nowrap"
                                title="Simulate EventID = 2 (Removed on iPhone)"
                              >
                                <Trash2 className="w-3.5 h-3.5 text-slate-400" />
                                <span>EventID=2</span>
                              </motion.button>
                            </div>
                          </motion.div>
                        );
                      })}
                    </AnimatePresence>
                  </div>
                )}
              </div>
            </div>
          </div>
        </section>

        {/* SECTION 2: PRODUCTION SOURCE CODE WORKBENCH */}
        <section id="source-workbench" className="space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-3">
            <div>
              <div className="flex items-center gap-2 text-xs font-mono text-sky-400">
                <FileCode className="w-3.5 h-3.5" />
                <span>Wear OS 5.0 (API 34) · Production Kotlin Codebase</span>
              </div>
              <h2 className="text-xl font-bold text-white font-display mt-0.5">
                Android Studio &amp; GitHub Actions Project Browser
              </h2>
            </div>
            <div className="text-xs text-slate-400 font-mono tabular-nums">
              <span>{PROJECT_FILES.length} Files</span> ·{' '}
              <span>
                {PROJECT_FILES.reduce((acc, f) => acc + f.content.split('\n').length, 0)} Total Lines
              </span>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-12 rounded-3xl border border-slate-800/90 bg-[#141C2E] overflow-hidden shadow-2xl">
            {/* Left File Navigation */}
            <div className="lg:col-span-4 border-b lg:border-b-0 lg:border-r border-slate-800/80 flex flex-col bg-[#0F1523]">
              <div className="p-4 border-b border-slate-800/80">
                <div className="relative">
                  <Search className="w-3.5 h-3.5 text-slate-400 absolute left-3 top-3" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search deliverables..."
                    className="w-full pl-9 pr-3 py-2 text-xs bg-[#0B0F17] border border-slate-800 rounded-xl text-slate-100 placeholder-slate-500 focus:outline-none focus:border-sky-500"
                  />
                </div>
              </div>

              <div className="divide-y divide-slate-800/60 overflow-y-auto max-h-[640px]">
                {filteredFiles.map((file) => {
                  const isSelected = file.id === selectedFile.id;
                  const lineCount = file.content.split('\n').length;
                  return (
                    <button
                      key={file.id}
                      onClick={() => setSelectedFileId(file.id)}
                      className={`w-full text-left p-4 transition-all cursor-pointer flex items-start justify-between gap-3 ${
                        isSelected
                          ? 'bg-sky-500/15 text-white'
                          : 'hover:bg-slate-800/40 text-slate-300'
                      }`}
                    >
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <FileCode
                            className={`w-4 h-4 shrink-0 ${
                              isSelected ? 'text-sky-400' : 'text-slate-400'
                            }`}
                          />
                          <span className="text-xs font-mono font-semibold truncate">
                            {file.path}
                          </span>
                        </div>
                        <p className="text-[11px] text-slate-400 mt-1 line-clamp-1">
                          {file.summary}
                        </p>
                      </div>
                      <span className="text-[11px] font-mono text-slate-500 tabular-nums shrink-0">
                        {lineCount}L
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Right Code Display */}
            <div className="lg:col-span-8 flex flex-col min-w-0 bg-[#0B0F17]">
              <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5 border-b border-slate-800/80 bg-[#141C2E]/60">
                <div className="min-w-0">
                  <div className="text-xs font-mono font-semibold text-sky-300 truncate">
                    {selectedFile.path}
                  </div>
                  <div className="text-[11px] text-slate-400 mt-0.5 truncate">
                    {selectedFile.summary} · {selectedFile.content.split('\n').length} lines
                  </div>
                </div>

                <div className="flex items-center gap-2 shrink-0">
                  <motion.button
                    whileTap={{ scale: 0.95 }}
                    onClick={() => handleCopyCode(selectedFile)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-200 bg-[#1B253D] hover:bg-[#232F4C] border border-slate-700 rounded-full transition-colors cursor-pointer whitespace-nowrap"
                  >
                    {copiedFileId === selectedFile.id ? (
                      <>
                        <Check className="w-3.5 h-3.5 text-emerald-400" />
                        <span>Copied</span>
                      </>
                    ) : (
                      <>
                        <Copy className="w-3.5 h-3.5" />
                        <span>Copy File</span>
                      </>
                    )}
                  </motion.button>

                  <motion.button
                    whileTap={{ scale: 0.95 }}
                    onClick={() => handleDownloadSingleFile(selectedFile)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-200 bg-[#1B253D] hover:bg-[#232F4C] border border-slate-700 rounded-full transition-colors cursor-pointer whitespace-nowrap"
                  >
                    <Download className="w-3.5 h-3.5" />
                    <span>Download Raw</span>
                  </motion.button>
                </div>
              </div>

              <div className="overflow-x-auto overflow-y-auto max-h-[640px] p-5">
                <pre className="text-xs font-mono leading-relaxed text-slate-200 select-all">
                  {selectedFile.content.split('\n').map((line, idx) => (
                    <div key={idx} className="table-row hover:bg-slate-900/60 transition-colors">
                      <span className="table-cell pr-5 text-right text-slate-600 select-none tabular-nums w-12">
                        {idx + 1}
                      </span>
                      <span className="table-cell whitespace-pre">{line}</span>
                    </div>
                  ))}
                </pre>
              </div>
            </div>
          </div>
        </section>

        {/* SECTION 3: BLE GATT QUEUE & HEX PACKET INSPECTOR */}
        <section id="protocol-inspector" className="space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-3">
            <div>
              <div className="flex items-center gap-2 text-xs font-mono text-sky-400">
                <Layers className="w-3.5 h-3.5" />
                <span>Protocol Telemetry</span>
              </div>
              <h2 className="text-xl font-bold text-white font-display mt-0.5">
                GATT Operation Queue &amp; Hex Packet Inspector
              </h2>
            </div>
            <motion.button
              whileTap={{ scale: 0.95 }}
              onClick={() => setHexLogs([])}
              className="inline-flex items-center gap-2 px-3.5 py-1.5 text-xs font-medium text-slate-300 bg-[#141C2E] hover:bg-[#1B253D] border border-slate-800 rounded-full transition-colors cursor-pointer shrink-0 whitespace-nowrap"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>Clear Log</span>
            </motion.button>
          </div>

          <div className="rounded-3xl border border-slate-800/90 bg-[#141C2E] overflow-hidden shadow-xl">
            <div className="overflow-x-auto max-h-[400px]">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="border-b border-slate-800 bg-[#0B0F17] text-[11px] font-mono text-slate-400">
                    <th className="py-3 px-5 whitespace-nowrap">Time</th>
                    <th className="py-3 px-5 whitespace-nowrap">Direction</th>
                    <th className="py-3 px-5 whitespace-nowrap">GATT Characteristic</th>
                    <th className="py-3 px-5">Protocol Event &amp; Byte Sequence</th>
                    <th className="py-3 px-5 text-right whitespace-nowrap">Bytes</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60 text-xs font-mono tabular-nums">
                  {hexLogs.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="py-10 text-center text-slate-500 font-sans">
                        Packet log cleared. Trigger an ANCS event or watch button above to inspect traffic.
                      </td>
                    </tr>
                  ) : (
                    hexLogs.map((log) => (
                      <tr key={log.id} className="hover:bg-slate-800/40 transition-colors">
                        <td className="py-3 px-5 text-slate-400 whitespace-nowrap">
                          {log.timestamp}
                        </td>
                        <td className="py-3 px-5 whitespace-nowrap">
                          <span
                            className={
                              log.direction === 'iOS -> Watch'
                                ? 'text-emerald-400 font-semibold'
                                : log.direction === 'Watch -> iOS'
                                ? 'text-sky-400 font-semibold'
                                : 'text-amber-300 font-semibold'
                            }
                          >
                            {log.direction}
                          </span>
                        </td>
                        <td className="py-3 px-5 text-slate-300 whitespace-nowrap">
                          {log.characteristic}
                        </td>
                        <td className="py-3 px-5 space-y-1">
                          <div className="text-white font-sans text-xs">{log.summary}</div>
                          <div className="text-[11px] text-slate-400 break-all">{log.hexBytes}</div>
                        </td>
                        <td className="py-3 px-5 text-right text-slate-400 whitespace-nowrap">
                          {log.byteCount}B
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </section>

        {/* SECTION 4: GITHUB ACTIONS CI BUILD & WIRELESS ADB */}
        <section id="ci-pipeline" className="rounded-3xl border border-slate-800/90 bg-[#141C2E] p-6 sm:p-8 space-y-6 shadow-xl">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div>
              <div className="flex items-center gap-2 text-xs font-mono text-sky-400">
                <Sparkles className="w-3.5 h-3.5" />
                <span>Automated Wear OS 5.0 APK Compilation</span>
              </div>
              <h2 className="text-xl font-bold text-white font-display mt-0.5">
                GitHub Actions Pipeline &amp; Wireless ADB Sideloading
              </h2>
            </div>
            <motion.button
              whileTap={{ scale: 0.95 }}
              onClick={() => setSelectedFileId('github-workflow')}
              className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold text-slate-200 bg-[#1B253D] hover:bg-[#232F4C] border border-slate-700 rounded-full transition-colors cursor-pointer shrink-0 whitespace-nowrap"
            >
              <FileCode className="w-4 h-4 text-sky-400" />
              <span>Inspect .github/workflows/build-apk.yml</span>
            </motion.button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 pt-2">
            <div className="p-5 rounded-2xl bg-[#0F1523] border border-slate-800 space-y-2.5">
              <div className="text-xs font-mono font-bold text-sky-400">01. Push to GitHub</div>
              <h3 className="text-sm font-semibold text-white">Cloud Gradle 8.7 Build</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Push or sync to GitHub. The workflow in <code className="text-sky-300 font-mono">.github/workflows/build-apk.yml</code> automatically compiles{' '}
                <code className="text-sky-300 font-mono">OP2R-iOS-Bridge-WearOS5.apk</code> and publishes it to both <strong>Actions Artifacts</strong> and <strong>GitHub Releases</strong>.
              </p>
            </div>

            <div className="p-5 rounded-2xl bg-[#0F1523] border border-slate-800 space-y-2.5">
              <div className="text-xs font-mono font-bold text-emerald-400">02. Wireless ADB Install</div>
              <h3 className="text-sm font-semibold text-white">Sideload to OnePlus Watch 2R</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Enable Wireless Debugging in Wear OS 5.0 Developer Options and install the compiled APK:
              </p>
              <pre className="p-3 rounded-xl bg-[#0B0F17] border border-slate-800 text-[11px] font-mono text-slate-300 overflow-x-auto">
                adb pair &lt;IP:PAIR_PORT&gt;{'\n'}adb connect &lt;IP:PORT&gt;{'\n'}adb install -r OP2R-iOS-Bridge-WearOS5.apk
              </pre>
            </div>

            <div className="p-5 rounded-2xl bg-[#0F1523] border border-slate-800 space-y-2.5">
              <div className="text-xs font-mono font-bold text-cyan-300">03. One-Time iOS Bond</div>
              <h3 className="text-sm font-semibold text-white">Screen Wake &amp; Full Message</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                Tap <strong>Advertise iPhone (A)</strong> on the watch, connect to <code className="text-sky-300 font-mono">OP2R-Bridge</code> in iPhone Bluetooth Settings, and enable <em>Share System Notifications</em>.
              </p>
            </div>
          </div>
        </section>
      </main>

      {/* Material 3 Footer */}
      <footer className="border-t border-slate-800/80 py-6 px-6 text-center text-xs text-slate-500 bg-[#0B0F17]">
        OP2R-Bridge · Material Design 3 &amp; Wear OS 5.0 (Android 14 / API 34) Native iOS ANCS BLE Bridge for OnePlus Watch 2R (OPWWE234 · 466×466px)
      </footer>
    </div>
  );
}
