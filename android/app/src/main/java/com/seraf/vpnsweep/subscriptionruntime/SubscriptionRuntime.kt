package com.seraf.vpnsweep.subscriptionruntime

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import com.seraf.vpnsweep.core.mobile.Mobile
import com.seraf.vpnsweep.core.mobile.SubscriptionSnapshot
import com.seraf.vpnsweep.searchprotocol.PrivateInputV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionBatchStatusV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionFailureV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionObserverV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionPortV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionProfilesTooLargeException
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRecordV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionRejectV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionSnapshotV1
import com.seraf.vpnsweep.subscriptionprotocol.SubscriptionStartV1
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_RECORDS = 10_000L
private const val MAX_PROFILES_BYTES = 8L * 1024L * 1024L
private const val GO_SIZE_CODE = "subscription_size"
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val ID_PATTERN = Regex("[0-9a-f]{32}")

internal class AndroidSubscriptionPort(
    context: Context,
    private val executor: Executor,
    private val timeoutMillis: Long,
) : SubscriptionPortV1 {
    private val app = requireNotNull(context.applicationContext)
    private val manager = requireNotNull(app.getSystemService(ConnectivityManager::class.java))
    private var active: Operation? = null

    private class Operation(val generation: Long, val startedNanos: Long) {
        val cancellation = SubscriptionCancellation()
    }

    override fun start(generation: Long, input: String, observer: SubscriptionObserverV1): SubscriptionStartV1 {
        if (generation <= 0 || timeoutMillis <= 0 || timeoutMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            return SubscriptionStartV1.REJECTED
        }
        val operation = synchronized(this) {
            if (active != null) return SubscriptionStartV1.BUSY
            Operation(generation, System.nanoTime()).also { active = it }
        }
        try {
            executor.execute { run(operation, input, observer) }
        } catch (_: RuntimeException) {
            synchronized(this) { if (active === operation) active = null }
            return SubscriptionStartV1.REJECTED
        }
        return SubscriptionStartV1.ACCEPTED
    }

    override fun cancel(generation: Long): Unit {
        val operation = synchronized(this) { active?.takeIf { it.generation == generation } } ?: return
        operation.cancellation.cancel()
    }

    private fun run(operation: Operation, input: String, observer: SubscriptionObserverV1): Unit {
        var route: AndroidSubscriptionRoute? = null
        var snapshot: AndroidSubscriptionSnapshot? = null
        var failure = SubscriptionFailureV1.INTERNAL
        try {
            if (operation.cancellation.cancelled()) throw IOException("cancelled")
            route = AndroidSubscriptionRoute.open(manager) {
                operation.cancellation.revoke()
            } ?: throw IOException("destination_rejected")
            operation.cancellation.installRoute(route::cancel)
            if (operation.cancellation.cancelled()) throw IOException("cancelled")
            val remaining = timeoutMillis * NANOS_PER_MILLISECOND - (System.nanoTime() - operation.startedNanos)
            if (remaining < NANOS_PER_MILLISECOND) throw IOException("subscription_timeout")
            val request = Mobile.newSubscriptionRequest(route, TimeUnit.NANOSECONDS.toMillis(remaining))
            operation.cancellation.installRequest(request::cancel)
            val native = request.fetch(input)
            try {
                snapshot = AndroidSubscriptionSnapshot(app.cacheDir, native)
            } catch (failure: Exception) {
                native.close()
                throw failure
            }
            if (operation.cancellation.cancelled() || operation.cancellation.revoked() || !route.current()) {
                snapshot.close()
                snapshot = null
                throw IOException(if (operation.cancellation.cancelled()) "cancelled" else "destination_rejected")
            }
        } catch (exception: Exception) {
            failure = when {
                operation.cancellation.cancelled() -> SubscriptionFailureV1.CANCELLED
                operation.cancellation.revoked() -> SubscriptionFailureV1.NETWORK
                else -> subscriptionFailure(exception.message)
            }
        } finally {
            operation.cancellation.clear()
            try {
                route?.close()
            } catch (_: Exception) {
                try {
                    snapshot?.close()
                } catch (_: Exception) {
                }
                snapshot = null
                failure = SubscriptionFailureV1.INTERNAL
            }
            synchronized(this) { if (active === operation) active = null }
        }
        val result = snapshot
        try {
            if (result == null) observer.onFailure(operation.generation, failure)
            else observer.onParsed(operation.generation, result.status, result)
        } catch (_: Exception) {
        }
    }
}

