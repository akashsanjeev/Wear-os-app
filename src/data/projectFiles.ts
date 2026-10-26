import settingsGradleRaw from '../../settings.gradle.kts?raw';
import rootBuildGradleRaw from '../../build.gradle.kts?raw';
import gradlePropertiesRaw from '../../gradle.properties?raw';
import appBuildGradleRaw from '../../app/build.gradle.kts?raw';
import androidManifestRaw from '../../app/src/main/AndroidManifest.xml?raw';
import ancsPacketParserRaw from '../../app/src/main/java/com/op2r/iosbridge/AncsPacketParser.kt?raw';
import ancsBridgeServiceRaw from '../../app/src/main/java/com/op2r/iosbridge/AncsBridgeService.kt?raw';
import mainActivityRaw from '../../app/src/main/java/com/op2r/iosbridge/MainActivity.kt?raw';
import githubWorkflowRaw from '../../.github/workflows/build-apk.yml?raw';

export interface ProjectFileItem {
  id: string;
  path: string;
  title: string;
  language: 'kotlin' | 'xml' | 'yaml' | 'gradle' | 'properties';
  summary: string;
  content: string;
}

export const PROJECT_FILES: ProjectFileItem[] = [
  {
    id: 'settings-gradle',
    path: 'settings.gradle.kts',
    title: 'settings.gradle.kts',
    language: 'gradle',
    summary: 'Root Gradle repository settings and :app module inclusion',
    content: settingsGradleRaw,
  },
  {
    id: 'root-build-gradle',
    path: 'build.gradle.kts',
    title: 'build.gradle.kts (Root)',
    language: 'gradle',
    summary: 'Root Android Application 8.3.2 & Kotlin 1.9.23 plugin declarations',
    content: rootBuildGradleRaw,
  },
  {
    id: 'app-build-gradle',
    path: 'app/build.gradle.kts',
    title: 'app/build.gradle.kts',
    language: 'gradle',
    summary: 'Wear OS 5.0 (Android 14) module configuration (compileSdk 34, minSdk 30, targetSdk 34, JDK 17)',
    content: appBuildGradleRaw,
  },
  {
    id: 'android-manifest',
    path: 'app/src/main/AndroidManifest.xml',
    title: 'app/src/main/AndroidManifest.xml',
    language: 'xml',
    summary: 'Wear OS 5.0 manifest with showWhenLocked, turnScreenOn, BLE permissions & connectedDevice FGS',
    content: androidManifestRaw,
  },
  {
    id: 'ancs-packet-parser',
    path: 'app/src/main/java/com/op2r/iosbridge/AncsPacketParser.kt',
    title: 'AncsPacketParser.kt',
    language: 'kotlin',
    summary: '8-byte Notification Source parser, LE Control Point builder & Multi-Packet MTU Reassembler',
    content: ancsPacketParserRaw,
  },
  {
    id: 'ancs-bridge-service',
    path: 'app/src/main/java/com/op2r/iosbridge/AncsBridgeService.kt',
    title: 'AncsBridgeService.kt',
    language: 'kotlin',
    summary: 'Wear OS 5.0 BLE Bridge with Screen Wake on Message, BigTextStyle & Full-Screen Popup Broadcast',
    content: ancsBridgeServiceRaw,
  },
  {
    id: 'main-activity',
    path: 'app/src/main/java/com/op2r/iosbridge/MainActivity.kt',
    title: 'MainActivity.kt',
    language: 'kotlin',
    summary: 'Round Wear OS 5.0 UI with Full-Screen Notification Message Popup Card & Material You Pills',
    content: mainActivityRaw,
  },
  {
    id: 'github-workflow',
    path: '.github/workflows/build-apk.yml',
    title: '.github/workflows/build-apk.yml',
    language: 'yaml',
    summary: 'Automated Ubuntu JDK 17 + Gradle 8.7 CI workflow producing app-debug.apk artifact',
    content: githubWorkflowRaw,
  },
  {
    id: 'gradle-properties',
    path: 'gradle.properties',
    title: 'gradle.properties',
    language: 'properties',
    summary: 'AndroidX and JVM heap settings required for clean CI compilation',
    content: gradlePropertiesRaw,
  },
];
