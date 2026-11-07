package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.GameSettings
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.physics.RigidBox
import com.handgame.prototype.render.HandMeshBuilder
import com.handgame.prototype.render.Loft
import com.handgame.prototype.render.MeshFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Checks the hand meshes are well-formed and writes software-rendered previews to
 * app/build/hand-preview/ (the device renderer can't run on the JVM; this is a sanity view).
 */
class HandMeshPreviewTest {

    private fun poseFor(g: Gesture, grab: Boolean): GameController {
        val game = GameController(GameSettings(), null)
        fun play(sec: Float, hand: () -> com.handgame.prototype.tracking.RawHand?) {
            var s = SyntheticHand.sample(hand())
            for (f in 0 until (sec * 60).toInt()) {
                if (f % 2 == 0) s = SyntheticHand.sample(hand())
                game.update(1f / 60f, s)
            }
        }
        play(2f) { SyntheticHand.hand(Gesture.OPEN) }
        game.skipRangeCalibration()
        play(1.5f) { SyntheticHand.hand(Gesture.OPEN) }
        if (grab) {
            play(0.6f) { SyntheticHand.hand(Gesture.OPEN, u = 0.46f) }
            play(1.0f) { SyntheticHand.hand(g, u = 0.46f, scale = 2.2f) }
        } else {
            play(0.5f) { SyntheticHand.hand(g, u = 0.62f, v = 0.6f) }
        }
        return game
    }

    @Test fun meshesAreFiniteAndClosed() {
        val game = poseFor(Gesture.OPEN, grab = false)
        val b = HandMeshBuilder()
        b.build(game.rig.pose.renderJoints, game.rig.pose.rotation)
        for (loft in listOf(b.body, b.cuff)) {
            assertTrue(loft.positions.all { it.isFinite() })
            assertTrue(loft.normals.all { it.isFinite() })
            assertTrue(loft.indices.all { (it.toInt() and 0xFFFF) < loft.vertexCount })
        }
        // Rebuilding with another pose keeps the same topology.
        val n = b.body.vertexCount
        b.build(poseFor(Gesture.FIST, false).rig.pose.renderJoints, game.rig.pose.rotation)
        assertTrue(n == b.body.vertexCount)
    }

    @Test fun renderPreviews() {
        val dir = File("build/hand-preview").apply { mkdirs() }
        for ((name, g, grab) in listOf(
            Triple("open", Gesture.OPEN, false),
            Triple("fist", Gesture.FIST, false),
            Triple("pinch-hold", Gesture.PINCH, true),
            Triple("fist-hold", Gesture.FIST, true),
        )) {
            val game = poseFor(g, grab)
            val img = Raster(540, 1170, game)
            img.table()
            img.cube(game.cube)
            val b = HandMeshBuilder()
            b.build(game.rig.pose.renderJoints, game.rig.pose.rotation)
            img.loft(b.body, intArrayOf(255, 242, 227), outline = true)
            img.loft(b.cuff, intArrayOf(255, 140, 168), outline = true)
            img.save(File(dir, "$name.png"), game)
        }
    }

    /** Tiny z-buffered triangle rasterizer with Lambert shading. */
    private class Raster(val w: Int, val h: Int, val game: GameController) {
        val image = Img(w, h)
        val depth = FloatArray(w * h) { Float.MAX_VALUE }
        val view = game.view.apply { fit(w.toFloat() / h) }
        val light = Vec3(-0.35f, 1f, 0.45f).normalized()

        init { for (y in 0 until h) for (x in 0 until w) image.setRGB(x, y, rgb(45 + y * 60 / h, 30, 80)) }

        fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        fun screen(p: Vec3): Triple<Float, Float, Float> {
            val (x, y) = view.project(p)
            return Triple((x + 1) / 2 * w, (1 - (y + 1) / 2) * h, (p - view.eye).length())
        }

        fun tri(a: Vec3, b: Vec3, c: Vec3, color: IntArray, n: Vec3?, cullBack: Boolean, cullFront: Boolean, flat: Boolean = false) {
            val (ax, ay, az) = screen(a); val (bx, by, bz) = screen(b); val (cx, cy, cz) = screen(c)
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            // Screen y is flipped, so front faces have negative area here.
            if (cullBack && area >= 0) return
            if (cullFront && area <= 0) return
            val shade = if (flat || n == null) 1f else 0.45f + 0.55f * max(0f, n dot light)
            val col = rgb((color[0] * shade).toInt(), (color[1] * shade).toInt(), (color[2] * shade).toInt())
            val minX = max(0, min(ax, min(bx, cx)).toInt()); val maxX = min(w - 1, max(ax, max(bx, cx)).toInt() + 1)
            val minY = max(0, min(ay, min(by, cy)).toInt()); val maxY = min(h - 1, max(ay, max(by, cy)).toInt() + 1)
            if (area == 0f) return
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
                val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
                val w2 = 1 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = w0 * az + w1 * bz + w2 * cz
                val i = y * w + x
                if (z < depth[i]) { depth[i] = z; image.setRGB(x, y, col) }
            }
        }

        fun mesh(pos: FloatArray, nrm: FloatArray, idx: ShortArray, color: IntArray, offset: Float = 0f, outlinePass: Boolean = false) {
            fun v(i: Int) = Vec3(pos[i * 3] + nrm[i * 3] * offset, pos[i * 3 + 1] + nrm[i * 3 + 1] * offset, pos[i * 3 + 2] + nrm[i * 3 + 2] * offset)
            fun n(i: Int) = Vec3(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2])
            for (t in idx.indices step 3) {
                val a = idx[t].toInt() and 0xFFFF; val b = idx[t + 1].toInt() and 0xFFFF; val c = idx[t + 2].toInt() and 0xFFFF
                val nn = (n(a) + n(b) + n(c)).normalized()
                tri(v(a), v(b), v(c), color, nn, cullBack = !outlinePass, cullFront = outlinePass, flat = outlinePass)
            }
        }

        fun loft(l: Loft, color: IntArray, outline: Boolean) {
            if (outline) mesh(l.positions, l.normals, l.indices, intArrayOf(41, 23, 61), 0.0045f, outlinePass = true)
            mesh(l.positions, l.normals, l.indices, color)
        }

        fun table() {
            val m = MeshFactory.roundedBox(0.5f, 0.04f, 0.9f, 0.035f)
            val p = FloatArray(m.positions.size) { if (it % 3 == 1) m.positions[it] - 0.04f else m.positions[it] }
            mesh(p, m.normals, m.indices, intArrayOf(122, 102, 209))
        }

        fun cube(c: RigidBox) {
            val m = MeshFactory.roundedBox(0.07f, 0.07f, 0.07f, 0.022f)
            val p = FloatArray(m.positions.size); val n = FloatArray(m.normals.size)
            for (i in 0 until m.vertexCount) {
                val wp = c.position + c.orientation.rotate(Vec3(m.positions[i * 3], m.positions[i * 3 + 1], m.positions[i * 3 + 2]))
                val wn = c.orientation.rotate(Vec3(m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2]))
                p[i * 3] = wp.x; p[i * 3 + 1] = wp.y; p[i * 3 + 2] = wp.z
                n[i * 3] = wn.x; n[i * 3 + 1] = wn.y; n[i * 3 + 2] = wn.z
            }
            mesh(p, n, m.indices, intArrayOf(31, 194, 158))
        }

        fun save(f: File, game: GameController) {
            // Crop around the hand for a closer look.
            val c = screen(game.rig.pose.palmCenter)
            val half = 210
            val x0 = (c.first.toInt() - half).coerceIn(0, w - 2 * half)
            val y0 = (c.second.toInt() - half).coerceIn(0, h - 2 * half)
            writePng(f, image, x0, y0, 2 * half, 2 * half)
        }
    }
}

