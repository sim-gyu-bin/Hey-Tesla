package com.heytesla.app.diagnostics

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Looper
import androidx.test.runner.AndroidJUnitRunner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Only the explicitly selected SDK/Parcel bench may use this runner. The default runner is unchanged.
 * Application.attach receives a fail-closed context before onCreate; DiagnosticApp is never created.
 * Framework provider attachment may obtain AppOps only before Instrumentation.onCreate; the bench
 * phase denies every system-service lookup. Neither phase permits component starts or permissions.
 * Android has no public Instrumentation service-instantiation hook: this is not an inventory of
 * services independently launched by the OS. The parent must keep vehicle services OFF throughout.
 */
class NoRuntimeAndroidJUnitRunner : AndroidJUnitRunner() {
    private val applicationCreations = AtomicInteger()
    private val applicationStarts = AtomicInteger()
    private val forbiddenOperations = AtomicInteger()
    private val startupAppOpsLookups = AtomicInteger()
    @Volatile private var providerAttachmentPhase = true
    private var sealedStartupAppOpsLookups = -1
    private lateinit var isolatedApplication: Application
    private lateinit var isolatedContext: Context

    override fun onCreate(arguments: Bundle) {
        // SDK36 ActivityThread.handleBindApplication installs providers before this callback.
        // Seal before super.onCreate can start a test thread: prepare must not use this exception.
        providerAttachmentPhase = false
        sealedStartupAppOpsLookups = startupAppOpsLookups.get()
        check(arguments.getString("class") == BENCH_SELECTION) {
            "No-runtime runner requires the single explicit SDK parcel bench"
        }
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(applicationCreations.incrementAndGet() == 1) { "Unexpected application creation" }
        isolatedContext = NoRuntimeContext(context)
        return super.newApplication(cl, Application::class.java.name, isolatedContext).also {
            check(it.javaClass == Application::class.java) { "Application isolation failed" }
            isolatedApplication = it
        }
    }

    override fun callApplicationOnCreate(app: Application) {
        check(app === isolatedApplication && app.javaClass == Application::class.java) {
            "Unexpected application startup"
        }
        check(applicationStarts.incrementAndGet() == 1) { "Repeated application startup" }
        super.callApplicationOnCreate(app)
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity = forbidden()

    internal fun assertIsolated(context: Context) {
        check(context.applicationContext === isolatedApplication) { "Unexpected application context" }
        check(isolatedApplication.javaClass == Application::class.java) { "Unexpected application class" }
        check(isolatedApplication.baseContext === isolatedContext) { "Missing startup guard" }
        check(applicationCreations.get() == 1 && applicationStarts.get() == 1) {
            "Application startup isolation failed"
        }
        check(!providerAttachmentPhase && startupAppOpsLookups.get() == sealedStartupAppOpsLookups) {
            "Framework startup service allowance escaped into the bench"
        }
        check(forbiddenOperations.get() == 0) { "Forbidden no-runtime operation attempted" }
    }

    private fun forbidden(): Nothing {
        forbiddenOperations.incrementAndGet()
        throw AssertionError("SDK parcel bench cannot access runtime, services, activities or permissions")
    }

    private inner class NoRuntimeContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any {
            // SDK36 ContentProvider.attachInfo requests APP_OPS_SERVICE (source lines 2631-2633).
            // SystemServiceRegistry maps only this name to AppOpsManager (lines 864-870).
            // No radio manager or other service is allowed, even during provider attachment.
            if (providerAttachmentPhase && name == Context.APP_OPS_SERVICE &&
                Looper.myLooper() == Looper.getMainLooper()
            ) {
                startupAppOpsLookups.incrementAndGet()
                return checkNotNull(super.getSystemService(name)) { "Framework provider AppOps unavailable" }
            }
            return forbidden()
        }
        override fun checkSelfPermission(permission: String): Int = forbidden()
        override fun startService(service: Intent): ComponentName = forbidden()
        override fun startForegroundService(service: Intent): ComponentName = forbidden()
        override fun stopService(name: Intent): Boolean = forbidden()
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = forbidden()
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Context.BindServiceFlags): Boolean =
            forbidden()
        override fun bindService(service: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean =
            forbidden()
        override fun bindService(
            service: Intent,
            flags: Context.BindServiceFlags,
            executor: Executor,
            conn: ServiceConnection,
        ): Boolean = forbidden()
        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: Executor,
            conn: ServiceConnection,
        ): Boolean = forbidden()
    }

    companion object {
        internal const val BENCH_SELECTION =
            "com.heytesla.app.vehicle.uwb.TeslaUwbSdkNoRfBenchTest#sdkParcelPreservesConsumerContractWithoutRuntime"
    }
}
