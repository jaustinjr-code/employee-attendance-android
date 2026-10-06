package com.jaustinjr.employeeattendance.legal

import androidx.annotation.StringRes
import com.jaustinjr.employeeattendance.R

/**
 * Every legal document the app ships, in the order the legal screen lists them. This enum is the
 * registry: adding a document (terms of use, open-source notices) is a new entry here plus its text
 * under `assets/legal/` — the legal screen, navigation and loading pick it up with no other change.
 *
 * The text lives in an asset rather than in `strings.xml` so it stays one reviewable file per
 * document, in the Markdown subset [parseLegalText] understands, that can also be published as-is
 * wherever a store listing needs a public copy.
 *
 * [id] is what the navigation route carries. It is a stable string rather than the enum's name or
 * ordinal so renaming or reordering entries cannot break a restored back stack.
 */
enum class LegalDocument(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val assetPath: String,
) {
    PRIVACY_POLICY(
        id = "privacy-policy",
        titleRes = R.string.legal_privacy_policy_title,
        summaryRes = R.string.legal_privacy_policy_summary,
        assetPath = "legal/privacy_policy.md",
    ),
    ;

    companion object {
        /** The document whose [id] is [id], or null if no shipped document has it. */
        fun fromId(id: String?): LegalDocument? = entries.firstOrNull { it.id == id }
    }
}
