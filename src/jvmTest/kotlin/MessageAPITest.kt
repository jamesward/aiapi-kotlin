import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MessageAPITest {

    private val anthropicKey = System.getenv("ANTHROPIC_KEY") ?: throw Exception("You must specify the ANTHROPIC_KEY env var")

    private val messageAPI = MessageAPI(anthropicKey)

    @Serializable
    data class Random(val num: Int)

    @Test
    fun create_works(): Unit = runBlocking {
        val messages = listOf(MessageRequest("hello, world"))
        val messageResponse = messageAPI.create(messages, temperature = 0.0) // more deterministic results with a zero temp
        assert(messageResponse.content.first() == Content.Text("Hello! How can I assist you today? Is there anything specific you'd like to talk about or any questions you have?"))
    }

    @Test
    fun create_with_invalid_temperature(): Unit = runBlocking {
        val messages = listOf(MessageRequest("hello, world"))
        assertFailsWith<MessageAPI.MessageException.InputException> {
            messageAPI.create(messages, temperature = 1.1)
        }
    }

    @Test
    fun work_with_protos(): Unit = runBlocking {
        val random = messageAPI.ask<Random>("num = 42")
        assert(random.num == 42)
    }

    @Test
    fun work_with_protos_invalid(): Unit = runBlocking {
        assertFailsWith<MessageAPI.MessageException.InputException> {
            messageAPI.ask<Random>("num = adsf")
        }
    }

}
