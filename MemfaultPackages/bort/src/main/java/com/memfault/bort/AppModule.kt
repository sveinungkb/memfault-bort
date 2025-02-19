package com.memfault.bort

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import com.memfault.bort.ingress.IngressService
import com.memfault.bort.logcat.KernelOopsDetector
import com.memfault.bort.logcat.NoopLogcatLineProcessor
import com.memfault.bort.metrics.BuiltinMetricsStore
import com.memfault.bort.settings.BundledConfig
import com.memfault.bort.settings.DeviceConfigUpdateService
import com.memfault.bort.settings.SettingsProvider
import com.memfault.bort.settings.SettingsUpdateService
import com.memfault.bort.settings.readBundledSettings
import com.memfault.bort.shared.BASIC_COMMAND_TIMEOUT_MS
import com.memfault.bort.shared.JitterDelayProvider
import com.memfault.bort.shared.JitterDelayProvider.ApplyJitter.APPLY
import com.memfault.bort.tokenbucket.Anr
import com.memfault.bort.tokenbucket.BugReportPeriodic
import com.memfault.bort.tokenbucket.BugReportRequestStore
import com.memfault.bort.tokenbucket.ContinuousLogFile
import com.memfault.bort.tokenbucket.HighResMetricsFile
import com.memfault.bort.tokenbucket.JavaException
import com.memfault.bort.tokenbucket.KernelOops
import com.memfault.bort.tokenbucket.Kmsg
import com.memfault.bort.tokenbucket.Logcat
import com.memfault.bort.tokenbucket.MarDropbox
import com.memfault.bort.tokenbucket.MetricReportStore
import com.memfault.bort.tokenbucket.MetricsCollection
import com.memfault.bort.tokenbucket.RealTokenBucketFactory
import com.memfault.bort.tokenbucket.RealTokenBucketStorage.Companion.createFor
import com.memfault.bort.tokenbucket.Reboots
import com.memfault.bort.tokenbucket.StructuredLog
import com.memfault.bort.tokenbucket.TokenBucketStore
import com.memfault.bort.tokenbucket.Tombstone
import com.memfault.bort.tokenbucket.Wtf
import com.memfault.bort.tokenbucket.WtfTotal
import com.memfault.bort.uploader.EnqueueHttpTask
import com.memfault.bort.uploader.HttpTask
import com.memfault.bort.uploader.HttpTaskCallFactory
import com.memfault.bort.uploader.PreparedUploadService
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.compat.MergeModules
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.FIELD
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER
import kotlin.time.toJavaDuration
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import vnd.myandroid.bortappid.CustomLogScrubber

/**
 * Bindings which are applicable only to the "release" build. These are replaced by ReleaseTestModule in the
 * "releaseTest" build
 */
@Module
@ContributesTo(SingletonComponent::class)
class ReleaseModule {
    @Provides
    fun applyJitter() = APPLY
}

