package io.github.thatsfguy.meshcore.android.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app's text sizes are Material's, so they match every other app on
 * the phone at every system font setting (issue #2). The compacted scale
 * this replaced put body text at 14 sp against Material's 16.
 */
class TypographyTest {

    @Test
    fun `the type scale is material's own`() {
        assertEquals(Typography(), MeshCoreTypography)
    }

    @Test
    fun `body text is not smaller than material's`() {
        // Spelled out as well, so the failure names the number that moved.
        assertEquals(16f, MeshCoreTypography.bodyLarge.fontSize.value, 0f)
        assertEquals(14f, MeshCoreTypography.bodyMedium.fontSize.value, 0f)
        assertEquals(12f, MeshCoreTypography.bodySmall.fontSize.value, 0f)
    }
}
