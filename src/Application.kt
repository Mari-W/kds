package de.moeri

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.request.isMultipart
import io.ktor.request.path
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.util.KtorExperimentalAPI
import org.slf4j.event.Level
import java.sql.Date

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@UseExperimental(KtorExperimentalAPI::class)
fun Application.module() {

    Config.init(environment.config)
    Database.init()

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }



    routing {
        get("/") {
            call.respondTwig("info")
        }
        get("status") {
            call.respondTwig("status", mapOf("dates" to Database.dates()))
        }
        get("events") {
            call.respondTwig("events")
        }
        get("/submit_personal") {
            call.respondTwig("submit_pers")
        }
        get("/submit_historic") {
            call.respondTwig("submit_hist")
        }
        post("/submit") {
            if (!call.request.isMultipart())
                call.respond(HttpStatusCode.Forbidden.description("Oh boy, tryin' to upload different forms?"))
            else
                call.receiveMultipart().readAllParts().map {
                    when (it) {
                        is PartData.FormItem -> {
                            it.name to it.value
                        }
                        else -> {
                            call.respond(HttpStatusCode.Forbidden.description("Sorry, we only take texts :/"))
                            return@post
                        }
                    }
                }.toMap().map {
                    it.key to it.value.replace("<", "(").replace(">", ")")
                }.toMap().apply {
                    try {
                        if (containsKey("g-recaptcha-response")) {
                            ReCaptcha.validate(get("g-recaptcha-response")!!)
                        } else {
                            call.respond(HttpStatusCode.Forbidden.description("U ROBOT!!!"))
                            return@post
                        }
                        if (!containsKey("type")) {
                            call.respond(HttpStatusCode.Forbidden.description("Invalid type, only historic or personal allowed."))
                            return@post
                        }
                        val type: Type? = Type.valueOf(get("type")!!.toUpperCase())
                        val okay = when (type) {
                            Type.PERSONAL -> containsKey("description") && containsKey("date")
                            Type.HISTORIC -> containsKey("description") && containsKey("date") && containsKey("source")
                            else -> false
                        }
                        if (okay && type != null) {
                            val entry = Entry(
                                type = type,
                                source = if (type == Type.HISTORIC) get("source")!! else "",
                                date = Date.valueOf(get("date")!!),
                                description = get("description")!!,
                                name = if (containsKey("name")) get("name")!! else "",
                                email = if (containsKey("email")) get("email")!! else ""
                            )
                            call.respondTwig("respond", mapOf(
                                "message" to if(Database.insert(entry))
                                    "Dein Ereignis wurde erfolgreich eingetragen!"
                                else "Du kannst maxmimal 10 Ereignisse pro Tag eintragen, versuch es morgen wieder!"
                            ))
                        } else {
                            call.respond(HttpStatusCode.Forbidden.description("Errör"))
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.Forbidden.description("Invalid arguments"))
                    } catch (e: NullPointerException) {
                        call.respond(HttpStatusCode.Forbidden.description("Null,null"))
                    } catch (e: ReCaptcha.CaptchaException) {
                        call.respondRedirect("/success")
                    }
                }
        }

        get("/imprint") {
            call.respondTwig("imprint")
        }

        route("/mod") {
            get("/") {
                call.respondRedirect("/mod/show/pending/sortby/date")
            }
            get("/show/{status?}/sortby/{order?}") {
                val status = if (call.parameters.contains("status")) call.parameters["status"] else "pending"
                val order = if (call.parameters.contains("order")) call.parameters["order"] else "date"
                call.respondTwig(
                    "mod",
                    mapOf(
                        "list" to Database.list(status!!, order!!),
                        "order" to order,
                        "status" to status
                    )
                )
            }
            route("/edit") {
                post("/status") {
                    if (!call.request.isMultipart())
                        call.respond(HttpStatusCode.Forbidden.description("Oh boy, tryin' to upload different forms?"))
                    else
                        call.receiveMultipart().readAllParts().map {
                            when (it) {
                                is PartData.FormItem -> it.name to it.value
                                else -> {
                                    call.respond(HttpStatusCode.Forbidden.description("Sorry, we only take texts :/"))
                                    return@post
                                }
                            }
                        }.toMap().apply {
                            if (containsKey("id") && containsKey("status") && containsKey("order") && containsKey("state"))
                                try {
                                    Database.changeStatus(get("id")!!.toInt(), get("status")!!)
                                    call.respondRedirect("/mod/show/${get("state")}/sortby/${get("order")}#${get("id")}")
                                } catch (e: IllegalArgumentException) {
                                    call.respond(HttpStatusCode.Forbidden.description("Invalid arguments"))
                                }
                            else call.respond(HttpStatusCode.Forbidden.description("Errör"))
                        }
                }
            }
        }

        static("/static") {
            resources("static")
        }
    }
}
