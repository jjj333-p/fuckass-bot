package org.example

// ktor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

// kotlinx
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
// java stdlib
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// halcyon
import tigase.halcyon.core.AbstractHalcyon
import tigase.halcyon.core.Halcyon
import tigase.halcyon.core.builder.createHalcyon
import tigase.halcyon.core.eventbus.Event
import tigase.halcyon.core.eventbus.EventBus
import tigase.halcyon.core.eventbus.EventDefinition
import tigase.halcyon.core.xmpp.BareJID
import tigase.halcyon.core.xmpp.bareJID
import tigase.halcyon.core.xmpp.modules.MessageReceivedEvent
import tigase.halcyon.core.xmpp.modules.muc.MUCModule
import tigase.halcyon.core.xmpp.modules.muc.MucEvents
import tigase.halcyon.core.xmpp.modules.muc.MucRoomEvents
import tigase.halcyon.core.xmpp.resource
import tigase.halcyon.core.xmpp.stanzas.Message
import tigase.halcyon.core.xmpp.stanzas.MessageType
import tigase.halcyon.core.xmpp.toBareJID

@Serializable
data class Config(
    val jid: String,
    val pass: String,
)

// for some fuckass reason, Halcyon's eventbus doesn't support suspend lambdas
fun <T : Event> EventBus.registerSuspend(
    definition: EventDefinition<T>,
    scope: CoroutineScope,
    action: suspend (T) -> Unit // The 'function color' magic happens here
) {
    // 1. Call Halcyon's original, synchronous register method
    this.register(definition) { event ->
        // 2. Instantly launch a coroutine for every event that fires
        scope.launch {
            // 3. Execute your suspend lambda inside the coroutine!
            action(event)
        }
    }
}

val Message.resourceOrEmpty: String
    get() = this.from?.resource ?: ""

fun String.toMessageType(): MessageType? = when (this) {
    "chat" -> MessageType.Chat
    "groupchat" -> MessageType.Groupchat
    "headline" -> MessageType.Headline
    "normal" -> MessageType.Normal
    else -> null
}

suspend fun sendInspire(halcyon: Halcyon, httpClient: HttpClient, t: BareJID, ty: MessageType) {

    val res = httpClient.get("https://inspirobot.me/api?generate=true")

    val urlMaybe = res.bodyAsText()


    val msg = halcyon.request.message {
        to = t
        type = ty
        body = urlMaybe

        // Halcyon's XML DSL for appending the custom OOB element
        "x" {
            attributes["xmlns"] = "jabber:x:oob"
            "url" {
                +urlMaybe
            }
            "desc" {
                +"An optional description of the file"
            }
        }
    }

    println("Sending message: $msg")
    msg.send()

}