private fun subscriptionFailure(code: String?): SubscriptionFailureV1 = when (code) {
    "invalid_subscription_url" -> SubscriptionFailureV1.INVALID_URL
    "subscription_timeout" -> SubscriptionFailureV1.TIMEOUT
    "subscription_http_status", "redirect_rejected" -> SubscriptionFailureV1.HTTP
    "destination_rejected", "resolution_failed", "network_failed", "subscription_read_failed" -> SubscriptionFailureV1.NETWORK
    "tls_failed" -> SubscriptionFailureV1.TLS
    "empty_input", "invalid_utf8", "unsupported_content_encoding" -> SubscriptionFailureV1.MALFORMED
    "invalid_envelope" -> SubscriptionFailureV1.INVALID_BASE64
    "record_count", "record_too_large", "decoded_too_large", "input_too_large", "subscription_too_large" -> SubscriptionFailureV1.LIMIT_EXCEEDED
    "cancelled" -> SubscriptionFailureV1.CANCELLED
    else -> SubscriptionFailureV1.INTERNAL
}

internal class AndroidSubscriptionSnapshot(
    private val cache: File,
    private val native: SubscriptionSnapshot,
) : SubscriptionSnapshotV1 {
    val status: SubscriptionBatchStatusV1 = when (native.status()) {
        1L -> SubscriptionBatchStatusV1.HAS_SUPPORTED
        2L -> SubscriptionBatchStatusV1.NO_SUPPORTED
        else -> throw IOException("subscription_snapshot")
    }
    override val records: List<SubscriptionRecordV1> = readRecords()
    private var closed = false

    private fun readRecords(): List<SubscriptionRecordV1> {
        val count = native.recordCount()
        if (count !in 1..MAX_RECORDS) throw IOException("subscription_snapshot")
        val records = ArrayList<SubscriptionRecordV1>(count.toInt())
        val ids = HashSet<String>()
        for (index in 0 until count) {
            val record = JSONObject(String(native.recordJSON(index), Charsets.UTF_8))
            val id = record.getString("id")
            val supported = record.getBoolean("supported")
            val reason = if (supported) null else {
                if (record.optString("reason", "") != "unsupported_record") throw IOException("subscription_snapshot")
                SubscriptionRejectV1.UNSUPPORTED_RECORD
            }
            if (!ID_PATTERN.matches(id) || !ids.add(id) || (supported && record.has("reason"))) {
                throw IOException("subscription_snapshot")
            }
            records.add(SubscriptionRecordV1(id, record.optString("title", ""), record.optString("subtitle", ""), supported, reason))
        }
        if (records.any { it.supported } != (status == SubscriptionBatchStatusV1.HAS_SUPPORTED)) {
            throw IOException("subscription_snapshot")
        }
        return records.toList()
    }

    @Synchronized
    override fun selectedProfiles(ids: Set<String>): PrivateInputV1? {
        if (closed || ids.isEmpty() || ids.size > MAX_RECORDS || !records.filter { it.supported }.map { it.id }.toSet().containsAll(ids)) {
            return null
        }
        val file = try {
            File.createTempFile("subscription-profiles-", ".private", cache)
        } catch (_: IOException) {
            return null
        }
        try {
            val written = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                native.writeSelected(JSONArray(ids.toList()).toString().toByteArray(Charsets.UTF_8), fd.fd.toLong())
            }
            if (written !in 1..MAX_PROFILES_BYTES || file.length() != written) throw IOException("subscription_profiles")
            return PrivateFileInput(file)
        } catch (failure: Exception) {
            file.delete()
            if (failure.message == GO_SIZE_CODE) throw SubscriptionProfilesTooLargeException()
            return null
        }
    }

    @Synchronized
    fun writeSnapshot(fd: Long): Long {
        check(!closed)
        return native.writeSnapshot(fd)
    }

    @Synchronized
    override fun close(): Unit {
        if (!closed) {
            native.close()
            closed = true
        }
    }
}

private class PrivateFileInput(private val file: File) : PrivateInputV1 {
    private var closed = false

    @Synchronized
    override fun open(): InputStream {
        if (closed) throw IOException("subscription_profiles_closed")
        return FileInputStream(file)
    }

    @Synchronized
    override fun close(): Unit {
        if (closed) return
        if (file.exists() && !file.delete()) throw IOException("subscription_profiles_cleanup")
        closed = true
    }
}
