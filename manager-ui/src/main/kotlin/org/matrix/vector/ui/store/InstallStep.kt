package org.matrix.vector.ui.store

/**
 * Where a store-driven module install has got to. A plain state type with no Android dependencies, so
 * the shared Details screen can render an install bar without knowing which app's installer produced
 * it. An app with no install capability never emits anything but [Idle].
 */
sealed interface InstallStep {
    data object Idle : InstallStep

    data class Downloading(val packageName: String, val bytes: Long, val total: Long) : InstallStep

    data class Installing(val packageName: String) : InstallStep

    data class Confirming(val packageName: String) : InstallStep

    data class Done(val packageName: String) : InstallStep

    data class Failed(val packageName: String, val reason: String?) : InstallStep
}

/** True while a download, a commit or the platform's confirmation is under way. */
val InstallStep.inProgress: Boolean
    get() =
        this is InstallStep.Downloading ||
            this is InstallStep.Installing ||
            this is InstallStep.Confirming

/**
 * This step as the page of [pagePackage] shows it.
 *
 * The host reports one install for the whole app, so a step can belong to another module's page:
 * its progress, failure or result is not this page's, and the page shows its resting state instead.
 */
fun InstallStep.forPage(pagePackage: String): InstallStep {
    val subject =
        when (this) {
            InstallStep.Idle -> return this
            is InstallStep.Downloading -> packageName
            is InstallStep.Installing -> packageName
            is InstallStep.Confirming -> packageName
            is InstallStep.Done -> packageName
            is InstallStep.Failed -> packageName
        }
    return if (subject == pagePackage) this else InstallStep.Idle
}
