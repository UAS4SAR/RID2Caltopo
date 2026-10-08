package org.ncssar.rid2caltopo.ui

/** Presentation only: these rules never authorize publication or trigger audio. */
internal enum class WorkspaceAlertTone { Hidden, Quiet, Caution, Active }
internal fun workspaceAlertTone(hasPlayed: Boolean, active: Boolean, caution: Boolean): WorkspaceAlertTone = when {
    !hasPlayed -> WorkspaceAlertTone.Hidden
    active -> WorkspaceAlertTone.Active
    caution -> WorkspaceAlertTone.Caution
    else -> WorkspaceAlertTone.Quiet
}
internal enum class WorkspaceDroneAction { Add, Confirm, Inspect }
internal fun workspaceDroneAction(known: Boolean, publishing: Boolean): WorkspaceDroneAction = when {
    publishing -> WorkspaceDroneAction.Inspect
    known -> WorkspaceDroneAction.Confirm
    else -> WorkspaceDroneAction.Add
}

/** Ten percent visual approach band; does not change the detector or audio schedule. */
internal fun workspaceSeparationCaution(horizontal: Double, vertical: Double?, threshold: Double): Boolean =
    threshold.isFinite() && threshold > 0 && horizontal.isFinite() && horizontal >= 0 &&
        horizontal <= threshold * 1.10 && (vertical == null || (vertical.isFinite() && vertical >= 0 && vertical <= threshold * 1.10))