/**
 * Anything that:
 * - creates an external type (e.g. okhttp)
 * - makes a runtime decision on what type to create
 * - binds a class to multiple types
 * - binds to a generic type
 * needs to be in a Module (i.e. can't just use @Inject).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    companion object {
        @Provides
        @Singleton
        fun bortEnabledProvider(
            settingsProvider: SettingsProvider,
            preferenceBortEnabledProvider: Lazy<PreferenceBortEnabledProvider>,
            bortAlwaysEnabledProvider: Lazy<BortAlwaysEnabledProvider>,
        ) = if (settingsProvider.isRuntimeEnableRequired) {
            preferenceBortEnabledProvider.get()
        } else {
            bortAlwaysEnabledProvider.get()
        }

        @Provides
        @Singleton
        fun okHttpClient(
            settingsProvider: SettingsProvider,
            interceptors: InjectSet<Interceptor>,
        ) = OkHttpClient.Builder()
            .connectTimeout(settingsProvider.httpApiSettings.connectTimeout.toJavaDuration())
            .writeTimeout(settingsProvider.httpApiSettings.writeTimeout.toJavaDuration())
            .readTimeout(settingsProvider.httpApiSettings.readTimeout.toJavaDuration())
            .callTimeout(settingsProvider.httpApiSettings.callTimeout.toJavaDuration())
            .also { client -> interceptors.forEach { client.addInterceptor(it) } }
            .build()

        @Provides
        @Singleton
        fun retrofit(settingsProvider: SettingsProvider, okHttpClient: OkHttpClient) = Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl(settingsProvider.httpApiSettings.filesBaseUrl.toHttpUrl())
            .addConverterFactory(
                kotlinxJsonConverterFactory()
            )
            .build()

        @Provides
        fun sharedPrefs(context: Application) = PreferenceManager.getDefaultSharedPreferences(
            context
        )

        @UploadHoldingArea
        @Provides
        fun holdingAreaPharedPrefs(context: Application) = context.getSharedPreferences(
            FILE_UPLOAD_HOLDING_AREA_PREFERENCE_FILE_NAME,
            Context.MODE_PRIVATE
        )

        @Provides
        fun bundledConfig(resources: Resources) = BundledConfig {
            resources.readBundledSettings()
        }

        @Provides
        fun resources(context: Application) = context.resources

        @Provides
        fun bootId() = LinuxBootId { File("/proc/sys/kernel/random/boot_id").readText().trim() }

        @Provides
        @BasicCommandTimout
        fun basicTimeout(): Long = BASIC_COMMAND_TIMEOUT_MS

        @Provides
        @Main
        fun mainLooper() = Looper.getMainLooper()

        @Provides
        @Singleton
        @BugReportRequestStore
        fun bugReportRequest(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "bug_report_requests"),
            getMaxBuckets = { settingsProvider.bugReportSettings.rateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.bugReportSettings.rateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindBugReportRequest(@BugReportRequestStore store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @Reboots
        fun reboots(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "reboot_events"),
            getMaxBuckets = { settingsProvider.rebootEventsSettings.rateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.rebootEventsSettings.rateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindReboots(@Reboots store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @BugReportPeriodic
        fun bugReportPeriodic(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "bug_report_periodic"),
            getMaxBuckets = { 1 },
            getTokenBucketFactory = {
                RealTokenBucketFactory(
                    defaultCapacity = 3,
                    defaultPeriod = settingsProvider.bugReportSettings.requestInterval *
                        settingsProvider.bugReportSettings.periodicRateLimitingPercentOfPeriod / 100,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindBugReportPeriodic(@BugReportPeriodic store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @Tombstone
        fun tombstone(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "tombstones"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.tombstonesRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.dropBoxSettings.tombstonesRateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindTombstone(@Tombstone store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @JavaException
        fun javaException(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "java_execeptions"),
            // Note: the backtrace signature is used as key, so one bucket per issue basically.
            getMaxBuckets = { settingsProvider.dropBoxSettings.javaExceptionsRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(
                    rateLimitingSettings = settingsProvider.dropBoxSettings.javaExceptionsRateLimitingSettings,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindJavaException(@JavaException store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @Wtf
        fun wtf(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "wtfs"),
            // Note: the backtrace signature is used as key, so one bucket per issue basically.
            getMaxBuckets = { settingsProvider.dropBoxSettings.wtfsRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(
                    rateLimitingSettings = settingsProvider.dropBoxSettings.wtfsRateLimitingSettings,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindWtf(@Wtf store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @WtfTotal
        fun wtfTotal(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "wtfs_total"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.wtfsTotalRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(
                    rateLimitingSettings = settingsProvider.dropBoxSettings.wtfsTotalRateLimitingSettings,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindWtfTotal(@WtfTotal store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @Anr
        fun anrs(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "anrs"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.anrRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.dropBoxSettings.anrRateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindAnr(@Anr store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @Kmsg
        fun kmsg(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "kmsgs"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.kmsgsRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.dropBoxSettings.kmsgsRateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindKmsg(@Kmsg store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @StructuredLog
        fun structuredLog(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "memfault_structured"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.structuredLogRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.dropBoxSettings.structuredLogRateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindStructuredLog(@StructuredLog store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @KernelOops
        fun kernelOops(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "kernel_oops"),
            getMaxBuckets = { 1 },
            getTokenBucketFactory = {
                val settings = settingsProvider.logcatSettings.kernelOopsRateLimitingSettings
                RealTokenBucketFactory(
                    defaultCapacity = settings.defaultCapacity,
                    defaultPeriod = settings.defaultPeriod.duration,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindOops(@KernelOops store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @Logcat
        fun logcat(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "logcat_periodic"),
            getMaxBuckets = { 1 },
            getTokenBucketFactory = {
                RealTokenBucketFactory(
                    defaultCapacity = 2,
                    defaultPeriod = settingsProvider.logcatSettings.collectionInterval / 2,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindLogcat(@Logcat store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @MetricsCollection
        fun metricCollection(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "metrics_periodic"),
            getMaxBuckets = { 1 },
            getTokenBucketFactory = {
                RealTokenBucketFactory(
                    defaultCapacity = 2,
                    defaultPeriod = settingsProvider.metricsSettings.collectionInterval / 2,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindMetricCollection(@MetricsCollection store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @HighResMetricsFile
        fun highResMetricsFile(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "high_res_metrics"),
            getMaxBuckets = { 1 },
            getTokenBucketFactory = {
                RealTokenBucketFactory(
                    defaultCapacity = 2,
                    defaultPeriod = settingsProvider.metricsSettings.collectionInterval / 2,
                    metrics = metrics,
                )
            },
        )

        @Provides
        @IntoSet
        fun bindHighResMetricsFile(@HighResMetricsFile store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @MetricReportStore
        fun metricReports(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "memfault_report"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.metricReportRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.dropBoxSettings.metricReportRateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindMetricReport(@MetricReportStore store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @ContinuousLogFile
        fun continuousLogFiles(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "continuous_log"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.continuousLogFileRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(
                    settingsProvider.dropBoxSettings.continuousLogFileRateLimitingSettings,
                    metrics
                )
            },
        )

        @Provides
        @IntoSet
        fun bindContinuousLogFile(@ContinuousLogFile store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        @MarDropbox
        fun marDropbox(
            context: Context,
            settingsProvider: SettingsProvider,
            metrics: BuiltinMetricsStore,
        ) = TokenBucketStore(
            storage = createFor(context, "mar_file"),
            getMaxBuckets = { settingsProvider.dropBoxSettings.marFileRateLimitingSettings.maxBuckets },
            getTokenBucketFactory = {
                RealTokenBucketFactory.from(settingsProvider.dropBoxSettings.marFileRateLimitingSettings, metrics)
            },
        )

        @Provides
        @IntoSet
        fun bindMarDropbox(@MarDropbox store: TokenBucketStore): TokenBucketStore = store

        @Provides
        @Singleton
        fun ingressService(settingsProvider: SettingsProvider, httpTaskCallFactory: HttpTaskCallFactory) =
            IngressService.create(settingsProvider.httpApiSettings, httpTaskCallFactory)

        @Provides
        @Singleton
        fun uploadService(retrofit: Retrofit) = retrofit.create(PreparedUploadService::class.java)

        @Provides
        @Singleton
        fun settingsUpdateService(okHttpClient: OkHttpClient, settingsProvider: SettingsProvider) =
            SettingsUpdateService.create(
                okHttpClient = okHttpClient,
                deviceBaseUrl = settingsProvider.httpApiSettings.deviceBaseUrl
            )

        @Provides
        @Singleton
        fun deviceConfigUpdateService(okHttpClient: OkHttpClient, settingsProvider: SettingsProvider) =
            DeviceConfigUpdateService.create(
                okHttpClient = okHttpClient,
                deviceBaseUrl = settingsProvider.httpApiSettings.deviceBaseUrl
            )

        @Provides
        fun enqueueHttpTask(
            context: Context,
            settingsProvider: SettingsProvider,
            jitterDelayProvider: JitterDelayProvider,
        ) = EnqueueHttpTask { input ->
            enqueueWorkOnce<HttpTask>(context, input.toWorkerInputData()) {
                setInitialDelay(jitterDelayProvider.randomJitterDelay())
                setConstraints(settingsProvider.httpApiSettings.uploadConstraints)
                setBackoffCriteria(BackoffPolicy.EXPONENTIAL, input.backoffDuration.toJavaDuration())
                input.taskTags.forEach { tag -> addTag(tag) }
            }
        }

        @Provides
        fun dataScrubber(settingsProvider: SettingsProvider): DataScrubber = DataScrubber(
            settingsProvider.dataScrubbingSettings.rules.filterIsInstance(LineScrubbingCleaner::class.java) +
                CustomLogScrubber
        )

        @Provides
        fun kernelOopsDetector(
            settingsProvider: SettingsProvider,
            kernelOopsDetector: KernelOopsDetector,
        ) = if (settingsProvider.logcatSettings.kernelOopsDataSourceEnabled) kernelOopsDetector
        else NoopLogcatLineProcessor

        @Provides
        @MarFileSampledHoldingDir
        fun marFileSampledHoldingDir(context: Context) = File(context.filesDir, "mar-uploads")

        @Provides
        @MarFileUnsampledHoldingDir
        fun marFileUnsampledHoldingDir(context: Context) = File(context.filesDir, "mar-unsampled")
    }

    @Binds
    abstract fun reporterConnector(real: RealReporterServiceConnector): ReporterServiceConnector

    @Binds
    abstract fun context(context: Application): Context
}

@Qualifier
@Retention(RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class UploadHoldingArea

@Qualifier
@Retention(RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class MarFileSampledHoldingDir

@Qualifier
@Retention(RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class MarFileUnsampledHoldingDir

/**
 * Injecting Set<T> for multibinding doesn't work in Kotlin, because the kotlin set is typed Set<out T>, so we get
 * a missing binding error. Always use this alias e.g. InjectSet<T> to inject multibinding values, instead.
 */
typealias InjectSet<T> = Set<@JvmSuppressWildcards T>

// typealias InjectMap<K, V> = Map<@JvmSuppressWildcards K, @JvmSuppressWildcards V>

// Binds Anvil (for ContributesBinding) to Hilt's component.
@MergeModules(SingletonComponent::class)
@InstallIn(SingletonComponent::class)
class AnvilModule
