package pinkoo.nag

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The plan's mascot parts drawn natively: SVG path data, transforms and colours, and the pixels they make. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MascotTest {
    private fun bounds(d: String): RectF = RectF().also { SvgPath.parse(d).computeBounds(it, true) }

    @Test fun pathDataInEveryForm() {
        assertEquals(RectF(10f, 20f, 30f, 40f), bounds("M10 20 L30 40 Z"))
        // A moveto's further pairs are linetos; relative commands; commas, signs and exponents without spaces.
        assertEquals(RectF(10f, 10f, 20f, 20f), bounds("m10 10 10 0 0 10z"))
        assertEquals(RectF(0f, 0f, 15f, 5f), bounds("M0,0h15v5H0z"))
        assertEquals(RectF(-5f, 0f, 5f, 0.5f), bounds("M-5 0L5 5e-1"))
        assertEquals(RectF(0f, 0f, 3f, 2f), bounds("M0 0l1.5.5 1.5 1.5"))
        // Curves (a path's bounds are its control points'): a smooth cubic reflects the previous control point, a smooth
        // quadratic its own.
        assertEquals(RectF(0f, -10f, 40f, 10f), bounds("M0 0C0 10 20 10 20 0S40 -10 40 0"))
        assertEquals(RectF(0f, -10f, 40f, 10f), bounds("M0 0Q10 10 20 0T40 0"))
        // A half-circle arc of radius 10: 20 wide, 10 tall.
        val arc = bounds("M0 0A10 10 0 0 1 20 0")
        assertEquals(20f, arc.width(), 0.05f)
        assertEquals(10f, arc.height(), 0.05f)
        // A radius too small for the span is scaled up, as SVG says.
        assertEquals(10f, bounds("M0 0A1 1 0 0 0 20 0").height(), 0.05f)
        // Malformed data draws what came before the fault.
        assertEquals(RectF(0f, 0f, 10f, 10f), bounds("M0 0L10 10L5"))
    }

    @Test fun transformsAndColours() {
        val m = Transforms.parse("translate(10 20) rotate(90) scale(2)")
        val pts = floatArrayOf(1f, 0f)
        m.mapPoints(pts)
        assertEquals(10f, pts[0], 0.001f)
        assertEquals(22f, pts[1], 0.001f)
        assertEquals(2f, Transforms.scaleOf(m), 0.001f)
        val r = Transforms.parse("rotate(180, 50, 50)")
        val q = floatArrayOf(40f, 50f).also(r::mapPoints)
        assertEquals(60f, q[0], 0.001f)
        assertTrue(Matrix().apply { setScale(1f, 1f) } == Transforms.parse(""))
        assertEquals(Color.parseColor("#FF7A59"), Colors.parse("#FF7A59"))
        assertEquals(Color.parseColor("#FFFFFF"), Colors.parse("#fff"))
        assertNull(Colors.parse("coral"))
    }

    @Test fun theMascotIsDrawnFittedAndInOrder() {
        val parts = JSONArray()
            .put(JSONObject().put("t", "circle").put("cx", 100).put("cy", 100).put("r", 80).put("fill", "#FF7A59"))
            .put(JSONObject().put("t", "rect").put("x", 90).put("y", 90).put("w", 20).put("h", 20).put("fill", "#2B2233"))
            .put(JSONObject().put("t", "ellipse").put("cx", 100).put("cy", 40).put("rx", 10).put("ry", 5).put("fill", JSONObject.NULL)
                .put("stroke", "#FFFFFF").put("sw", 2).put("op", 0.5))
        val m = Mascot.parse(JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200))).put("parts", parts))
        assertEquals(3, m.parts.size)
        val bm = MascotDrawable(m).bitmap(100)
        assertEquals("the later part is on top", Color.parseColor("#2B2233"), bm.getPixel(50, 50))
        assertEquals(Color.parseColor("#FF7A59"), bm.getPixel(50, 80))
        assertEquals("outside the body: nothing", 0, Color.alpha(bm.getPixel(2, 2)))
    }
}
