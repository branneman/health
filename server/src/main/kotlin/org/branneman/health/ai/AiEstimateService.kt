package org.branneman.health.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.branneman.health.AiEstimateResponseDto

@Serializable
data class ClaudeEstimate(val kcal: Int, val explanation: String? = null)

class ClaudeEstimateException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface AnthropicGateway {
    fun estimate(
        apiKey: String,
        text: String?,
        imageBase64: String?,
        imageMimeType: String?,
    ): ClaudeEstimate
}

class HttpAnthropicGateway : AnthropicGateway {

    private val outputConfig: OutputConfig = run {
        val schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "kcal" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 9999),
                        "explanation" to mapOf("type" to "string"),
                    )
                )
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("kcal")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
        OutputConfig.builder()
            .format(JsonOutputFormat.builder().schema(schema).build())
            .build()
    }

    override fun estimate(
        apiKey: String,
        text: String?,
        imageBase64: String?,
        imageMimeType: String?,
    ): ClaudeEstimate {
        val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

        val blocks = mutableListOf<ContentBlockParam>()
        if (text != null) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()))
        }
        if (imageBase64 != null && imageMimeType != null) {
            val mediaType = when (imageMimeType) {
                "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
                else        -> Base64ImageSource.MediaType.IMAGE_JPEG
            }
            blocks.add(
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .data(imageBase64)
                                .mediaType(mediaType)
                                .build()
                        )
                        .build()
                )
            )
        }

        val params = MessageCreateParams.builder()
            .model(Model.CLAUDE_OPUS_4_8)
            .maxTokens(100L)
            .system(
                "You are a nutritionist AI. Estimate the total calorie content of the described " +
                "or shown meal. Return only a JSON object with a required 'kcal' integer (1–9999) " +
                "and an optional 'explanation' string. If you include an explanation, keep it to " +
                "one short sentence starting with the calorie count, e.g. '350 kcal — typical " +
                "cocktail with one spirit measure and mixer.'"
            )
            .addUserMessageOfBlockParams(blocks)
            .outputConfig(outputConfig)
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: Exception) {
            throw ClaudeEstimateException("Claude API call failed: ${e.message}", e)
        }

        val jsonText = message.content()
            .firstNotNullOfOrNull { block -> block.text().orElse(null)?.text() }
            ?: throw ClaudeEstimateException("No text content in Claude response")

        return try {
            Json.decodeFromString<ClaudeEstimate>(jsonText)
        } catch (e: Exception) {
            throw ClaudeEstimateException("Failed to parse Claude response: $jsonText", e)
        }
    }
}

class AiEstimateService(private val gateway: AnthropicGateway) {

    fun estimate(
        apiKey: String,
        text: String?,
        imageBase64: String?,
        imageMimeType: String?,
    ): AiEstimateResponseDto {
        val result = try {
            gateway.estimate(apiKey, text, imageBase64, imageMimeType)
        } catch (e: ClaudeEstimateException) {
            throw e
        } catch (e: Exception) {
            throw ClaudeEstimateException("Estimate failed: ${e.message}", e)
        }

        if (result.kcal !in 1..9999) {
            throw ClaudeEstimateException("kcal out of range: ${result.kcal}")
        }

        return AiEstimateResponseDto(result.kcal, result.explanation)
    }
}
