package com.example.cameratranslator.core

import org.junit.Assert.*
import org.junit.Test

class PhotoDiagnosticConsentPolicyTest {
    @Test fun noExplicitConsentDefaultsToRedactedAndPurgesOldLog() {
        assertFalse(PhotoDiagnosticConsentPolicy.allowPhotoText(false, true))
        assertFalse(PhotoDiagnosticConsentPolicy.allowPhotoText(false, false))
        assertTrue(PhotoDiagnosticConsentPolicy.purgePreConsentHistory(false))
    }

    @Test fun explicitOptInMayLogTextWithoutPurgingPriorData() {
        assertTrue(PhotoDiagnosticConsentPolicy.allowPhotoText(true, true))
        assertFalse(PhotoDiagnosticConsentPolicy.purgePreConsentHistory(true))
    }

    @Test fun explicitOptOutKeepsTextRedacted() {
        assertFalse(PhotoDiagnosticConsentPolicy.allowPhotoText(true, false))
        assertFalse(PhotoDiagnosticConsentPolicy.purgePreConsentHistory(true))
    }
}
