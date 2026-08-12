package io.github.thatsfguy.meshcore.android.platform

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * QR-scan capture activity pinned to portrait.
 *
 * zxing-android-embedded's stock [CaptureActivity] follows the sensor:
 * `ScanOptions.setOrientationLocked(true)` only re-locks to whatever
 * orientation the device happened to be in *at launch*, so holding the
 * phone in landscape (or scanning right after a landscape app) opened
 * the scanner sideways. Forcing `screenOrientation="portrait"` on this
 * subclass in the manifest makes the camera preview always upright —
 * the Android counterpart of the iOS QR-scanner portrait lock
 * (docs/REDESIGN.md §10).
 *
 * ## What this class used to do, and could not
 *
 * It also overrode `initializeContent()` to install a decoder factory
 * carrying `ALSO_INVERTED` and `TRY_HARDER`, so the app could read the
 * white-on-dark codes that other MeshCore clients render. That override
 * never had any effect. `CaptureActivity.onCreate` runs
 * `initializeContent()` first and *then*
 * `CaptureManager.initializeFromIntent()`, which reaches
 * `DecoratedBarcodeView.initializeFromIntent()` — and that builds a
 * fresh `DefaultDecoderFactory` from the intent extras and calls
 * `setDecoderFactory()` with it. Ours was replaced before a single
 * frame was decoded.
 *
 * It went unnoticed because the test asserted this file *contained the
 * string* `ALSO_INVERTED`. It did. The scanner still could not read a
 * single inverted code, and 0.7.11 shipped claiming to have fixed
 * exactly that.
 *
 * The scan type is now an intent extra (`meshScanOptions`), which is the
 * one channel the library does not overwrite, and the assertion is on
 * the value rather than on the source text. The override is gone rather
 * than left in place as belt-and-braces: code that looks like it
 * configures the decoder, and doesn't, is what caused this.
 */
class PortraitCaptureActivity : CaptureActivity()
