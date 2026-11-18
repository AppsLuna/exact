// The mascot as the plan carries it (`NagMascot`): the same geometry the app draws in Contract, flattened by the data
// module into an ordered list of SVG shapes, each a path, circle, ellipse or rect with a fill, a stroke and a transform
// of translate, rotate and scale. Drawn with android.graphics for the bubble and the notification's picture, where no
// Exact view runs.
package pinkoo.nag

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class Part(
    val t: String, val d: String?, val cx: Float, val cy: Float, val r: Float, val rx: Float, val ry: Float,
    val x: Float, val y: Float, val w: Float, val h: Float, val fill: Int?, val stroke: Int?, val sw: Float, val op: Float,
    val cap: Paint.Cap, val join: Paint.Join, val tf: Matrix?,
) {
    /** The part's outline in the mascot's own space, its transform applied. */
    val path: Path by lazy {
        val p = when (t) {
            "path" -> SvgPath.parse(d ?: "")
            "circle" -> Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            "ellipse" -> Path().apply { addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }
            "rect" -> Path().apply {
                if (rx > 0 || ry > 0) addRoundRect(RectF(x, y, x + w, y + h), rx.takeIf { it > 0 } ?: ry, ry.takeIf { it > 0 } ?: rx, Path.Direction.CW)
                else addRect(RectF(x, y, x + w, y + h), Path.Direction.CW)
            }
            else -> Path()
        }
        tf?.let { p.transform(it) }
        p
    }

    companion object {
        fun parse(o: JSONObject): Part {
            fun f(k: String) = if (o.isNull(k)) 0f else o.optDouble(k, 0.0).toFloat()
            fun color(k: String): Int? = if (o.isNull(k)) null else Colors.parse(o.optString(k))
            return Part(o.optString("t"), if (o.isNull("d")) null else o.optString("d"), f("cx"), f("cy"), f("r"), f("rx"), f("ry"),
                f("x"), f("y"), f("w"), f("h"), color("fill"), color("stroke"), if (o.isNull("sw")) 1f else f("sw"),
                if (o.isNull("op")) 1f else f("op").coerceIn(0f, 1f),
                when (o.optString("cap")) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT },
                when (o.optString("join")) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER },
                if (o.isNull("tf")) null else Transforms.parse(o.optString("tf")))
        }
    }
}

internal class Mascot(val box: FloatArray, val parts: List<Part>) {
    companion object {
        fun parse(o: JSONObject): Mascot {
            val vb = o.optJSONArray("viewBox")
            val box = FloatArray(4) { i -> (vb?.optDouble(i, if (i < 2) 0.0 else 200.0) ?: (if (i < 2) 0.0 else 200.0)).toFloat() }
            val a = o.optJSONArray("parts")
            return Mascot(box, (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.let(Part::parse) })
        }
    }
}

/** The mascot fitted into its bounds, keeping its aspect (SVG's `xMidYMid meet`). [tilt] turns it about its centre, in
 * degrees, and [squash] (0..1) flattens it on its base: the bubble's wiggle and hop. */
internal class MascotDrawable(private val mascot: Mascot) : Drawable() {
    var tilt = 0f
        set(v) { field = v; invalidateSelf() }
    var squash = 0f
        set(v) { field = v; invalidateSelf() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val (bx, by, bw, bh) = mascot.box.let { listOf(it[0], it[1], it[2], it[3]) }
        if (bw <= 0 || bh <= 0) return
        val scale = minOf(b.width() / bw, b.height() / bh)
        canvas.save()
        canvas.translate(b.left + (b.width() - bw * scale) / 2, b.top + (b.height() - bh * scale) / 2)
        canvas.scale(scale, scale)
        canvas.translate(-bx, -by)
        canvas.rotate(tilt, bx + bw / 2, by + bh / 2)
        if (squash > 0f) canvas.scale(1f + squash * 0.04f, 1f - squash * 0.08f, bx + bw / 2, by + bh)
        for (p in mascot.parts) {
            val alpha = (p.op * 255).toInt()
            p.fill?.let {
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = it
                paint.alpha = (paint.alpha * alpha) / 255
                canvas.drawPath(p.path, paint)
            }
            p.stroke?.let {
                paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE; paint.color = it
                paint.alpha = (paint.alpha * alpha) / 255
                paint.strokeWidth = p.sw * (p.tf?.let { m -> Transforms.scaleOf(m) } ?: 1f)
                paint.strokeCap = p.cap; paint.strokeJoin = p.join
                canvas.drawPath(p.path, paint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** A [size]-pixel square picture of the mascot: the notification's large icon. */
    fun bitmap(size: Int): Bitmap {
        val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, size, size)
        draw(Canvas(bm))
        return bm
    }

    companion object {
        fun of(plan: Plan?, rung: Int): MascotDrawable? = plan?.mascot(rung)?.takeIf { it.parts.isNotEmpty() }?.let(::MascotDrawable)
    }
}

internal object Colors {
    /** `#RRGGBB`, `#RGB` or `#RRGGBBAA`; anything else draws nothing. */
    fun parse(s: String): Int? {
        val h = s.trim().removePrefix("#")
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> (0xFF000000 or v).toInt()
            3 -> {
                val r = (v shr 8) and 0xF; val g = (v shr 4) and 0xF; val b = v and 0xF
                (0xFF000000 or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)).toInt()
            }
            8 -> (((v and 0xFF) shl 24) or (v shr 8)).toInt()
            else -> null
        }
    }
}

/** An SVG transform list of `translate`, `rotate` (with an optional centre) and `scale`, applied right to left as SVG
 * applies them. */
internal object Transforms {
    fun parse(s: String): Matrix {
        val m = Matrix()
        for (match in Regex("""(translate|rotate|scale)\s*\(([^)]*)\)""").findAll(s)) {
            val a = match.groupValues[2].split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }.map { it.toFloat() }
            val step = Matrix()
            when (match.groupValues[1]) {
                "translate" -> step.setTranslate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                "rotate" -> if (a.size >= 3) step.setRotate(a[0], a[1], a[2]) else step.setRotate(a.getOrElse(0) { 0f })
                "scale" -> step.setScale(a.getOrElse(0) { 1f }, a.getOrElse(1) { a.getOrElse(0) { 1f } })
            }
            m.preConcat(step)
        }
        return m
    }