private class Img(val w: Int, val h: Int) {
    val px = IntArray(w * h)
    fun setRGB(x: Int, y: Int, c: Int) { px[y * w + x] = c }
}

/** Minimal PNG writer (RGB, no filtering) so previews work without java.awt. */
private fun writePng(f: File, img: Img, x0: Int, y0: Int, cw: Int, ch: Int) {
    val raw = ByteArrayOutputStream()
    DeflaterOutputStream(raw).use { z ->
        for (y in y0 until y0 + ch) {
            z.write(0)
            for (x in x0 until x0 + cw) {
                val c = img.px[y * img.w + x]
                z.write((c shr 16) and 255); z.write((c shr 8) and 255); z.write(c and 255)
            }
        }
    }
    DataOutputStream(f.outputStream()).use { out ->
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size)
            val t = type.toByteArray()
            out.write(t); out.write(data)
            val crc = CRC32().apply { update(t); update(data) }
            out.writeInt(crc.value.toInt())
        }
        val hdr = ByteArrayOutputStream()
        DataOutputStream(hdr).apply { writeInt(cw); writeInt(ch); write(8); write(2); write(0); write(0); write(0) }
        chunk("IHDR", hdr.toByteArray())
        chunk("IDAT", raw.toByteArray())
        chunk("IEND", ByteArray(0))
    }
}
