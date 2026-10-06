package com.jaustinjr.employeeattendance.testutil

import java.io.File

/**
 * The `:app` module directory, for tests that read source-tree files (manifest, resources, assets)
 * directly. Unit tests run with the Gradle module directory as the working directory, but resolve
 * by walking up so a test also works from the repo or project root.
 *
 * A test reading files this way must also declare them as inputs of the `Test` task in
 * `app/build.gradle.kts`, or editing only those files leaves the task UP-TO-DATE and the test unrun.
 */
fun findAppModuleDir(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val candidate = File(dir, "src/main/res/xml/backup_rules.xml")
        if (candidate.isFile) return dir
        val nested = File(dir, "app/src/main/res/xml/backup_rules.xml")
        if (nested.isFile) return File(dir, "app")
        dir = dir.parentFile
    }
    throw AssertionError("could not locate the app module from ${File("").absolutePath}")
}
