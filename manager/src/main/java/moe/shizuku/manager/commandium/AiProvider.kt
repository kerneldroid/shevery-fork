package moe.shizuku.manager.commandium

import kotlinx.serialization.Serializable

/**
 * One configured AI provider: an OpenAI-compatible endpoint plus the model to use.
 * API keys are NOT stored here; see [AiProviderRepository.getKey].
 */
@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
)
