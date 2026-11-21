package dev.exact.canvas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import java.io.File
import java.nio.IntBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * pinkoo (C8): exact2 tells a face (op 10, 41) where it first draws it. A face first drawn in a row's layer is told in
 * that layer's set, which follows the row, and a text the row draws itself after the layer (a positioned one, painted
 * with the layers in tree order) uses it before it is told. A reader made again (an activity made again) learns every
 * face from one stream, so its rows lost those texts until they were recorded again: Pinkoo's Today lost every second
 * line, the faces its tags first drew, at each return to the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowFacesTest {
    private fun f(n: Float) = n.toRawBits()
    private val identity = intArrayOf(1, f(1f), 0, 0, f(1f), 0, 0)
    private val header = intArrayOf(f(1f), 0, 0, f(1f), 0, 0, 0, 0, 0, 0, f(1f), 0, f(1f), 0, 0, -1)
    private val path = File("../../painted/tests/fonts/Inter-Regular.ttf").canonicalPath

    private fun face(key: Int): IntArray {
        val bytes = path.toByteArray()
        val words = IntArray((bytes.size + 3) / 4)
        for (i in bytes.indices) words[i / 4] = words[i / 4] or ((bytes[i].toInt() and 255) shl (8 * (i % 4)))
        return intArrayOf(10, key, 0, 400, bytes.size) + words
    }

    /** Three black glyphs of face [key] at 40 px, from ([x], 50). */
    private fun glyphs(key: Int, x: Float) = intArrayOf(9, key, f(40f), Color.BLACK, 0, 3,
        36, f(x), f(50f), 37, f(x + 25f), f(50f), 38, f(x + 50f), f(50f))
    private fun row(id: Int, ops: IntArray) = intArrayOf(15, id, 0, f(200f), f(64f), ops.size + 1) + ops + intArrayOf(16)
    private fun layer(id: Int, ops: IntArray) = (header + ops).let { intArrayOf(30, id, it.size) + it }
    private fun slot(id: Int, ops: IntArray) = intArrayOf(24, id, ops.size) + ops
    private fun draw(id: Int) = intArrayOf(17, id, 0, 0)

    /** A reader made as an activity's is, its rows made as their stream arrives. */
    private class Reader {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        init { activity.intent.putExtra("async", "0") }
        val view = ExactView(activity, false) {}
        private val apply = ExactView::class.java.getDeclaredMethod("applyStream", IntBuffer::class.java).apply { isAccessible = true }
        private val player = ExactView::class.java.getDeclaredField("main").apply { isAccessible = true }.get(view) as Player
        init { player.width = 200; player.height = 64 }

        /** The dark pixels left of x 100 and right of it, once [ops] is applied and drawn on a white page. */
        fun ink(ops: IntArray): Pair<Int, Int> {
            apply.invoke(view, IntBuffer.wrap(ops))
            val page = RenderNode("page").apply { setPosition(0, 0, 200, 64) }
            val canvas = page.beginRecording()
            canvas.drawColor(Color.WHITE)
            player.replay(canvas, IntBuffer.wrap(ops), 0, ops.size)
            page.endRecording()
            val bitmap = HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
                RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, page, 200, 64) as Bitmap
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            var left = 0; var right = 0
            for (y in 0 until 64) for (x in 0 until 200) {
                if (Color.red(copy.getPixel(x, y)) < 128) { if (x < 100) left++ else right++ }
            }
            copy.recycle(); bitmap.recycle()
            return left to right
        }
    }

    @Test fun aRowDrawsTheFaceItsLayerTellsAfterIt() {
        // Row 1 draws its layer (a tag rising in, at x 10), then its own text (x 110) in the face the tag first drew.
        val (tag, text) = Reader().ink(row(1, intArrayOf(29, 7) + identity + glyphs(1, 110f)) +
            layer(7, identity + face(1) + glyphs(1, 10f)) + draw(1))
        assertTrue("the layer draws its text", tag > 0)
        assertTrue("the row draws its own text in the face its layer told after it", text > 0)
    }

    @Test fun aRowDrawsTheFaceItsSlotTellsAfterIt() {
        val (_, text) = Reader().ink(row(1, intArrayOf(23, 5) + identity + glyphs(1, 110f)) +
            slot(5, identity + face(1) + glyphs(1, 10f)) + draw(1))
        assertTrue("the row draws its own text in the face its slot told after it", text > 0)
    }

    @Test fun anInnerLayerDrawsTheFaceItsOuterLayerTellsAfterIt() {
        // Layer 8 is drawn inside layer 7 and closes first, so its set comes first; the face is in layer 7's.
        val (outer, inner) = Reader().ink(row(1, intArrayOf(29, 7)) + layer(8, identity + glyphs(1, 110f)) +
            layer(7, identity + face(1) + glyphs(1, 10f) + intArrayOf(29, 8)) + draw(1))
        assertTrue("the outer layer draws its text", outer > 0)
        assertTrue("the inner layer draws its text in the face the outer told after it", inner > 0)
    }

    @Test fun anInnerLayerOutsideRowsDrawsTheFaceItsOuterLayerTellsAfterIt() {
        val (outer, inner) = Reader().ink(intArrayOf(29, 7) + layer(8, identity + glyphs(1, 110f)) +
            layer(7, identity + face(1) + glyphs(1, 10f) + intArrayOf(29, 8)))
        assertTrue("the outer layer draws its text", outer > 0)
        assertTrue("the inner layer draws its text in the face the outer told after it", inner > 0)
    }

    @Test fun aRowStillDrawsAFaceToldBeforeIt() {
        val (first, second) = Reader().ink(row(1, identity + face(1) + glyphs(1, 10f)) + draw(1) +
            row(2, identity + glyphs(1, 110f)) + draw(2))
        assertTrue(first > 0)
        assertTrue(second > 0)
    }
}
