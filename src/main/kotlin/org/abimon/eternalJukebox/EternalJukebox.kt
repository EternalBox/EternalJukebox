package org.abimon.eternalJukebox

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpServer
import io.vertx.ext.web.Router
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.abimon.eternalJukebox.data.analysis.IAnalyser
import org.abimon.eternalJukebox.data.analysis.SpotifyAnalyser
import org.abimon.eternalJukebox.data.analytics.IAnalyticsProvider
import org.abimon.eternalJukebox.data.analytics.IAnalyticsStorage
import org.abimon.eternalJukebox.data.audio.IAudioSource
import org.abimon.eternalJukebox.data.database.IDatabase
import org.abimon.eternalJukebox.data.storage.IStorage
import org.abimon.eternalJukebox.handlers.PopularHandler
import org.abimon.eternalJukebox.handlers.StaticResources
import org.abimon.eternalJukebox.handlers.api.*
import org.abimon.eternalJukebox.objects.BasicStringSubstitutor
import org.abimon.eternalJukebox.objects.ConstantValues
import org.abimon.eternalJukebox.objects.EmptyDataAPI
import org.abimon.eternalJukebox.objects.JukeboxConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.StringReader
import java.util.*
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.jvmName

object EternalJukebox : CoroutineScope {
    override val coroutineContext = SupervisorJob() + CoroutineName("EternalJukebox") // `SupervisorJob` means this won't be cancelled

    val jsonMapper: ObjectMapper = ObjectMapper()
            .registerModules(Jdk8Module(), KotlinModule.Builder().build(), JavaTimeModule(), ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
            .registerModules(Jdk8Module(), KotlinModule.Builder().build(), JavaTimeModule(), ParameterNamesModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)

    private val jsonConfig: File = File("config.json")
    private val yamlConfig: File = File("config.yaml")

    val BASE_64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

    @Suppress("JoinDeclarationAndAssignment")
    val config: JukeboxConfig
    val vertx: Vertx
    private val webserver: HttpServer

    val storage: IStorage
    val audio: IAudioSource?

    val spotify: IAnalyser

    val analytics: IAnalyticsStorage
    val analyticsProviders: List<IAnalyticsProvider>

    val database: IDatabase

    private val logger: Logger = LoggerFactory.getLogger("EternalBox")

    private val schedule: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val apis = ArrayList<IAPI>()

    private val logStreams: Map<String, PrintStream>
    private val emptyPrintStream = PrintStream(object: OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    })

    private val hourlyVisitorsAddress: ConcurrentSkipListSet<String> = ConcurrentSkipListSet()
    private val referrers: ConcurrentSkipListSet<String> = ConcurrentSkipListSet()
    private val referrersFile = File("referrers.txt")

    public fun start() {
        webserver.listen(config.port)
        logger.info("Now listening on port {}", config.port)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val hikariLogger = LoggerFactory.getLogger("com.zaxxer.hikari") as ch.qos.logback.classic.Logger
        hikariLogger.level = Level.INFO

        start()
    }

    init {
        config = if (jsonConfig.exists()) {
            jsonMapper.readValue(jsonConfig.bufferedReader().use(BasicStringSubstitutor::preprocess), JukeboxConfig::class.java)
        } else if (yamlConfig.exists()) {
            yamlMapper.readValue(yamlConfig.bufferedReader().use(BasicStringSubstitutor::preprocess), JukeboxConfig::class.java)
        } else {
            JukeboxConfig()
        }

        logStreams = config.logFiles.mapValues { (_, filename) -> if(filename != null) PrintStream(File(filename)) else emptyPrintStream }

        if(config.printConfig)
            logger.trace("Loaded config: {}", config)
        else
            logger.trace("Loaded config")

        if (referrersFile.exists())
            referrers.addAll(referrersFile.readLines())

        // Config Handling

        vertx = Vertx.vertx(VertxOptions().setMaxWorkerExecuteTime(config.workerExecuteTime).setWarningExceptionTime(1).setWarningExceptionTimeUnit(TimeUnit.SECONDS))
        webserver = vertx.createHttpServer()

        storage = config.storageType.storage

        val mainRouter = Router.router(vertx)

        //Something, something check for cookies
        mainRouter.route().handler {
            val ip = it.request().remoteAddress().host()
            if (ip !in hourlyVisitorsAddress) {
                it.data()[ConstantValues.HOURLY_UNIQUE_VISITOR] = true
                hourlyVisitorsAddress.add(ip)
            }

            it.request().getHeader("Referer")?.let(referrers::add)

            it.data()[ConstantValues.USER_UID] = UUID.randomUUID().toString()

            it.next()
        }

        config.redirects.forEach { (route, path) -> mainRouter.route(route).handler { context -> context.response().redirect(path) } }

        val runSiteAPI = isEnabled("siteAPI")

        if (runSiteAPI) {
            mainRouter.route().handler {
                it.next()
            }
        }

        val apiRouter = Router.router(vertx)

        if (isEnabled("analysisAPI"))
            apis.add(AnalysisAPI)

        if (runSiteAPI) {
            apis.add(SiteAPI)

            if(isEnabled("analytics")) {
                analytics = config.analyticsStorageType.analytics
                analyticsProviders = config.analyticsProviders.map { (type) -> type.provider }
            } else {
                analytics = EmptyDataAPI
                analyticsProviders = emptyList()
            }
        } else {
            analytics = EmptyDataAPI
            analyticsProviders = emptyList()
        }

        database = if (isEnabled("database"))
            requireNotNull(config.databaseType.db.objectInstance) { "No class of name ${config.databaseType.db.jvmName}"}
        else
            EmptyDataAPI

        if (isEnabled("audioAPI")) {
            apis.add(AudioAPI)

            audio = requireNotNull(config.audioSourceType.audio.objectInstance) { "No class of name ${config.audioSourceType.audio.jvmName}" }
        } else {
            audio = EmptyDataAPI
        }

        spotify = if (isEnabled("audioAPI") || isEnabled("analysisAPI"))
            SpotifyAnalyser
        else
            EmptyDataAPI

        if (isEnabled("nodeAPI"))
            apis.add(NodeAPI)

        analyticsProviders.forEach { provider -> provider.setupWebAnalytics(mainRouter) }

        apis.forEach { api ->
            val sub = Router.router(vertx)
            api.setup(sub)
            apiRouter.mountSubRouter(api.mountPath, sub)
        }
        mainRouter.mountSubRouter("/api", apiRouter)

        if (isEnabled("popular"))
            PopularHandler.setup(mainRouter)
        if (isEnabled("staticResources"))
            StaticResources.setup(mainRouter)

        webserver.requestHandler(mainRouter)

        schedule.scheduleAtFixedRate(0, 1, TimeUnit.HOURS) { referrersFile.writeText(referrers.joinToString("\n")) }
    }

    private fun isEnabled(function: String): Boolean = config.disable[function] != true
}
