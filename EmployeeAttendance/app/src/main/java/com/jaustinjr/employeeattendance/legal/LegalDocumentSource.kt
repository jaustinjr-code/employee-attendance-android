package com.jaustinjr.employeeattendance.legal

import android.content.Context

/** Reads a [LegalDocument]'s raw text. The seam over `AssetManager`, so loading stays JVM-testable. */
fun interface LegalDocumentSource {

    /** The document's text. Blocking I/O: call it off the main thread. Throws if it cannot be read. */
    fun read(document: LegalDocument): String
}

/** [LegalDocumentSource] over the APK's `assets/`, where each document's [LegalDocument.assetPath] points. */
class AssetLegalDocumentSource(context: Context) : LegalDocumentSource {

    private val assets = context.applicationContext.assets

    override fun read(document: LegalDocument): String =
        assets.open(document.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
