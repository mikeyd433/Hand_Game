package com.handgame.prototype

import com.handgame.prototype.game.GameController
import com.handgame.prototype.game.HandRig
import com.handgame.prototype.game.PlaySpace
import com.handgame.prototype.input.Gesture
import com.handgame.prototype.math.Vec3
import com.handgame.prototype.tracking.Lm

/**
 * A simulated player for tests: steers a synthetic hand so a chosen point (pinch point, palm,
 * index tip) sits over a world position, the way a person watching the screen would, by inverting
 * the game's screen-wide mapping. Front camera.
 */
class SimPlayer(val game: GameController) {
    var u = 0.5f; var v = 0.5f
    var gesture = Gesture.OPEN
    var pointing = false
    /** Image scale of the hand: bigger = closer to the camera (front camera: lower / dip). */
    var scale = 2.5f
    var maxSpeed = 0.6f
    private var sample = SyntheticHand.sample(SyntheticHand.hand(Gesture.OPEN))
    private var frame = 0

    enum class Point { PINCH, PALM, INDEX_TIP }

    fun aim(x: Float, z: Float, point: Point, dt: Float): Boolean {
        val pose = game.rig.pose
        val p = when (point) { Point.PINCH -> pose.pinchPoint; Point.PALM -> pose.palmCenter; Point.INDEX_TIP -> pose.joints[Lm.INDEX_TIP] }
        val offset = p - pose.palmCenter
        val target = Vec3(x - offset.x, PlaySpace.HOVER_HEIGHT, z - offset.z)
        val (ndcX, ndcY) = game.view.project(target)
        val nx = (ndcX / HandRig.SCREEN_REACH_X).coerceIn(-1f, 1f)
        val ny = (if (ndcY > 0f) -ndcY / HandRig.SCREEN_REACH_TOP else -ndcY / HandRig.SCREEN_REACH_BOTTOM).coerceIn(-1f, 1f)
        val box = game.rig.playBox(game.calibration, 480, 640, game.settings.sensitivity)
        val wantU = 1f - (box[0] + nx * box[1])
        val wantV = box[2] + ny * box[3]
        val step = maxSpeed * dt
        u += (wantU - u).coerceIn(-step, step)
        v += (wantV - v).coerceIn(-step, step)
        return kotlin.math.hypot(p.x - x, p.z - z) < 0.025f
    }

    /** Advances one 60 fps frame (tracking at 30 fps). */
    fun tick(dt: Float = 1f / 60f) {
        if (frame++ % 2 == 0) sample = SyntheticHand.sample(SyntheticHand.hand(gesture, u = u, v = v, scale = scale, pointing = pointing))
        game.update(dt, sample)
    }

    fun calibrate() {
        gesture = Gesture.OPEN
        repeat(120) { tick() }
        game.skipRangeCalibration()
        repeat(90) { tick() }
    }
}
