package com.example.adas.dms

/**
 * Builds face-landmark frames from three intuitive dials, with no camera involved.
 *
 * Used by [SimulatedFaceLandmarkSource] for the Settings bench toggle (same
 * pattern as the OBD simulator) and by the DMS unit tests as their fixture, so
 * both exercise the exact geometry the real signal functions read.
 *
 * The layout is a schematic face in normalized coordinates, dimensioned so the
 * signal values land in realistic ranges:
 *   - eye width 0.10 → EAR = `eyeOpenness * 0.30` (0.30 is a typical open eye)
 *   - mouth width 0.16 → MAR ranges 0.05 (closed) to 0.60 (wide yawn)
 *   - nose offset ±0.2 → [headYawProxy] returns `yaw` exactly
 */
object SyntheticFace {

    private const val POINT_COUNT = FaceMesh.MAX_INDEX + 1

    private const val EYE_HALF_HEIGHT_OPEN = 0.015f
    private const val MOUTH_HALF_HEIGHT_CLOSED = 0.004f
    private const val MOUTH_HALF_HEIGHT_RANGE = 0.044f

    /**
     * @param eyeOpenness 0 = fully closed, 1 = fully open (EAR ≈ 0.30).
     * @param mouthOpenness 0 = closed, 1 = wide yawn.
     * @param yaw head turn in [-1,1]; [headYawProxy] returns this value back.
     */
    fun build(
        eyeOpenness: Float = 1f,
        mouthOpenness: Float = 0f,
        yaw: Float = 0f
    ): FaceLandmarks {
        val pts = MutableList(POINT_COUNT) { Landmark(0.5f, 0.5f) }

        val eyeH = eyeOpenness.coerceIn(0f, 1f) * EYE_HALF_HEIGHT_OPEN
        putEye(pts, FaceMesh.RIGHT_EYE, outerX = 0.35f, innerX = 0.45f, cy = 0.45f, h = eyeH)
        putEye(pts, FaceMesh.LEFT_EYE, outerX = 0.65f, innerX = 0.55f, cy = 0.45f, h = eyeH)

        val mouthH = MOUTH_HALF_HEIGHT_CLOSED +
            mouthOpenness.coerceIn(0f, 1f) * MOUTH_HALF_HEIGHT_RANGE
        pts[FaceMesh.MOUTH_LEFT] = Landmark(0.42f, 0.72f)
        pts[FaceMesh.MOUTH_RIGHT] = Landmark(0.58f, 0.72f)
        pts[FaceMesh.LIP_UPPER] = Landmark(0.50f, 0.72f - mouthH)
        pts[FaceMesh.LIP_LOWER] = Landmark(0.50f, 0.72f + mouthH)

        pts[FaceMesh.CHEEK_LEFT] = Landmark(0.30f, 0.50f)
        pts[FaceMesh.CHEEK_RIGHT] = Landmark(0.70f, 0.50f)
        pts[FaceMesh.NOSE_TIP] = Landmark(0.50f + yaw.coerceIn(-1f, 1f) * 0.20f, 0.55f)

        return FaceLandmarks(pts)
    }

    /** Eye contour in EAR index order: outer, upper, upper, inner, lower, lower. */
    private fun putEye(
        pts: MutableList<Landmark>,
        eye: IntArray,
        outerX: Float,
        innerX: Float,
        cy: Float,
        h: Float
    ) {
        val upperX1 = outerX + (innerX - outerX) * 0.33f
        val upperX2 = outerX + (innerX - outerX) * 0.66f
        pts[eye[0]] = Landmark(outerX, cy)
        pts[eye[1]] = Landmark(upperX1, cy - h)
        pts[eye[2]] = Landmark(upperX2, cy - h)
        pts[eye[3]] = Landmark(innerX, cy)
        pts[eye[4]] = Landmark(upperX2, cy + h)
        pts[eye[5]] = Landmark(upperX1, cy + h)
    }
}
