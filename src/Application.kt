package de.moeri

import ch.qos.logback.core.util.FileUtil
import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.config.ApplicationConfig
import io.ktor.features.CallLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.*
import io.ktor.request.isMultipart
import io.ktor.request.path
import io.ktor.request.receiveMultipart
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jtwig.JtwigModel
import org.jtwig.JtwigTemplate
import org.slf4j.event.Level
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.sql.Date
import javax.imageio.ImageIO


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
            call.respondRedirect("/submit")
        }

        route("/submit") {
            get("/") {
                call.respondTwig("submit", mapOf("date" to "Wie wär's mit dem 1. August?"))
            }
            post("/") {
                if (!call.request.isMultipart())
                    call.respond(HttpStatusCode.Forbidden.description("Oh boy, tryin' to upload different forms?"))
                else
                    call.receiveMultipart().readAllParts().map {
                        when (it) {
                            is PartData.FormItem -> {
                                it.name to it.value
                            }
                            is PartData.FileItem -> {
                                val parent = File("data")
                                if (!parent.exists())
                                    parent.mkdir()
                                val folder = File(parent,"images")
                                if (!folder.exists())
                                    folder.mkdir()
                                val name = "${System.currentTimeMillis().toString().sha1()}.${File(it.originalFileName!!).extension}"
                                val file = File(folder, name)
                                file.createNewFile()
                                it.streamProvider().use { its ->

                                    try {
                                        ImageIO.read(its) != null
                                    } catch (e: java.lang.Exception) {
                                        call.respond(HttpStatusCode.Forbidden.description("Only pictures! :angry:"))
                                    }

                                    file.outputStream().buffered().use { s ->
                                        its.copyTo(s)
                                    }
                                }
                                "picture" to name
                            }
                            else -> {
                                call.respond(HttpStatusCode.Forbidden.description("Sorry, we only take pictures or texts :/"))
                                return@post
                            }
                        }
                    }.toMap().apply {
                        try {
                            if (containsKey("g-recaptcha-response")) {
                                ReCaptcha.recaptchaValidate(get("g-recaptcha-response")!!)
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
                                    picture = if(containsKey("picture")) get("picture")!! else ""
                                )
                                Database.insert(entry)
                                call.respondRedirect("/success")
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
        }

        get("/success") {
            call.respondTwig("success")
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
                                    call.respond(HttpStatusCode.Forbidden)
                                    return@post
                                }
                            }
                        }.toMap().apply {
                            if (containsKey("id") && containsKey("status") && containsKey("order") && containsKey("state"))
                                try {
                                    Database.changeStatus(get("id")!!.toInt(), get("status")!!)
                                    call.respondRedirect("/mod/show/${get("state")}/sortby/${get("order")}")
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
