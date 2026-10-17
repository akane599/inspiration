package com.akane.inspiration.ipc.client

import android.os.Parcel
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot

/**
 * E3b byte budget for the usage DTO: above [MAX_BYTES] the lowest-`totalMah` UIDs are dropped
 * until the parcel fits, and the drop count lands in `truncatedUids`.
 */
internal object UsageParcelBudget {
    const val MAX_BYTES = 512 * 1024

    fun fit(
        snapshot: BatteryUsageSnapshot,
        parcelBytes: (BatteryUsageSnapshot) -> Int,
        maxBytes: Int = MAX_BYTES,
    ): BatteryUsageSnapshot {
        if (snapshot.uids.isEmpty() || parcelBytes(snapshot) <= maxBytes) return snapshot
        // Stable: equal totals drop in their original order.
        val dropOrder = snapshot.uids.indices.sortedBy { snapshot.uids[it].totalMah }
        fun dropping(count: Int): BatteryUsageSnapshot {
            val dropped = dropOrder.subList(0, count).toHashSet()
            return snapshot.copy(
                uids = snapshot.uids.filterIndexed { index, _ -> index !in dropped },
                truncatedUids = count,
            )
        }
        // Size shrinks monotonically with the drop count, so binary-search the smallest count.
        var low = 1
        var high = snapshot.uids.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (parcelBytes(dropping(mid)) <= maxBytes) high = mid else low = mid + 1
        }
        return dropping(low)
    }

    /** Measures [snapshot] in a temporary [Parcel], as the binder reply would carry it. */
    fun measureParcelBytes(snapshot: BatteryUsageSnapshot): Int {
        val parcel = Parcel.obtain()
        try {
            snapshot.writeToParcel(parcel, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}
