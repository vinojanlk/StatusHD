package com.techwin.statushd.storage

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StoragePreflightResult {
    data class Sufficient(
        val availability: StorageAvailability,
        val required: VolumeRequiredStorage,
        val isCloseToLimit: Boolean = false
    ) : StoragePreflightResult

    data class InternalCacheInsufficient(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : StoragePreflightResult

    data class SharedMediaInsufficient(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : StoragePreflightResult

    data class BothInsufficient(
        val internalAvailableBytes: Long,
        val internalRequiredBytes: Long,
        val sharedAvailableBytes: Long,
        val sharedRequiredBytes: Long
    ) : StoragePreflightResult

    data class InternalAvailabilityUnavailable(
        val requiredBytes: Long
    ) : StoragePreflightResult

    data class SharedAvailabilityUnavailable(
        val requiredBytes: Long
    ) : StoragePreflightResult

    data class BothAvailabilityUnavailable(
        val internalRequiredBytes: Long,
        val sharedRequiredBytes: Long
    ) : StoragePreflightResult
}

sealed interface StoragePreflightPrompt {
    data class InsufficientShared(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : StoragePreflightPrompt

    data class InsufficientInternal(
        val availableBytes: Long,
        val requiredBytes: Long
    ) : StoragePreflightPrompt

    data class InsufficientBoth(
        val internalAvailableBytes: Long,
        val internalRequiredBytes: Long,
        val sharedAvailableBytes: Long,
        val sharedRequiredBytes: Long
    ) : StoragePreflightPrompt

    data object Unavailable : StoragePreflightPrompt
}

data class StorageLowWarning(
    val internalAvailableBytes: Long?,
    val internalRequiredBytes: Long,
    val sharedAvailableBytes: Long?,
    val sharedRequiredBytes: Long
)

class StoragePreflightChecker(
    private val availableStorageProvider: AvailableStorageProvider
) {
    suspend fun check(required: VolumeRequiredStorage): StoragePreflightResult {
        val availability = try {
            availableStorageProvider.getAvailability()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StorageAvailability(
                internalCacheAvailableBytes = null,
                sharedMediaAvailableBytes = null
            )
        }

        return evaluate(availability = availability, required = required)
    }

    companion object {
        fun evaluate(
            availability: StorageAvailability,
            required: VolumeRequiredStorage
        ): StoragePreflightResult {
            val internalReq = required.internalCacheRequiredBytes.coerceAtLeast(0L)
            val sharedReq = required.sharedMediaRequiredBytes.coerceAtLeast(0L)

            val internalAvail = availability.internalCacheAvailableBytes
            val sharedAvail = availability.sharedMediaAvailableBytes

            val internalRequiresCheck = internalReq > 0L
            val sharedRequiresCheck = sharedReq > 0L

            val internalKnownInsufficient = internalRequiresCheck &&
                internalAvail != null && internalAvail < internalReq
            val sharedKnownInsufficient = sharedRequiresCheck &&
                sharedAvail != null && sharedAvail < sharedReq

            if (internalKnownInsufficient && sharedKnownInsufficient) {
                return StoragePreflightResult.BothInsufficient(
                    internalAvailableBytes = internalAvail,
                    internalRequiredBytes = internalReq,
                    sharedAvailableBytes = sharedAvail,
                    sharedRequiredBytes = sharedReq
                )
            }
            if (internalKnownInsufficient) {
                return StoragePreflightResult.InternalCacheInsufficient(
                    availableBytes = internalAvail,
                    requiredBytes = internalReq
                )
            }
            if (sharedKnownInsufficient) {
                return StoragePreflightResult.SharedMediaInsufficient(
                    availableBytes = sharedAvail,
                    requiredBytes = sharedReq
                )
            }

            val internalUnknown = internalRequiresCheck && internalAvail == null
            val sharedUnknown = sharedRequiresCheck && sharedAvail == null

            if (internalUnknown && sharedUnknown) {
                return StoragePreflightResult.BothAvailabilityUnavailable(
                    internalRequiredBytes = internalReq,
                    sharedRequiredBytes = sharedReq
                )
            }
            if (internalUnknown) {
                return StoragePreflightResult.InternalAvailabilityUnavailable(
                    requiredBytes = internalReq
                )
            }
            if (sharedUnknown) {
                return StoragePreflightResult.SharedAvailabilityUnavailable(
                    requiredBytes = sharedReq
                )
            }

            // At this point, for every volume that requires storage (> 0), availability is known and >= required.
            var isCloseToLimit = false
            if (internalRequiresCheck && internalAvail != null) {
                val margin = internalAvail - internalReq
                if (margin < StorageEstimatePolicy.TIGHT_STORAGE_MARGIN_BYTES) {
                    isCloseToLimit = true
                }
            }
            if (sharedRequiresCheck && sharedAvail != null) {
                val margin = sharedAvail - sharedReq
                if (margin < StorageEstimatePolicy.TIGHT_STORAGE_MARGIN_BYTES) {
                    isCloseToLimit = true
                }
            }

            return StoragePreflightResult.Sufficient(
                availability = availability,
                required = required,
                isCloseToLimit = isCloseToLimit
            )
        }
    }
}

class StoragePreflightGate(
    private val scope: CoroutineScope,
    private val storagePreflightChecker: StoragePreflightChecker
) {
    private val _prompt = MutableStateFlow<StoragePreflightPrompt?>(null)
    val prompt: StateFlow<StoragePreflightPrompt?> = _prompt.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _lowStorageWarning = MutableStateFlow<StorageLowWarning?>(null)
    val lowStorageWarning: StateFlow<StorageLowWarning?> = _lowStorageWarning.asStateFlow()

    private var inFlightJob: Job? = null
    private var pendingContinueAction: (() -> Unit)? = null

    fun requestStart(
        canStart: () -> Boolean,
        estimateRequired: suspend () -> VolumeRequiredStorage,
        onProceed: (StorageLowWarning?) -> Unit
    ): Boolean {
        if (!canStart()) return false
        if (inFlightJob?.isActive == true || _prompt.value != null) return false

        _isChecking.value = true
        val job = scope.launch {
            try {
                val required = estimateRequired()
                val result = storagePreflightChecker.check(required)
                if (!canStart()) return@launch
                when (result) {
                    is StoragePreflightResult.Sufficient -> {
                        val warning = if (result.isCloseToLimit) {
                            StorageLowWarning(
                                internalAvailableBytes = result.availability.internalCacheAvailableBytes,
                                internalRequiredBytes = result.required.internalCacheRequiredBytes,
                                sharedAvailableBytes = result.availability.sharedMediaAvailableBytes,
                                sharedRequiredBytes = result.required.sharedMediaRequiredBytes
                            )
                        } else {
                            null
                        }
                        _lowStorageWarning.value = warning
                        onProceed(warning)
                    }
                    is StoragePreflightResult.InternalCacheInsufficient -> {
                        pendingContinueAction = null
                        _prompt.value = StoragePreflightPrompt.InsufficientInternal(
                            availableBytes = result.availableBytes,
                            requiredBytes = result.requiredBytes
                        )
                    }
                    is StoragePreflightResult.SharedMediaInsufficient -> {
                        pendingContinueAction = null
                        _prompt.value = StoragePreflightPrompt.InsufficientShared(
                            availableBytes = result.availableBytes,
                            requiredBytes = result.requiredBytes
                        )
                    }
                    is StoragePreflightResult.BothInsufficient -> {
                        pendingContinueAction = null
                        _prompt.value = StoragePreflightPrompt.InsufficientBoth(
                            internalAvailableBytes = result.internalAvailableBytes,
                            internalRequiredBytes = result.internalRequiredBytes,
                            sharedAvailableBytes = result.sharedAvailableBytes,
                            sharedRequiredBytes = result.sharedRequiredBytes
                        )
                    }
                    is StoragePreflightResult.InternalAvailabilityUnavailable,
                    is StoragePreflightResult.SharedAvailabilityUnavailable,
                    is StoragePreflightResult.BothAvailabilityUnavailable -> {
                        pendingContinueAction = {
                            _lowStorageWarning.value = null
                            onProceed(null)
                        }
                        _prompt.value = StoragePreflightPrompt.Unavailable
                    }
                }
            } finally {
                if (inFlightJob == coroutineContext[Job]) {
                    inFlightJob = null
                }
                _isChecking.value = false
            }
        }
        if (!job.isCompleted) {
            inFlightJob = job
        }
        return true
    }

    fun continueAfterUnavailable() {
        if (_prompt.value !is StoragePreflightPrompt.Unavailable) return
        val action = pendingContinueAction
        pendingContinueAction = null
        _prompt.value = null
        action?.invoke()
    }

    fun dismissPrompt() {
        pendingContinueAction = null
        _prompt.value = null
    }

    fun clearAll() {
        inFlightJob?.cancel()
        inFlightJob = null
        _isChecking.value = false
        pendingContinueAction = null
        _prompt.value = null
        _lowStorageWarning.value = null
    }

    fun clearWarning() {
        _lowStorageWarning.value = null
    }
}

object StorageSettingsNavigator {
    fun createManageStorageIntent(): Intent =
        Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
}

object StoragePreflightTestHooks {
    @Volatile
    var availableStorageProviderFactory: ((Context) -> AvailableStorageProvider)? = null

    fun resolveStorageProvider(context: Context): AvailableStorageProvider {
        return availableStorageProviderFactory?.invoke(context)
            ?: AndroidAvailableStorageProvider(context)
    }

    fun reset() {
        availableStorageProviderFactory = null
    }
}
