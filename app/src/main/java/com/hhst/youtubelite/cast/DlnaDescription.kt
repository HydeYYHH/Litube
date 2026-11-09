package com.hhst.youtubelite.cast

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element

internal data class DlnaRenderer(val location: HttpUrl, val name: String, val serviceType: String, val control: HttpUrl,
    val model: String = "")

internal object DlnaDescription {
    fun parse(bytes: ByteArray, location: HttpUrl): DlnaRenderer? {
        val root = document(bytes)
        val base = root.child("URLBase")?.textContent?.trim()?.takeIf(String::isNotEmpty)
            ?.let(location::resolve) ?: location
        val devices = root.getElementsByTagNameNS("*", "device")
        for (i in 0 until devices.length) {
            val device = devices.item(i) as? Element ?: continue
            val services = device.child("serviceList")?.children("service").orEmpty()
            val transport = services.firstOrNull {
                it.value("serviceType").startsWith("urn:schemas-upnp-org:service:AVTransport:", true)
            } ?: continue
            val control = base.resolve(transport.value("controlURL")) ?: continue
            if (transport.value("controlURL").isBlank() || control.username.isNotEmpty() || control.password.isNotEmpty()) continue
            val name = device.value("friendlyName").ifBlank { device.value("modelName") }.ifBlank { location.host }
            return DlnaRenderer(location, name, transport.value("serviceType"), control, device.value("modelName"))
        }
        return null
    }

    fun values(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val nodes = document(bytes).getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            if (element.firstElementChild() == null) result[element.localName ?: element.tagName] = element.textContent.trim()
        }
        return result
    }

    private fun document(bytes: ByteArray): Element {
        require(bytes.size <= MAX_BYTES) { "Device response exceeds size limit" }
        val text = bytes.toString(Charsets.UTF_8)
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            throw IOException("DTD declarations are not allowed")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IOException("External entities are not allowed") }
        }.parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.children(name: String): List<Element> = buildList {
        for (i in 0 until childNodes.length) {
            val element = childNodes.item(i) as? Element ?: continue
            if ((element.localName ?: element.tagName) == name) add(element)
        }
    }
    private fun Element.child(name: String) = children(name).firstOrNull()
    private fun Element.value(name: String) = child(name)?.textContent?.trim().orEmpty()
    private fun Element.firstElementChild(): Element? {
        for (i in 0 until childNodes.length) (childNodes.item(i) as? Element)?.let { return it }
        return null
    }

    fun location(value: String) = value.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
    const val MAX_BYTES = 512 * 1024
}