fun main() {

    val config = Json.decodeFromString<Config>(File("config.json").readText())

    val botScope = CoroutineScope(Dispatchers.Default)

    val httpClient = HttpClient(CIO)

    val halcyon = createHalcyon {
        install(MUCModule)
        auth {
            userJID = config.jid.toBareJID()
            password { config.pass }
        }
    }

    halcyon.eventBus.registerSuspend(MessageReceivedEvent, botScope) {
        val fucker = it.stanza.body ?: ""
        if (fucker.startsWith("!echo")) {

            val trimmedfuck = fucker.removePrefix("!echo").trim()

            halcyon.request.message {
                to = it.fromJID
                body = "Echo: $trimmedfuck"
                type = it.stanza.type
            }.send()
        } else if (fucker.startsWith("!inspire")) {
            val j = it.fromJID?.bareJID ?: return@registerSuspend
            sendInspire(halcyon, httpClient, j, it.stanza.type!!)
        }
    }


    halcyon.eventBus.register(MucEvents) {
        if (it is MucEvents.InvitationReceived) {
            println("Invitation received from ${it.invitation.sender} to ${it.invitation.roomjid}")
            halcyon.modules[MUCModule::class].join(it.invitation.roomjid, "fart", it.invitation.password).send()
        }
    }

    val monologueCounter = mutableMapOf<String, Pair<String, Int>>()
    val monologueCounterMutex = Mutex()

    val onlineIndicator = ConcurrentHashMap<String, Boolean>()

    halcyon.eventBus.registerSuspend(MucRoomEvents, botScope) {

        val rJIDstr = it.room.roomJID.toString()

        when (it) {

            // we wait until after the join is successful to ignore the presence spam that is apart of joining
            is MucRoomEvents.YouJoined -> {
                println("Joined room ${it.room.roomJID}")
                onlineIndicator[rJIDstr] = true
            }

            is MucRoomEvents.ReceivedMessage -> {

                // ignore empty/broken messages
                if ((it.message.body ?: "") == "") return@registerSuspend
                if (it.message.resourceOrEmpty == "") return@registerSuspend

                monologueCounterMutex.withLock {

                    // get previous values
                    var (prev, count) = monologueCounter[rJIDstr] ?: Pair("", 0)

                    println("processing for ${it.message.resourceOrEmpty} against $prev")

                    if (prev == it.message.resourceOrEmpty) {
                        if (count >= 10) {
                            sendInspire(halcyon, httpClient, it.room.roomJID, MessageType.Groupchat)

                            // so when we add one later its up to 0
                            count = -1
                        }

                        monologueCounter[rJIDstr] = Pair(prev, count + 1)

                        println("Monologue counter: $prev -> $count")
                    } else {
                        monologueCounter[rJIDstr] = Pair(it.message.resourceOrEmpty, 1)
                        println("Initialized monologue counter for ${it.message.resourceOrEmpty}")
                    }
                }
            }

            is MucRoomEvents.Created -> { /* TODO */
            }

            is MucRoomEvents.JoinError -> { /* TODO */
            }

            is MucRoomEvents.OccupantCame -> {
                if (onlineIndicator[rJIDstr] ?: false)
                    halcyon.modules[MUCModule::class].message(it.room, "${it.nickname} wbbbbbbb").send()
            }

            is MucRoomEvents.OccupantChangedPresence -> { /* TODO */
            }

            is MucRoomEvents.OccupantLeave -> { /* TODO */
            }

            is MucRoomEvents.YouLeaved -> {
                onlineIndicator[rJIDstr] = false

                val x = it.presence.getChildrenNS("x", "http://jabber.org/protocol/muc#user")
                val code = x?.getFirstChild("status")?.attributes["code"]
                val item = x?.getFirstChild("item")
                val affiliation = item?.attributes["affiliation"]
                val role = item?.attributes["role"]
                val actorStanza = item?.getFirstChild("actor")
                val actor = actorStanza?.attributes["jid"] ?: actorStanza?.attributes["nick"] ?: "<unknown>"
                val reason = item?.getFirstChild("reason")?.value

                val permanent: Boolean
                val type: String
                when (code) {
                    "301" -> {
                        permanent = true
                        type = "Banned"
                    }
                    "321" -> {
                        permanent = true
                        type = "Affiliation revoked"
                    }
                    "322" -> {
                        permanent = true
                        type = "Room locked"
                    }
                    "307" -> {
                        permanent = true
                        type = "Kicked"
                    }
                    "332" -> {
                        permanent = false
                        type = "restart"
                        // TODO: check for destroy to see if permanent
                    }
                    null -> {
                        permanent = true
                        type = "Unknown"
                    }
                    else -> {
                        permanent = false
                        type = "Left"
                    }
                }

                println("$type (${if (permanent) "permanent" else "temporary"}) room $rJIDstr with code $code and reason ${reason}, now have affiliation $affiliation and role ${role}. performed by ${actor}.")
            }
        }
    }

    halcyon.connectAndWait()

    // waiting while client is connected
    // im not sure this needs to be here but it was in sample code
    while (halcyon.state == AbstractHalcyon.State.Connected) Thread.sleep(1000)
}