package com.elmdash.app.phone

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.elmdash.core.protocol.EcuDiagnosticReport
import com.elmdash.core.telemetry.BatteryHealthState
import com.elmdash.core.telemetry.HealthRating
import com.elmdash.core.telemetry.TelemetrySnapshot
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a 2-page A4 EV Battery Health Certificate (PDF) using Android's native PdfDocument
 * (zero third-party dependencies), covering EscoEvObdScanner's 6-point ratings, 108-cell voltage
 * & SoH charts, 54 temperature probes, under-load sag analysis, lifetime kWh counters, and DTCs.
 */
object BatteryReportPdfGenerator {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    fun generate(
        context: Context,
        snapshot: TelemetrySnapshot,
        health: BatteryHealthState,
        dtcReports: List<EcuDiagnosticReport>,
        vinOrNote: String
    ): File {
        val doc = PdfDocument()
        val nowStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            textSize = 9.5f
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 9f
        }
        val bodyBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 23, 42)
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 7.8f
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.rgb(203, 213, 225)
        }

        // ==================== PAGE 1: SUMMARY, 6-POINT RATINGS, LIFETIME & DTCS ====================
        val page1 = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c1 = page1.canvas

        // Header banner
        bgPaint.color = Color.rgb(15, 23, 42)
        c1.drawRect(0f, 0f, PAGE_W.toFloat(), 84f, bgPaint)
        c1.drawText("Corsa-e Dash — HV-Batterie-Zustandsbericht", MARGIN, 34f, titlePaint)
        val vinLine = if (vinOrNote.isNotBlank()) "Fahrzeug / VIN: $vinOrNote  •  " else "Opel Corsa Electric (Stellantis e-CMP, 50 kWh 108s2p)  •  "
        c1.drawText("${vinLine}Erstellt: $nowStr", MARGIN, 52f, subHeaderPaint)
        val meta = health.bmsMetadata
        val metaLine = buildString {
            append("Kilometerstand: ")
            append(snapshot.getValue("odometer")?.let { String.format(Locale.GERMANY, "%,.0f km", it) } ?: "--")
            meta.manufactureDate?.let { append("  •  BMS Baujahr: $it") }
            meta.hardwareVersion?.let { append("  •  HW: $it") }
            meta.softwareVersion?.let { append("  •  SW: $it") }
        }
        c1.drawText(metaLine, MARGIN, 68f, subHeaderPaint)

        var y = 104f

        // KPI Row (4 cards)
        val kpis = listOf(
            "Akku-SoH (BMS)" to (snapshot.getValue("soh")?.let { String.format("%.1f %%", it) } ?: "--"),
            "Zell-Ø SoH (108s)" to (health.cellSohAvgPct?.let { String.format("%.1f %%", it) } ?: "--"),
            "Verschleiß / 10t km" to (health.wearPer10kKmPct?.let { String.format("%.2f %%", it) } ?: "--"),
            "SoC / Energie" to "${snapshot.getValue("soc")?.let { String.format("%.1f%%", it) } ?: "--"} / ${snapshot.getValue("available_energy")?.let { String.format("%.1f kWh", it) } ?: "--"}"
        )
        val cardW = (PAGE_W - 2 * MARGIN - 24f) / 4f
        kpis.forEachIndexed { idx, (label, value) ->
            val left = MARGIN + idx * (cardW + 8f)
            val rect = RectF(left, y, left + cardW, y + 46f)
            bgPaint.color = Color.rgb(248, 250, 252)
            c1.drawRoundRect(rect, 6f, 6f, bgPaint)
            c1.drawRoundRect(rect, 6f, 6f, strokePaint)
            c1.drawText(label, left + 8f, y + 16f, mutedPaint)
            c1.drawText(value, left + 8f, y + 34f, sectionPaint)
        }
        y += 64f

        // Section: 6-Point EscoEvObdScanner Health Checks
        c1.drawText("1. Batterie-Bewertung (6-Punkte-Check)", MARGIN, y, sectionPaint)
        y += 14f

        for (chk in health.checks) {
            val rowRect = RectF(MARGIN, y, PAGE_W - MARGIN, y + 36f)
            bgPaint.color = Color.rgb(248, 250, 252)
            c1.drawRoundRect(rowRect, 5f, 5f, bgPaint)
            c1.drawRoundRect(rowRect, 5f, 5f, strokePaint)

            // Rating pill
            val (badgeColor, badgeText) = when (chk.rating) {
                HealthRating.GOOD -> Color.rgb(16, 185, 129) to "GUT"
                HealthRating.ATTENTION -> Color.rgb(245, 158, 11) to "ACHTUNG"
                HealthRating.CHECK -> Color.rgb(239, 68, 68) to "PRÜFEN"
                HealthRating.NA -> Color.rgb(148, 163, 184) to "N/A"
            }
            bgPaint.color = badgeColor
            c1.drawRoundRect(RectF(PAGE_W - MARGIN - 68f, y + 7f, PAGE_W - MARGIN - 8f, y + 23f), 4f, 4f, bgPaint)
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 7.8f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            c1.drawText(badgeText, PAGE_W - MARGIN - 38f, y + 18f, badgeTextPaint)

            c1.drawText(chk.title, MARGIN + 8f, y + 14f, bodyBoldPaint)
            c1.drawText(chk.valueText, MARGIN + 190f, y + 14f, bodyBoldPaint)
            c1.drawText(chk.basis, MARGIN + 8f, y + 29f, mutedPaint)
            y += 42f
        }

        y += 8f
        c1.drawText("2. Lebensdauer-Zähler & Systemwerte (BMS / VCU)", MARGIN, y, sectionPaint)
        y += 14f

        val totalIn = snapshot.getValue("total_energy_charged")
        val totalOut = snapshot.getValue("total_energy_discharged")
        val lifetimeRows = listOf(
            "Gesamt geladen (Total kWh IN inkl. Reku)" to (totalIn?.let { String.format(Locale.GERMANY, "%,.1f kWh", it) } ?: "--"),
            "Gesamt entladen (Total kWh OUT)" to (totalOut?.let { String.format(Locale.GERMANY, "%,.1f kWh", it) } ?: "--"),
            "Vollzyklen-Äquivalente (Entladen / 46,3 kWh)" to (health.fullCycleEquivalents?.let { String.format("%.1f Zyklen", it) } ?: "--"),
            "HV-Schütz Schaltzyklen (Starts, 0xD416)" to (snapshot.getValue("contactor_closes")?.let { String.format(Locale.GERMANY, "%,.0f ×", it) } ?: "--"),
            "Zeit über 55 °C (0xE082)" to (snapshot.getValue("time_above_55c")?.let { String.format("%.0f s (%.1f min)", it, it / 60.0) } ?: "--"),
            "Pack-Spannung / Zell-Min / Zell-Max" to "${snapshot.getValue("hv_voltage")?.let { String.format("%.1f V", it) } ?: "--"}  |  ${snapshot.getValue("cell_voltage_min")?.let { String.format("%.3f V", it) } ?: "--"} – ${snapshot.getValue("cell_voltage_max")?.let { String.format("%.3f V", it) } ?: "--"}",
            "12V Bordnetz-Spannung (BU-903 Hinweis: in Ruhe prüfen)" to (snapshot.getValue("aux_12v_voltage")?.let { String.format("%.2f V (~%d%%)", it, health.aux12vSocPct ?: 0) } ?: "--")
        )
        for ((k, v) in lifetimeRows) {
            c1.drawText(k, MARGIN + 4f, y + 11f, bodyPaint)
            c1.drawText(v, PAGE_W - MARGIN - 180f, y + 11f, bodyBoldPaint)
            c1.drawLine(MARGIN, y + 16f, PAGE_W - MARGIN, y + 16f, strokePaint)
            y += 19f
        }

        y += 14f
        c1.drawText("3. Steuergeräte-Fehlercodes (UDS 0x19)", MARGIN, y, sectionPaint)
        y += 14f
        if (dtcReports.isEmpty()) {
            c1.drawText("Kein DTC-Scan in dieser Sitzung durchgeführt.", MARGIN + 4f, y + 10f, mutedPaint)
        } else {
            for (rep in dtcReports) {
                val summary = if (!rep.reachable) {
                    "Nicht erreichbar"
                } else if (rep.dtcs.isEmpty()) {
                    "0 Fehler (OK)"
                } else {
                    rep.dtcs.joinToString("; ") { "${it.code}-${it.failureTypeByte} (${if (it.isActive) "AKTIV" else "Gespeichert"}): ${it.description}" }
                }
                c1.drawText("${rep.ecuKey} (${rep.txHeader}): ", MARGIN + 4f, y + 10f, bodyBoldPaint)
                c1.drawText(summary.take(86), MARGIN + 76f, y + 10f, bodyPaint)
                y += 15f
                if (y > PAGE_H - 40f) break
            }
        }

        c1.drawText("Seite 1/2  •  Corsa-e Dash (Stellantis e-CMP UDS)  •  Grenzwerte nach UNECE R100, Midtronics 2025 & EscoEvObdScanner", MARGIN, PAGE_H - 18f, mutedPaint)
        doc.finishPage(page1)

        // ==================== PAGE 2: 108-CELL VOLTAGES, 108-CELL SOH, 54 TEMP PROBES & LOAD SAG ====================
        val page2 = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create())
        val c2 = page2.canvas

        bgPaint.color = Color.rgb(15, 23, 42)
        c2.drawRect(0f, 0f, PAGE_W.toFloat(), 54f, bgPaint)
        c2.drawText("Zell- & Temperatur-Analyse (108s2p CATL Pack)", MARGIN, 33f, titlePaint)

        var y2 = 74f

        // 108 Cell Voltages Chart
        val cells = snapshot.getArray("cell_voltages").orEmpty()
        c2.drawText("4. Alle 108 Zellspannungen (22 D440)", MARGIN, y2, sectionPaint)
        y2 += 12f
        drawBarChart(
            canvas = c2,
            rect = RectF(MARGIN, y2, PAGE_W - MARGIN, y2 + 125f),
            values = cells,
            unit = "V",
            decimals = 3,
            highlightMinMax = true,
            strokePaint = strokePaint,
            mutedPaint = mutedPaint
        )
        y2 += 145f

        // 108 Cell SoH Chart
        val cellSoh = snapshot.getArray("cell_soh").orEmpty()
        c2.drawText("5. Zell-Gesundheit (SoH %) je Zelle (22 D4B5)", MARGIN, y2, sectionPaint)
        y2 += 12f
        drawBarChart(
            canvas = c2,
            rect = RectF(MARGIN, y2, PAGE_W - MARGIN, y2 + 125f),
            values = cellSoh,
            unit = "%",
            decimals = 1,
            highlightMinMax = true,
            strokePaint = strokePaint,
            mutedPaint = mutedPaint
        )
        y2 += 145f

        // 54 Temperature Probes Chart
        val probes = snapshot.getArray("temp_probes").orEmpty()
        c2.drawText("6. Alle 54 Akku-Temperaturfühler (22 D442)", MARGIN, y2, sectionPaint)
        y2 += 12f
        drawBarChart(
            canvas = c2,
            rect = RectF(MARGIN, y2, PAGE_W - MARGIN, y2 + 105f),
            values = probes,
            unit = "°C",
            decimals = 0,
            highlightMinMax = false,
            strokePaint = strokePaint,
            mutedPaint = mutedPaint
        )
        y2 += 126f

        // Under-Load Cell Sag Ranking
        c2.drawText("7. Zellen unter Last — Spannungsabfall pro 100 A (Innenwiderstand)", MARGIN, y2, sectionPaint)
        y2 += 14f
        if (health.cellsUnderLoad.isEmpty()) {
            c2.drawText(
                "Noch keine Last-Messung (erfordert Ruhe-Referenz ≤ 5 A und Fahrt mit ≥ 30 A).",
                MARGIN + 4f,
                y2 + 10f,
                mutedPaint
            )
        } else {
            c2.drawText(
                "Basis: ${health.restSampleCount} Ruhe-Samples, ${health.loadSampleCount} Last-Samples (max. ${String.format("%.0f A", health.maxObservedCurrentA)}), Median: ${String.format("%.1f mV/100 A", health.medianLoadSagMvPer100A ?: 0.0)}",
                MARGIN + 4f,
                y2 + 10f,
                mutedPaint
            )
            y2 += 20f
            health.cellsUnderLoad.take(8).forEachIndexed { idx, entry ->
                val sohTxt = entry.cellSohPct?.let { String.format(" • SoH %.1f%%", it) } ?: ""
                c2.drawText(
                    "#${idx + 1}: Zelle #${entry.cellNumber}  →  ${String.format("%.2f mV / 100 A", entry.sagMvPer100A)}$sohTxt",
                    MARGIN + 8f,
                    y2 + 10f,
                    if (entry.sagMvPer100A >= 15.0) bodyBoldPaint else bodyPaint
                )
                y2 += 15f
            }
        }

        c2.drawText("Seite 2/2  •  Corsa-e Dash  •  Generiert am $nowStr", MARGIN, PAGE_H - 18f, mutedPaint)
        doc.finishPage(page2)

        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
        dir.mkdirs()
        val outFile = File(dir, "CorsaE_Batteriebericht_$stamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    private fun drawBarChart(
        canvas: Canvas,
        rect: RectF,
        values: List<Double>,
        unit: String,
        decimals: Int,
        highlightMinMax: Boolean,
        strokePaint: Paint,
        mutedPaint: Paint
    ) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252) }
        canvas.drawRoundRect(rect, 6f, 6f, bg)
        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)

        if (values.isEmpty()) {
            canvas.drawText("Keine Daten empfangen (Slow-Tier / Multi-Frame noch ausstehend)", rect.left + 12f, rect.centerY(), mutedPaint)
            return
        }

        val minV = values.min()
        val maxV = values.max()
        val avgV = values.average()
        val minIdx = values.indexOf(minV)
        val maxIdx = values.indexOf(maxV)

        val summary = "Min: ${String.format("%.${decimals}f", minV)} $unit (#${minIdx + 1})  •  Ø: ${String.format("%.${decimals}f", avgV)} $unit  •  Max: ${String.format("%.${decimals}f", maxV)} $unit (#${maxIdx + 1})  •  Δ: ${String.format("%.${decimals}f", maxV - minV)} $unit"
        canvas.drawText(summary, rect.left + 10f, rect.top + 14f, mutedPaint)

        val chartTop = rect.top + 22f
        val chartBottom = rect.bottom - 10f
        val chartLeft = rect.left + 10f
        val chartRight = rect.right - 10f
        val chartH = (chartBottom - chartTop).coerceAtLeast(10f)
        val span = (maxV - minV).coerceAtLeast(if (unit == "V") 0.02 else 1.0)
        val lo = minV - span * 0.25
        val hi = maxV + span * 0.10

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val step = (chartRight - chartLeft) / values.size.toFloat()
        val barW = (step * 0.72f).coerceAtLeast(1.2f)

        values.forEachIndexed { idx, v ->
            val norm = ((v - lo) / (hi - lo)).toFloat().coerceIn(0.08f, 1f)
            val top = chartBottom - norm * chartH
            val left = chartLeft + idx * step
            barPaint.color = when {
                highlightMinMax && idx == minIdx -> Color.rgb(245, 158, 11)
                highlightMinMax && idx == maxIdx -> Color.rgb(16, 185, 129)
                else -> Color.rgb(14, 165, 233)
            }
            canvas.drawRect(left, top, left + barW, chartBottom, barPaint)
        }
    }

    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Exportieren / Teilen").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
