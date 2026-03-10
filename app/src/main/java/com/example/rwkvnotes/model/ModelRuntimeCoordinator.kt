package com.example.rwkvnotes.model

import com.example.rwkvnotes.ai.ModelEngineReloader

internal class ModelRuntimeCoordinator(
    private val modelManager: ModelManager,
    private val engineReloader: ModelEngineReloader,
) {
    suspend fun warmupActiveModel(): Boolean {
        val precheckOk = modelManager.warmup()
        if (!precheckOk) {
            return false
        }
        val activePath = modelManager.activeModelPath()
        val reloaded = try {
            engineReloader.reloadEngine(activePath)
        } catch (throwable: Throwable) {
            modelManager.recordWarmupResult(false, throwable.message ?: "engine reload failed for $activePath")
            return false
        }
        modelManager.recordWarmupResult(
            success = reloaded.success,
            message = if (reloaded.success) null else reloaded.errorMessage ?: "engine reload failed for $activePath",
        )
        return reloaded.success
    }

    suspend fun switchActiveModel(path: String): Boolean {
        require(path.isNotBlank()) { "model path is blank" }
        val previousPath = modelManager.activeModelPath()
        if (path == previousPath) {
            return warmupActiveModel()
        }
        val switched = modelManager.switchModel(path)
        require(switched) { "model switch rejected: $path" }
        val reloaded = try {
            engineReloader.reloadEngine(path)
        } catch (throwable: Throwable) {
            val rollbackOk = rollbackTo(previousPath)
            finalizeAfterSwitchFailure(
                path = path,
                previousPath = previousPath,
                rollbackOk = rollbackOk,
                failureMessage = throwable.message,
            )
            throw IllegalStateException(buildSwitchFailureMessage(path, previousPath, rollbackOk), throwable)
        }
        if (reloaded.success) {
            modelManager.recordWarmupResult(true)
            return true
        }
        val rollbackOk = rollbackTo(previousPath)
        finalizeAfterSwitchFailure(path, previousPath, rollbackOk, reloaded.errorMessage)
        throw IllegalStateException(buildSwitchFailureMessage(path, previousPath, rollbackOk))
    }

    private suspend fun rollbackTo(previousPath: String): Boolean {
        val switchedBack = try {
            modelManager.switchModel(previousPath)
        } catch (_: Throwable) {
            return false
        }
        if (!switchedBack) {
            return false
        }
        return try {
            engineReloader.reloadEngine(previousPath).success
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun finalizeAfterSwitchFailure(
        path: String,
        previousPath: String,
        rollbackOk: Boolean,
        failureMessage: String?,
    ) {
        if (rollbackOk) {
            modelManager.recordWarmupResult(true)
            return
        }
        modelManager.recordWarmupResult(
            success = false,
            message = failureMessage?.let { "$it; rollback to $previousPath failed" }
                ?: "engine reload failed for $path and rollback to $previousPath failed",
        )
    }
}

private fun buildSwitchFailureMessage(path: String, previousPath: String, rollbackOk: Boolean): String {
    return "engine reload failed after model switch: $path; rollback=" +
        if (rollbackOk) "ok ($previousPath)" else "failed"
}
