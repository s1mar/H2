package com.kin.familyhealth.vitals.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.kin.familyhealth.vitals.model.Vitals
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * AGENT-VITALS: thin wrapper over the Health Connect client SDK.
 *
 * Reads the latest available snapshot for each vital. Any single record type
 * failing (not granted, no data, Health Connect not installed) degrades that
 * one field to null rather than failing the whole read.
 */
sealed class HealthConnectAvailability {
    object Available : HealthConnectAvailability()
    object NotInstalled : HealthConnectAvailability()
    object Unavailable : HealthConnectAvailability()
}

class HealthConnectReader(private val context: Context) {

    /** Permission set onboarding must request before reads will succeed. */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    /** Checks whether the Health Connect provider is installed/usable on this device. */
    fun availability(): HealthConnectAvailability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.NotInstalled
            else -> HealthConnectAvailability.Unavailable
        }
    }

    private fun clientOrNull(): HealthConnectClient? {
        return if (availability() is HealthConnectAvailability.Available) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = clientOrNull() ?: return false
        val granted = runCatching {
            client.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        return granted.containsAll(permissions)
    }

    /**
     * Reads the latest snapshot for [uid]. Fields are null when unavailable,
     * ungranted, or absent — callers should render partial data gracefully.
     */
    suspend fun readLatest(uid: String): Vitals {
        val client = clientOrNull()
            ?: return Vitals(uid = uid, timestampEpochMs = System.currentTimeMillis())

        val now = Instant.now()
        val last24h = TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now)

        val heartRate = readLatestHeartRate(client, last24h)
        val restingHr = readLatestRestingHr(client, last24h)
        val steps = readTodaySteps(client)
        val sleepMinutes = readLastNightSleepMinutes(client)
        val spo2 = readLatestSpo2(client, last24h)

        return Vitals(
            uid = uid,
            timestampEpochMs = System.currentTimeMillis(),
            heartRateBpm = heartRate,
            restingHrBpm = restingHr,
            steps = steps,
            sleepMinutes = sleepMinutes,
            spo2Percent = spo2,
        )
    }

    private suspend fun readLatestHeartRate(
        client: HealthConnectClient,
        range: TimeRangeFilter,
    ): Int? = runCatching {
        val records = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = range)
        ).records
        records.flatMap { it.samples }
            .maxByOrNull { it.time }
            ?.beatsPerMinute
            ?.toInt()
    }.getOrNull()

    private suspend fun readLatestRestingHr(
        client: HealthConnectClient,
        range: TimeRangeFilter,
    ): Int? = runCatching {
        val records = client.readRecords(
            ReadRecordsRequest(RestingHeartRateRecord::class, timeRangeFilter = range)
        ).records
        records.maxByOrNull { it.time }?.beatsPerMinute?.toInt()
    }.getOrNull()

    private suspend fun readLatestSpo2(
        client: HealthConnectClient,
        range: TimeRangeFilter,
    ): Double? = runCatching {
        val records = client.readRecords(
            ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = range)
        ).records
        records.maxByOrNull { it.time }?.percentage?.value
    }.getOrNull()

    private suspend fun readTodaySteps(client: HealthConnectClient): Int? = runCatching {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
            )
        )
        response[StepsRecord.COUNT_TOTAL]?.toInt()
    }.getOrNull()

    private suspend fun readLastNightSleepMinutes(client: HealthConnectClient): Int? = runCatching {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // "Last night" window: noon yesterday through noon today, covers any sleep session.
        val windowStart = today.minusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant()
        val windowEnd = today.atTime(LocalTime.NOON).atZone(zone).toInstant()
        val records = client.readRecords(
            ReadRecordsRequest(
                SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(windowStart, windowEnd),
            )
        ).records
        if (records.isEmpty()) return@runCatching null
        val totalMinutes = records.sumOf { record ->
            Duration.between(record.startTime, record.endTime).toMinutes()
        }
        totalMinutes.toInt()
    }.getOrNull()

    companion object {
        /**
         * Convenience for onboarding: builds the request-permissions contract
         * activity result launcher input. Onboarding still owns the actual
         * ActivityResultLauncher wiring; this just supplies the permission set.
         */
        fun requestPermissionsContract() = PermissionController.createRequestPermissionResultContract()
    }
}
