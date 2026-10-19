package com.akane.inspiration.ipc;

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot;
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot;
import com.akane.inspiration.ipc.model.BgDexoptStatus;
import com.akane.inspiration.ipc.model.ToolkitResult;
import com.akane.inspiration.ipc.model.CpuTimeSnapshot;
import com.akane.inspiration.ipc.model.DumpResult;
import com.akane.inspiration.ipc.model.KernelBatterySnapshot;

interface ITelemetryUserService {
    void destroy() = 16777114;
    KernelBatterySnapshot readKernelBattery() = 1;
    CpuTimeSnapshot readCpuTimeInState() = 2;
    BatteryUsageSnapshot readBatteryUsage(long maxAgeMs) = 3;
    // 4 reserved (was readPowerMonitors, debug-probe only; the probe now reads it in-process; never reuse).
    // 5 reserved (was readDeviceIdle, debug-probe only; the probe now reads it in-process; never reuse).
    DumpResult openDump(int target) = 6;
    // 7 reserved (was generic command runner; never reuse). Phase 5 typed actions start at 20.
    BatteryHistorySnapshot readBatteryHistory(long statsStartMs, long afterHistoryTimeMs, int maxEvents) = 8;

    // Phase 5 typed privileged actions. Only int codes cross this boundary (see ipc/toolkit/Toolkit.kt).
    ToolkitResult setAppOp(String packageName, int op, int mode) = 20;
    ToolkitResult getAppOps(String packageName) = 21;
    ToolkitResult setStandbyBucket(String packageName, int bucket) = 22;
    ToolkitResult getStandbyBucket(String packageName) = 23;
    ToolkitResult applyDozeProfile(int profile, String expectedBeforeRaw) = 24;
    ToolkitResult getDozeState() = 25;
    ToolkitResult forceStop(String packageName) = 26;
    ToolkitResult startBgDexopt() = 27;
    BgDexoptStatus getBgDexoptStatus() = 28;
    ToolkitResult cancelBgDexopt() = 29;
    ToolkitResult restoreDozeRaw(String rawOrNull, String expectedCurrentRaw) = 30;
    int getApiVersion() = 31;
}
