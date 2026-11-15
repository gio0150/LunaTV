package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlipClockSecondsTest {
    @Test fun colonBlinksInTheMiddleAndTheSecondsSwitchHidesIt() {
        var second = 30
        val clock = FlipClockView(RuntimeEnvironment.getApplication()) { second }
        clock.layout(0, 0, 960, 540)
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        fun centerPixels(): Int {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            clock.draw(canvas)
            return (470..490).sumOf { x -> (180..320).count { y -> Color.red(bitmap.getPixel(x, y)) > 200 } }
        }
        try {
            clock.setContents("1215", "Date · Weather", true, 1)
            assertTrue(centerPixels() > 0)
            clock.setContents("1215", "Date · Weather", false, 1)
            assertEquals(0, centerPixels())
            second = 31
            clock.setContents("1215", "Date · Weather", true, 1)
            assertEquals(0, centerPixels())
            clock.setContents("1215", "Date · Weather", true, 0)
            assertEquals(0, centerPixels())
        } finally { bitmap.recycle() }
    }
}
