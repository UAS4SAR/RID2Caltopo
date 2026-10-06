package org.ncssar.rid2caltopo.data

/**
 * Clues captured while the bound drone's current flight was ignored ("Don't publish") in the Drone
 * Confirmation Panel. Apple mirrors this in R2CCore/IgnoredFlightClue.swift; keep wording identical.
 *
 * The clue is always captured (snapshot and binding kept). The operator is asked whether to publish
 * the flight: Yes reopens the Drone Confirmation Panel for that drone, No keeps the flight ignored.
 * After No, Submit keeps the clue on this device only (no CalTopo upload).
 */
object IgnoredFlightClue {
    const val TITLE = "Current flight ignored"
    const val MESSAGE = "Do you want to publish it?"
    const val YES = "Yes"
    const val NO = "No"
    const val LOCAL_ONLY_NOTE = "Current flight ignored: Submit saves this clue on this device only (no CalTopo upload)."
    const val LOCAL_ONLY_SAVED = "Clue saved on this device only; the current flight is ignored, so it was not uploaded to CalTopo."

    enum class SubmitAction { UPLOAD, ASK, LOCAL_ONLY }

    /** What Submit does: upload normally, ask first, or (after No) keep the clue local only. */
    fun submitAction(flightIgnored: Boolean, keptIgnored: Boolean): SubmitAction = when {
        !flightIgnored -> SubmitAction.UPLOAD
        keptIgnored -> SubmitAction.LOCAL_ONLY
        else -> SubmitAction.ASK
    }
}
