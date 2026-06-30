package com.company.facelogin.ml;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

/**
 * Shared face-validity check used by both RegisterActivity and FaceVerificationActivity.
 * Requires the ML Kit detector to be configured with:
 *   LANDMARK_MODE_ALL and CLASSIFICATION_MODE_ALL
 */
public final class FaceValidator {

    private FaceValidator() {}

    /**
     * Returns true only for a plausible human face.
     * Rejects hands, objects, and other non-face detections by verifying:
     *   - Eye open probabilities are present and non-trivial
     *   - Smiling probability is computed (requires full face structure)
     *   - Five key landmarks are detected (eyes, nose, mouth corners)
     *   - Landmarks follow the expected vertical ordering: eyes → nose → mouth
     *   - Eyes are horizontally separated (not collapsed to a single point)
     *   - Head roll is within ±45°
     */
    public static boolean isFaceLike(Face face) {
        Float leftEyeProb  = face.getLeftEyeOpenProbability();
        Float rightEyeProb = face.getRightEyeOpenProbability();
        if (leftEyeProb == null || rightEyeProb == null)  return false;
        if (Math.max(leftEyeProb, rightEyeProb) < 0.1f) return false;

        if (face.getSmilingProbability() == null) return false;

        FaceLandmark noseBase   = face.getLandmark(FaceLandmark.NOSE_BASE);
        FaceLandmark leftEye    = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye   = face.getLandmark(FaceLandmark.RIGHT_EYE);
        FaceLandmark mouthLeft  = face.getLandmark(FaceLandmark.MOUTH_LEFT);
        FaceLandmark mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT);
        if (noseBase == null || leftEye == null || rightEye == null
                || mouthLeft == null || mouthRight == null) return false;

        // Vertical ordering: eyes → nose → mouth (larger Y = lower in portrait image)
        float eyeMidY   = (leftEye.getPosition().y + rightEye.getPosition().y) / 2f;
        float noseY     = noseBase.getPosition().y;
        float mouthMidY = (mouthLeft.getPosition().y + mouthRight.getPosition().y) / 2f;
        if (noseY <= eyeMidY)   return false;
        if (mouthMidY <= noseY) return false;

        // Eyes must be horizontally separated (≥ 20% of face bounding box width)
        float eyeDist   = Math.abs(leftEye.getPosition().x - rightEye.getPosition().x);
        float faceWidth = face.getBoundingBox().width();
        if (eyeDist < faceWidth * 0.20f) return false;

        if (Math.abs(face.getHeadEulerAngleZ()) > 45f) return false;

        return true;
    }
}
