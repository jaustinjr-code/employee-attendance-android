package com.jaustinjr.employeeattendance.reporting

import androidx.core.content.FileProvider
import com.jaustinjr.employeeattendance.R

/** The report share provider, limited to `cacheDir/reports` by `@xml/report_paths`. */
class ReportFileProvider : FileProvider(R.xml.report_paths)
