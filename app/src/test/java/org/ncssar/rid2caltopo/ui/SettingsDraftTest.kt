package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient

class SettingsDraftTest {
    @Test fun editsAndDiscardDoNotWriteLiveSettings() {
        CaltopoClient.ResetPersistedClientState()
        val model = CaltopoSettingsViewModel()
        model.beginEditing()
        val volume = CaltopoClient.GetAlarmVolumePercent()
        model.onAlarmVolumePercentChanged(if (volume == 25) 50 else 25)
        assertTrue(model.hasUnsavedChanges())
        assertEquals(volume, CaltopoClient.GetAlarmVolumePercent())
        model.discardEdits()
        assertEquals(volume, model.alarmVolumePercent.value)
        assertFalse(model.hasUnsavedChanges())
    }
    @Test fun externalRefreshDoesNotDiscardPendingDraftAndRevertIsClean() {
        val model = CaltopoSettingsViewModel()
        model.beginEditing()
        val original = model.opPeriod.value
        model.onOpPeriodChanged("Draft period")
        model.settingsChanged()
        assertEquals("Draft period", model.opPeriod.value)
        model.onOpPeriodChanged(original)
        assertFalse(model.hasUnsavedChanges())
        model.discardEdits()
    }
}
