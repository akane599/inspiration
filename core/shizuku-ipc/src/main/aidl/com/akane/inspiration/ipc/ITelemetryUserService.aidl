package com.akane.inspiration.ipc;

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot;
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot;
import com.akane.inspiration.ipc.model.CpuTimeSnapshot;
import com.akane.inspiration.ipc.model.DeviceIdleSnapshot;
import com.akane.inspiration.ipc.model.DumpResult;
import com.akane.inspiration.ipc.model.KernelBatterySnapshot;
import com.akane.inspiration.ipc.model.PowerMonitorSnapshot;

interface ITelemetryUserService {
    void destroy() = 16777114;
    KernelBatterySnapshot readKernelBattery() = 1;
    CpuTimeSnapshot readCpuTimeInState() = 2;
    BatteryUsageSnapshot readBatteryUsage(long maxAgeMs) = 3;
    PowerMonitorSnapshot readPowerMonitors() = 4;
    DeviceIdleSnapshot readDeviceIdle() = 5;
    DumpResult openDump(int target) = 6;
    // 7 reserved (was generic command runner; never reuse). Phase 5 typed actions start at 20.
    BatteryHistorySnapshot readBatteryHistory(long statsStartMs, long afterHistoryTimeMs, int maxEvents) = 8;
}
