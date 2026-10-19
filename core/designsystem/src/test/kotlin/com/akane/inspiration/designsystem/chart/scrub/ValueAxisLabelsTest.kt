package com.akane.inspiration.designsystem.chart.scrub

import com.akane.inspiration.designsystem.component.format.MINUS_SIGN
import com.akane.inspiration.designsystem.component.format.NBSP
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.TelemetryUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** The chart's value-axis and readout texts are worded like every other reading: locale percent placement, U+2212. */
class ValueAxisLabelsTest {
    private val english = TelemetryFormat(Locale.ENGLISH, TelemetryUnits.English)
    private val turkish = TelemetryFormat(Locale.forLanguageTag("tr"), TelemetryUnits.English)

    @Test
    fun `temperature axis labels use the typographic minus and keep number and unit together`() {
        assertEquals(listOf("${MINUS_SIGN}5$NBSP°C", "0$NBSP°C", "5$NBSP°C"), temperatureAxisLabels(ValueAxis(-5f, 5f), english))
        assertEquals(listOf("25$NBSP°C", "35$NBSP°C", "45$NBSP°C"), temperatureAxisLabels(ValueAxis(25f, 45f), turkish))
        assertTrue(temperatureAxisLabels(null, english).isEmpty())
    }

    @Test
    fun `battery axis labels place the percent sign per locale`() {
        val en = chartString("values", "chart_axis_percent")
        val tr = chartString("values-tr", "chart_axis_percent")

        assertEquals(
            listOf("0%", "50%", "100%"),
            BATTERY_AXIS_PERCENTS.map { String.format(Locale.ENGLISH, en, english.number(it.toDouble())) },
        )
        assertEquals(
            listOf("%0", "%50", "%100"),
            BATTERY_AXIS_PERCENTS.map {
                String.format(Locale.forLanguageTag("tr"), tr, turkish.number(it.toDouble()))
            },
        )
    }

    @Test
    fun `the readout temperature takes the TelemetryFormat text so a sub-zero reading keeps the typographic minus`() {
        val reading = english.celsius(-3.25, decimals = 1).text
        assertEquals(
            "Temperature ${MINUS_SIGN}3.3$NBSP°C",
            String.format(Locale.ENGLISH, chartString("values", "chart_readout_temperature"), reading),
        )
        assertEquals(
            "Sıcaklık 41,5$NBSP°C",
            String.format(
                Locale.forLanguageTag("tr"),
                chartString("values-tr", "chart_readout_temperature"),
                turkish.celsius(41.5, 1).text,
            ),
        )
    }

    /** The raw resource text, as `Resources.getString(id, args)` hands it to `String.format`. */
    private fun chartString(
        dir: String,
        name: String,
    ): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings_chart.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}
