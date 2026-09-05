package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;
import io.nekohasekai.sagernet.aidl.SpeedTestData;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);
  oneway void resetTraffic(in long[] profileIds);

  int urlTest(boolean automatic);
  boolean claimAutomaticConnectionCheck();
  String connectionTestStatus();
  String connectionTestIpInfo();
  void setConnectionTestPresentation(String status, String ipInfo);
  oneway void startSpeedTest(long runId, int durationMillis, int connections, int serverMode, String serverValue, int finalResult);
  oneway void stopSpeedTest(long runId);
  SpeedTestData speedTestStatus();

  String currentClashMode();
  String clashModeList();
  void setClashMode(String mode);
  void setLogLevel(String level, boolean enabled);

  String adblockStats();
  String adblockFilterMetadata(String url);
  String adblockFilterMetadataMap(String joinedUrls);
  String adblockStoredFilterVersion(String url);
  String adblockStoredFilterVersions(String joinedUrls);
  String adblockPreCacheFilter(String url);
  String adblockPreCacheFilters(String joinedUrls);
  void adblockDeleteCachedFilter(String url);
  void adblockDeleteCachedFilters(String joinedUrls);
  void adblockReloadEngine();

  boolean isCoreProfilingRunning();
  boolean hasCoreProfilerSnapshot();
  void performLibcoreGcSweep();
  void triggerLibcoreCrash(String crashType);
  void startCoreProfiling(int mode);
  void stopCoreProfiling();
  void writeCoreProfilerSnapshot(String outputDir);
  void deleteCoreProfilerSnapshot();
}
