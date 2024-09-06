import kotlinx.datetime.LocalDate
import kotlinx.serialization.*

@Serializable
data class Person(val name: String)

@Serializable
data class People(val people: List<Person>)

@Serializable
data class FullMoons(val dates: List<LocalDate>)

suspend fun main() {
    val anthropicKey = System.getenv("ANTHROPIC_KEY") ?: throw Exception("You must specify the ANTHROPIC_KEY env var")
    MessageAPI(anthropicKey).use { messageAPI ->
        val person = messageAPI.ask<Person>("return a random person")
        println(person)
        val people = messageAPI.ask<People>("return a list of 3 random people")
        println(people)
        val fullMoons = messageAPI.ask<FullMoons>("return all full moons in 2024 for Denver, Colorado")
        println(fullMoons)
    }
}
