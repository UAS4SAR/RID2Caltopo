# iPad confirmation and explicit Follow parity

Changes made in `/Users/kjt/Projects/RID2Caltopo`, without a separate worktree.

- The associated-drone button in the iPad Main Screen row now presents the same DroneConfirmationView used by automatic confirmation, including Publish track and Don't publish. It no longer navigates to aircraft details. Unassociated aircraft still open Add to RID Map. Opening the panel does not itself change the remembered publication decision; explicit confirmation removes the ignored state.
- Both iPad Follow switches use a shared binding that releases manual viewport suspension when explicitly enabled. The pilot-specific switch retains the selected aircraft as the focus target.
- Manual viewport suspension is part of the aircraft render state, so resuming follow can update the map even without new telemetry. Pan and zoom still preserve the operator's view until an explicit focus/follow action.
- Checked Android MainScreen/R2CViewModel confirmation routing and both MapPane follow controls; they already use confirmation and clear manual suspension on enable.

Validation: Apple core suite passed all 434 tests; signed iOS Debug build succeeded. Logs: `/tmp/r2c-confirm-follow-tests.log`, `/tmp/r2c-confirm-follow-build.log`. No device update performed for this follow-up; installed build 302 remains unchanged.

Physical checks pending: ignore Mini 4 Pro, reopen confirmation from its Main Screen button, then explicitly publish; select the aircraft and enable Follow in each control; pan/zoom, re-enable Follow, and confirm following resumes. Verify manual map bounds remain preserved when Follow is not explicitly resumed.

Update: these fixes were installed in place and launched on Ken's iPad as 2.3.7 (303), together with the expired-pending-confirmation fix. Installed version verified; physical checks remain pending.
