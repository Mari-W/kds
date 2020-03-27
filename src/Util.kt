package de.moeri

import io.ktor.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.request.accept
import io.ktor.request.userAgent
import io.ktor.response.respondText
import org.jtwig.JtwigModel
import org.jtwig.JtwigTemplate

fun Int.toBoolean(): Boolean {
    return this == 1
}

inline fun <reified K, V> Map<K, V>.containsKeys(vararg keys: K): Boolean {
    for (key in keys) {
        if(!containsKey(key))
            return false
    }
    return true
}

private val templates = mutableMapOf<String, JtwigTemplate>()

suspend fun ApplicationCall.respondTwig(template: String, model: Map<String, Any> = mapOf(), mobile: Boolean = true) {
    var template = template
    if (mobile && request.userAgent() != null && request.accept() != null && UAgentInfo(
            request.userAgent()!!,
            request.accept()!!
        ).detectMobileQuick()
    ) {
        template = "${template}_mobile"
    }
    if (!templates.containsKey(template))
        templates[template] = JtwigTemplate.classpathTemplate("template/$template.twig")
    val twigModel = JtwigModel.newModel()
    model.forEach { (key, value) ->
        twigModel.with(key, value)
    }
    twigModel.with("template", template)
    respondText(
        templates[template]!!.render(twigModel),
        ContentType.Text.Html
    )
}
