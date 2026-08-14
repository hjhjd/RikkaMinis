package com.openminis.app.data.repository

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelFavoriteMappingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun config(pinned: Boolean): ProviderConfig {
        val instance = ProviderInstance(
            id = "provider-1",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
        )
        val entry = ModelEntry(
            providerInstanceId = instance.id,
            baseModel = LLMModel(
                id = "gpt-test",
                displayName = "GPT Test",
                provider = "OpenAI",
            ),
            pinned = pinned,
        )
        return ProviderConfig(
            instances = mutableListOf(instance),
            modelEntries = mutableListOf(entry),
        )
    }

    @Test
    fun `favorite survives Room snapshot round trip`() {
        val snapshot = config(pinned = true).toSnapshot(json)

        assertTrue(snapshot.entries.single().pinned != 0)
        assertTrue(snapshot.toProviderConfig(json).modelEntries.single().pinned)
    }

    @Test
    fun `unfavorited entry remains false after Room snapshot round trip`() {
        val snapshot = config(pinned = false).toSnapshot(json)

        assertFalse(snapshot.entries.single().pinned != 0)
        assertFalse(snapshot.toProviderConfig(json).modelEntries.single().pinned)
    }

    @Test
    fun `favorite counts as user modification`() {
        assertTrue(config(pinned = true).modelEntries.single().isUserModified)
    }
}
