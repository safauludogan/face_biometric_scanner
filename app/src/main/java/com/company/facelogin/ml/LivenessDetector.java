package com.company.facelogin.ml;

import android.os.SystemClock;

import com.google.mlkit.vision.face.Face;

public class LivenessDetector {

    public enum State {
        WAITING_BLINK,
        WAITING_HEAD_LEFT,
        WAITING_HEAD_RIGHT,
        PASS,
        FAIL
    }

    private static final float EYE_OPEN_THRESHOLD   = 0.7f;
    private static final float EYE_CLOSED_THRESHOLD = 0.3f;
    private static final float HEAD_TURN_THRESHOLD  = 20f;
    private static final float HEAD_CENTER_ZONE     = 10f;
    private static final long  SESSION_TIMEOUT_MS   = 30_000L;

    private State   state         = State.WAITING_BLINK;
    private boolean eyeWasClosed  = false;
    private boolean headWasTurned = false;
    private long    startTimeMs;

    public LivenessDetector() {
        startTimeMs = SystemClock.elapsedRealtime();
    }

    /**
     * Processes one face frame and advances the state machine.
     * Returns the current state after processing.
     */
    public State process(Face face) {
        if (state == State.PASS || state == State.FAIL) return state;

        if (SystemClock.elapsedRealtime() - startTimeMs > SESSION_TIMEOUT_MS) {
            state = State.FAIL;
            return state;
        }

        switch (state) {
            case WAITING_BLINK:      processBlink(face);             break;
            case WAITING_HEAD_LEFT:  processHeadTurn(face, true);   break;
            case WAITING_HEAD_RIGHT: processHeadTurn(face, false);  break;
        }

        return state;
    }

    public State getState() { return state; }

    public String getInstructionText() {
        switch (state) {
            case WAITING_BLINK:      return "Göz kırpın";
            case WAITING_HEAD_LEFT:  return "Sola bakın";
            case WAITING_HEAD_RIGHT: return "Sağa bakın";
            case PASS:               return "Doğrulanıyor...";
            case FAIL:               return "Liveness başarısız";
            default:                 return "";
        }
    }

    public void reset() {
        state         = State.WAITING_BLINK;
        eyeWasClosed  = false;
        headWasTurned = false;
        startTimeMs   = SystemClock.elapsedRealtime();
    }

    // ── Detection helpers ─────────────────────────────────────────────────────

    private void processBlink(Face face) {
        Float leftEye  = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();
        if (leftEye == null || rightEye == null) return;

        float minOpen = Math.min(leftEye, rightEye);

        if (minOpen < EYE_CLOSED_THRESHOLD) {
            eyeWasClosed = true;
        } else if (eyeWasClosed && minOpen > EYE_OPEN_THRESHOLD) {
            eyeWasClosed = false;
            state = State.WAITING_HEAD_LEFT;
        }
    }

    /**
     * ML Kit convention (front camera): positive Y = face pointing toward camera's right
     * = person looking to their left.
     *
     * @param expectLeft true = wait for Y > threshold ("Sola bakın")
     *                  false = wait for Y < -threshold ("Sağa bakın")
     */
    private void processHeadTurn(Face face, boolean expectLeft) {
        float yAngle = face.getHeadEulerAngleY();

        boolean turned = expectLeft
                ? (yAngle > HEAD_TURN_THRESHOLD)
                : (yAngle < -HEAD_TURN_THRESHOLD);

        if (turned) {
            headWasTurned = true;
        } else if (headWasTurned && Math.abs(yAngle) < HEAD_CENTER_ZONE) {
            headWasTurned = false;
            state = expectLeft ? State.WAITING_HEAD_RIGHT : State.PASS;
        }
    }
}
