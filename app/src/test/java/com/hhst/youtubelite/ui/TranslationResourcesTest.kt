package com.hhst.youtubelite.ui

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TranslationResourcesTest {
    private val resources = File("src/main/res").takeIf(File::isDirectory) ?: File("app/src/main/res")
    private val format = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([a-zA-Z%])")

    private fun elements(file: File, tag: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun everyExistingLocaleContainsTheCompleteMessageCatalog() {
        val expected = elements(File(resources, "values/strings.xml"), "string")
            .filter { it.getAttribute("translatable") != "false" }.map { it.getAttribute("name") }.toSet()
        val expectedPlurals = elements(File(resources, "values/plurals.xml"), "plurals")
            .map { it.getAttribute("name") }.toSet()
        val locales = resources.listFiles()!!.filter { it.name != "values" && File(it, "strings.xml").exists() }
        assertTrue("No translated resources found", locales.isNotEmpty())
        for (directory in locales) {
            val entries = elements(File(directory, "strings.xml"), "string")
            val names = entries.map { it.getAttribute("name") }
            assertEquals("Duplicate keys in ${directory.name}", names.size, names.toSet().size)
            assertEquals("Missing translations in ${directory.name}", emptySet<String>(), expected - names.toSet())
            for (entry in entries) assertFalse("Empty ${directory.name}/${entry.getAttribute("name")}", entry.textContent.isBlank())
            val plurals = elements(File(directory, "plurals.xml"), "plurals")
            val pluralNames = plurals.map { it.getAttribute("name") }
            assertEquals("Duplicate plurals in ${directory.name}", pluralNames.size, pluralNames.toSet().size)
            assertEquals("Incomplete plurals in ${directory.name}", expectedPlurals, pluralNames.toSet())
            for (plural in plurals) {
                val items = plural.getElementsByTagName("item")
                val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
                assertTrue("Missing plural fallback in ${directory.name}", "other" in quantities)
                assertEquals("Duplicate plural quantities in ${directory.name}", quantities.size, quantities.toSet().size)
                for (index in 0 until items.length) {
                    val item = items.item(index) as Element
                    val arguments = format.findAll(item.textContent).map { it.value }.toList()
                    val naturalNumber = item.getAttribute("quantity") in listOf("one", "two") && arguments.isEmpty()
                    assertTrue("Invalid plural argument in ${directory.name}", naturalNumber || arguments == listOf("%1\$d"))
                    String.format(item.textContent, 12)
                }
            }
        }
    }

    @Test fun translatedFormatArgumentsMatchAndRenderInTheirLocale() {
        val base = elements(File(resources, "values/strings.xml"), "string").associateBy { it.getAttribute("name") }
        val locales = resources.listFiles()!!.filter { File(it, "strings.xml").exists() }
        for (directory in locales) {
            val tag = directory.name.removePrefix("values-").removePrefix("b+").replace('+', '-').replace("-r", "-")
            val locale = Locale.forLanguageTag(tag)
            for (entry in elements(File(directory, "strings.xml"), "string")) {
                val key = entry.getAttribute("name")
                val original = base[key] ?: continue
                if (original.getAttribute("formatted") == "false") continue
                val sourceFormats = format.findAll(original.textContent).map { it.value }.toList().groupingBy { it }.eachCount()
                val translatedFormats = format.findAll(entry.textContent).map { it.value }.toList().groupingBy { it }.eachCount()
                assertEquals("Changed arguments in ${directory.name}/$key", sourceFormats, translatedFormats)
                val matches = format.findAll(entry.textContent).filter { it.groupValues[2] !in listOf("%", "n") }.toList()
                if (matches.isEmpty()) continue
                val arguments = mutableMapOf<Int, Any>()
                matches.forEachIndexed { index, match ->
                    val position = match.groupValues[1].toIntOrNull()?.minus(1) ?: index
                    arguments[position] = when (match.groupValues[2].lowercase()) {
                        "d", "o", "x" -> 12
                        "e", "f", "g", "a" -> 1.5
                        "c" -> 'A'
                        "b" -> true
                        else -> "Example"
                    }
                }
                val values = Array(arguments.keys.max() + 1) { arguments[it] ?: "Example" }
                try { String.format(locale, entry.textContent, *values) }
                catch (error: Exception) { throw AssertionError("Cannot format ${directory.name}/$key", error) }
            }
        }
    }
}
