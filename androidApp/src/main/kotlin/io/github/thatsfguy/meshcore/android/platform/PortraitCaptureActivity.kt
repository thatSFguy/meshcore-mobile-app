package io.github.thatsfguy.meshcore.android.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * QR-scan capture activity pinned to portrait, and able to read inverted
 * codes.
 *
 * **Portrait:** zxing-android-embedded's stock [CaptureActivity] follows
 * the sensor: `ScanOptions.setOrientationLocked(true)` only re-locks to
 * whatever orientation the device happened to be in *at launch*, so
 * holding the phone in landscape (or scanning right after a landscape
 * app) opened the scanner sideways. Forcing `screenOrientation="portrait"`
 * on this subclass in the manifest makes the camera preview always
 * upright — the Android counterpart of the iOS QR-scanner portrait lock
 * (docs/REDESIGN.md §10).
 *
 * **Inverted codes:** the QR spec assumes dark modules on a light
 * background and ZXing honours that literally. MeshCore apps in dark mode
 * render the opposite — white modules on near-black — which the stock
 * decoder silently fails to see. `ALSO_INVERTED` makes ZXing retry each
 * frame with the luminance flipped, so a contact QR held up on someone's
 * dark-mode screen scans instead of hanging on a blank viewfinder.
 */
class PortraitCaptureActivity : CaptureActivity() {

    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        view.barcodeView.decoderFactory = DefaultDecoderFactory(
            listOf(BarcodeFormat.QR_CODE),
            mapOf(
                DecodeHintType.ALSO_INVERTED to true,
                DecodeHintType.TRY_HARDER to true,
            ),
            null,
            0,
        )
        return view
    }
}
