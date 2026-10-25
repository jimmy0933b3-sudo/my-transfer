package com.example.cameratranslator.core

/** Full photo/OCR text may be logged only after an explicit opt-in. */
internal object PhotoDiagnosticConsentPolicy {
    fun allowPhotoText(hasSavedChoice: Boolean, savedValue: Boolean): Boolean =
        hasSavedChoice && savedValue

    /** Old releases wrote photo text by default without a saved consent key. */
    fun purgePreConsentHistory(hasSavedChoice: Boolean): Boolean = !hasSavedChoice
}
