package io.github.thatsfguy.meshcore.android.ui.screens

import com.google.zxing.client.android.Intents
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
 *
 * **Correction, 2026-08-12.** The paragraph above was right that
 * inverted codes are half the codes in circulation, and wrong that this
 * app could read them. The hints were set on a decoder factory the
 * library replaced during the same onCreate — so the fix released for
 * that bug never took effect, and a real code held up on a dark-mode
 * screen scanned in every other app on the phone and not in this one.
 * The scan type now travels as an intent extra instead. See below.
 */
fun meshScanOptions(prompt: String): ScanOptions =
    ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(prompt)
        .setBeepEnabled(false)
        // Portrait lock. NOTE: this used to be described as carrying the
        // inverted-decode hints too. It did not — see below.
        .setCaptureActivity(PortraitCaptureActivity::class.java)
        // Read white-on-dark codes. This has to travel as an INTENT
        // EXTRA, which is the only channel the library does not later
        // overwrite.
        //
        // PortraitCaptureActivity.initializeContent() set a decoder
        // factory carrying ALSO_INVERTED and TRY_HARDER, and it was
        // discarded every time. CaptureActivity.onCreate runs
        // initializeContent() FIRST, then
        // CaptureManager.initializeFromIntent() →
        // DecoratedBarcodeView.initializeFromIntent(), which builds a
        // fresh DefaultDecoderFactory from the intent extras and calls
        // setDecoderFactory() with it. Ours was replaced before a single
        // frame was decoded, so the scanner has always been plain
        // dark-on-light — for the whole life of the feature, including
        // the release that claimed to fix exactly this.
        //
        // MIXED_SCAN makes the library build a MixedDecoder, which
        // alternates normal and inverted frames. It is the library's own
        // supported mechanism and it is read from the intent, so nothing
        // downstream can quietly undo it.
        .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
