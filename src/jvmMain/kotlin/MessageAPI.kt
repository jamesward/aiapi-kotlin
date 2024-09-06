import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import kotlinx.serialization.serializer

@Serializable
data class Usage(val inputTokens: Int, val outputTokens: Int)

sealed interface Content {
    data class Text(val text: String) : Content
    data class Unknown(
        val id: String? = null,
        val type: String? = null,
        val name: String? = null,
        val input: MessageAPI.Input?= null,
        val text: String? = null,
    ): Content
}

@Serializable
data class MessageRequest(
    val content: String,
    val role: String = "user",
)

data class MessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<Content>,
    val model: String,
    val stopReason: String,
    val stopSequence: String? = null,
    val usage: Usage,
)

data class MessageAPI(
    private val apiKey: String,
    private val anthropicVersion: String = "2023-06-01",
): AutoCloseable {

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonEncoder = Json {
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(jsonEncoder)
        }
    }

    @Serializable
    data class MessageRequestBody(
        val messages: List<MessageRequest>,
        val model: String,
        val maxTokens: Int,
        val temperature: Double?,
    )

    @Serializable
    class Input

    // Docs are unclear on the valid formats for content: https://docs.anthropic.com/claude/reference/messages_post
    // So handle everything that seems to be possible, then transform it into something consumable later
    @Serializable
    data class ContentResponse(
        val id: String? = null,
        val type: String? = null,
        val name: String? = null,
        val input: Input?= null,
        val text: String? = null,
    )

    @Serializable
    data class MessageResponseBody(
        val id: String,
        val type: String,
        val role: String,
        val content: List<ContentResponse>,
        val model: String,
        val stopReason: String,
        val stopSequence: String? = null,
        val usage: Usage,
    )

    sealed class MessageException(message: String?) : Exception(message) {
        data class APIException(val httpResponse: HttpResponse) : MessageException(null) {
            override val message: String =
                httpResponse.status.description + " : " + runBlocking { httpResponse.bodyAsText() }
        }
        class InputException(message: String): MessageException(message)
        class ResponseException(messageResponse: MessageResponse): MessageException(messageResponse.toString())
    }

    suspend fun create(
        messages: List<MessageRequest>,
        model: String = "claude-3-5-sonnet-20240620",
        maxTokens: Int = 1024,
        temperature: Double = 1.0, // this is the Anthropic default
    ): MessageResponse = run {

        if (temperature < 0.0 || temperature > 1.0) {
            throw MessageException.InputException("temperature must be between 0.0 and 1.0")
        }

        val body = MessageRequestBody(messages, model, maxTokens, temperature)

        val resp = httpClient.post("https://api.anthropic.com/v1/messages") {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", anthropicVersion)
            }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        when(resp.status) {
            HttpStatusCode.OK -> {
                val messageResponseBody = resp.body<MessageResponseBody>()
                val content = messageResponseBody.content.map { content ->
                    if (content.type == "text" && content.text != null) {
                        Content.Text(content.text)
                    }
                    else {
                        Content.Unknown(content.id, content.type, content.name, content.input, content.text)
                    }
                }
                MessageResponse(
                    id = messageResponseBody.id,
                    type = messageResponseBody.type,
                    role = messageResponseBody.role,
                    content = content,
                    model = messageResponseBody.model,
                    stopReason = messageResponseBody.stopReason,
                    stopSequence = messageResponseBody.stopSequence,
                    usage = messageResponseBody.usage,
                )
            }
            else -> {
                throw MessageException.APIException(resp)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> ask(prompt: String): T = run {
        val descriptor = serializer<T>().descriptor
        val schema = ProtoBufSchemaGenerator.generateSchemaText(descriptor)
        val schemaMsg = MessageRequest("here is a protobuf schema:\n$schema")
        val confirmation = MessageRequest("OK", "assistant")
        val request = MessageRequest("RETURN ONLY JSON DATA THAT IS VALIDATED AGAINST THE SCHEMA! $prompt")
        val messages = listOf(schemaMsg, confirmation, request)
        val messageResponse = create(messages)
        val resp: Content = messageResponse.content.first()
        if (resp is Content.Text) {
            try {
                Json.decodeFromString<T>(resp.text)
            }
            catch (e: SerializationException) {
                throw MessageException.InputException(resp.text)
            }
        }
        else {
            throw MessageException.ResponseException(messageResponse)
        }
    }

    override fun close() {
        httpClient.close()
    }
}
