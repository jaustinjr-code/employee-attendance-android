package com.jaustinjr.employeeattendance.reporting

import androidx.core.content.FileProvider

/**
 * The report share provider. A subclass only so it cannot merge with the debug manifest's
 * `androidx.core.content.FileProvider`; its paths still come from the manifest meta-data, which is
 * what the static `FileProvider.getUriForFile` reads.
 */
class ReportFileProvider : FileProvider()