    /** The mean scale of [m], which a stroke's width follows. */
    fun scaleOf(m: Matrix): Float {
        val v = FloatArray(9).also(m::getValues)
        return sqrt(abs(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y]))
    }
}

/** SVG path data to a [Path]: every command, absolute and relative, with implicit repeats, and arcs as cubic curves. */
internal object SvgPath {
    /** Malformed data draws what came before the fault, as a browser does. */
    fun parse(d: String): Path {
        val p = Path()
        try { parseInto(p, d) } catch (e: RuntimeException) { /* the path so far */ }
        return p
    }

    private fun parseInto(p: Path, d: String) {
        val tokens = Regex("""[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = ' '
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var cx2 = 0f; var cy2 = 0f; var lastCubic = false; var lastQuad = false
        fun more() = i < tokens.size && !tokens[i][0].isLetter()
        fun n(): Float = tokens[i++].toFloat()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) cmd = tokens[i++][0] else if (cmd == ' ') { i++; continue }
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
            var cubic = false; var quad = false
            when (cmd.uppercaseChar()) {
                'M' -> {
                    x = ox + n(); y = oy + n(); p.moveTo(x, y); sx = x; sy = y
                    // Further pairs after a moveto are linetos.
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { x = ox + n(); y = oy + n(); p.lineTo(x, y) }
                'H' -> { x = ox + n(); p.lineTo(x, y) }
                'V' -> { y = (if (rel) y else 0f) + n(); p.lineTo(x, y) }
                'C' -> {
                    val x1 = ox + n(); val y1 = oy + n(); val x2 = ox + n(); val y2 = oy + n(); val ex = ox + n(); val ey = oy + n()
                    p.cubicTo(x1, y1, x2, y2, ex, ey); cx2 = x2; cy2 = y2; x = ex; y = ey; cubic = true
                }
                'S' -> {
                    val x1 = if (lastCubic) 2 * x - cx2 else x; val y1 = if (lastCubic) 2 * y - cy2 else y
                    val x2 = ox + n(); val y2 = oy + n(); val ex = ox + n(); val ey = oy + n()
                    p.cubicTo(x1, y1, x2, y2, ex, ey); cx2 = x2; cy2 = y2; x = ex; y = ey; cubic = true
                }
                'Q' -> {
                    val x1 = ox + n(); val y1 = oy + n(); val ex = ox + n(); val ey = oy + n()
                    p.quadTo(x1, y1, ex, ey); cx2 = x1; cy2 = y1; x = ex; y = ey; quad = true
                }
                'T' -> {
                    val x1 = if (lastQuad) 2 * x - cx2 else x; val y1 = if (lastQuad) 2 * y - cy2 else y
                    val ex = ox + n(); val ey = oy + n()
                    p.quadTo(x1, y1, ex, ey); cx2 = x1; cy2 = y1; x = ex; y = ey; quad = true
                }
                'A' -> {
                    val rx = n(); val ry = n(); val rot = n(); val large = n() != 0f; val sweep = n() != 0f
                    val ex = ox + n(); val ey = oy + n()
                    arc(p, x, y, rx, ry, rot, large, sweep, ex, ey); x = ex; y = ey
                }
                'Z' -> { p.close(); x = sx; y = sy }
                else -> i++
            }
            lastCubic = cubic; lastQuad = quad
            // Numbers after a closepath belong to no command.
            if (cmd.uppercaseChar() == 'Z' && more()) cmd = ' '
        }
    }

    /** An elliptical arc (SVG 1.1 implementation notes, F.6.5) as cubic curves of at most a quarter turn each. */
    private fun arc(p: Path, x1: Float, y1: Float, rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x2, y2); return }
        val phi = Math.toRadians(angle.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = sqrt(maxOf(0.0, num / den))
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry; val cyp = -co * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI else if (sweep && dt < 0) dt += 2 * PI
        val segs = ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / segs
        val k = 4.0 / 3.0 * kotlin.math.tan(step / 4)
        var t = t1
        for (s in 0 until segs) {
            val c1 = cos(t); val s1 = sin(t); val c2 = cos(t + step); val s2 = sin(t + step)
            fun px(ex: Double, ey: Double) = (cx + rx * ex * cp - ry * ey * sp).toFloat()
            fun py(ex: Double, ey: Double) = (cy + rx * ex * sp + ry * ey * cp).toFloat()
            p.cubicTo(px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1),
                px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2), px(c2, s2), py(c2, s2))
            t += step
        }
    }
}
