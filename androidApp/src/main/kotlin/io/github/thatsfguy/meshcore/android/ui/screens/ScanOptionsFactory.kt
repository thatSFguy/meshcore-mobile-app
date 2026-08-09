package io.github.thatsfguy.meshcore.android.ui.screens

import com.journeyapps.barcodescanner.ScanOptions
import io.github.thatsfguy.meshcore.android.platform.PortraitCaptureActivity

/**
 * The scanner options every QR button in this app uses.
 *
 * There were five hand-built copies of this and one of them drifted —
 * the repeater's Radio panel, which opened the scanner sideways in a
 * portrait-locked app. Orientation was the visible half. The invisible
 * half was worse: [PortraitCaptureActivity] is also where
 * `ALSO_INVERTED` and `TRY_HARDER` are set, so that launcher silently
 * could not read a dark-mode QR at all — and MESHCORE_PROTOCOL §11
 * records that roughly half the codes in circulation are inverted,
 * because apps render them on a dark background.
 *
 * A launcher that scans nothing looks identical to a code that is bad.
 * So there is one factory now, and a test asserts every scanner goes
 * through it.
 */
fun meshScanOptions(prompt: String): ScanOptions =
    ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(prompt)
        .setBeepEnabled(false)
        // Carries portrait AND the inverted/try-harder decode hints.
        .setCaptureActivity(PortraitCaptureActivity::class.java)
