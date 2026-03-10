package com.example.rwkvnotes.model

import android.net.Uri
import com.example.rwkvnotes.ai.EngineReloadResult
import com.example.rwkvnotes.ai.ModelEngineReloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelRuntimeCoordinatorTest {
    @Test
    fun warmupActiveModel_marksSuccessAfterReload() = runTest {
        val manager = FakeModelManager(activePath = "/models/a.bin")
        val reloader = FakeEngineReloader(
            results = mutableMapOf("/models/a.bin" to EngineReloadResult(success = true)),
        )

        val ok = ModelRuntimeCoordinator(manager, reloader).warmupActiveModel()

        assertTrue(ok)
        assertEquals(listOf("/models/a.bin"), reloader.calls)
        assertTrue(manager.runtimeState.value.lastWarmupSuccess)
        assertNull(manager.runtimeState.value.lastErrorMessage)
    }

    @Test
    fun warmupActiveModel_returnsFalseWithoutReloadWhenPrecheckFails() = runTest {
        val manager = FakeModelManager(
            activePath = "/models/a.bin",
            warmupPrecheckResult = false,
            precheckErrorMessage = "active model file not found",
        )
        val reloader = FakeEngineReloader()

        val ok = ModelRuntimeCoordinator(manager, reloader).warmupActiveModel()

        assertEquals(false, ok)
        assertTrue(reloader.calls.isEmpty())
        assertEquals("active model file not found", manager.runtimeState.value.lastErrorMessage)
    }

    @Test
    fun switchActiveModel_rollsBackWhenReloadReturnsFalse() = runTest {
        val manager = FakeModelManager(activePath = "/models/a.bin")
        val reloader = FakeEngineReloader(
            results = mutableMapOf(
                "/models/b.bin" to EngineReloadResult(success = false, errorMessage = "MODEL_OPEN_FAILED: cannot map"),
                "/models/a.bin" to EngineReloadResult(success = true),
            ),
        )

        try {
            ModelRuntimeCoordinator(manager, reloader).switchActiveModel("/models/b.bin")
            fail("expected switch failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("rollback=ok"))
        }

        assertEquals("/models/a.bin", manager.activeModelPath())
        assertTrue(manager.runtimeState.value.lastWarmupSuccess)
        assertNull(manager.runtimeState.value.lastErrorMessage)
        assertEquals(listOf("/models/b.bin", "/models/a.bin"), manager.switchCalls)
        assertEquals(listOf("/models/b.bin", "/models/a.bin"), reloader.calls)
    }

    @Test
    fun switchActiveModel_marksFailureWhenRollbackFails() = runTest {
        val manager = FakeModelManager(activePath = "/models/a.bin")
        val reloader = FakeEngineReloader(
            results = mutableMapOf(
                "/models/b.bin" to EngineReloadResult(success = false, errorMessage = "MODEL_OPEN_FAILED: cannot map"),
                "/models/a.bin" to EngineReloadResult(success = false, errorMessage = "MODEL_OPEN_FAILED: rollback failed"),
            ),
        )

        try {
            ModelRuntimeCoordinator(manager, reloader).switchActiveModel("/models/b.bin")
            fail("expected switch failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("rollback=failed"))
        }

        assertEquals("/models/a.bin", manager.activeModelPath())
        assertEquals(false, manager.runtimeState.value.lastWarmupSuccess)
        assertEquals(
            "MODEL_OPEN_FAILED: cannot map; rollback to /models/a.bin failed",
            manager.runtimeState.value.lastErrorMessage,
        )
        assertEquals(listOf("/models/b.bin", "/models/a.bin"), manager.switchCalls)
        assertEquals(listOf("/models/b.bin", "/models/a.bin"), reloader.calls)
    }
}

private class FakeModelManager(
    activePath: String,
    private val warmupPrecheckResult: Boolean = true,
    private val precheckErrorMessage: String? = null,
) : ModelManager {
    override val models: StateFlow<List<ModelDescriptor>> = MutableStateFlow(emptyList())
    override val runtimeState = MutableStateFlow(
        ModelRuntimeState(
            activeModelPath = activePath,
            mmapReadable = true,
            lastWarmupSuccess = false,
            requiredRuntimeExtension = ".bin",
        ),
    )
    val switchCalls = mutableListOf<String>()

    override suspend fun refreshLocalModels() = Unit

    override suspend fun importModel(modelUri: Uri, preferredFileName: String?): ModelDescriptor {
        error("unused in test")
    }

    override suspend fun downloadModel(
        modelName: String,
        primaryUrl: String,
        mirrorUrls: List<String>,
        expectedSha256: String?,
        maxRetriesPerSource: Int,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?,
    ): ModelDownloadProgress {
        error("unused in test")
    }

    override suspend fun switchModel(path: String): Boolean {
        switchCalls += path
        runtimeState.value = runtimeState.value.copy(
            activeModelPath = path,
            lastWarmupSuccess = false,
            lastErrorMessage = null,
        )
        return true
    }

    override suspend fun warmup(): Boolean {
        runtimeState.value = runtimeState.value.copy(
            lastWarmupSuccess = false,
            lastErrorMessage = if (warmupPrecheckResult) null else precheckErrorMessage,
        )
        return warmupPrecheckResult
    }

    override suspend fun recordWarmupResult(success: Boolean, message: String?) {
        runtimeState.value = runtimeState.value.copy(
            lastWarmupSuccess = success,
            lastErrorMessage = message,
        )
    }

    override fun activeModelPath(): String = runtimeState.value.activeModelPath
}

private class FakeEngineReloader(
    private val results: MutableMap<String, EngineReloadResult> = mutableMapOf(),
) : ModelEngineReloader {
    val calls = mutableListOf<String>()

    override suspend fun reloadEngine(modelPath: String): EngineReloadResult {
        calls += modelPath
        return results[modelPath] ?: EngineReloadResult(success = true)
    }
}
