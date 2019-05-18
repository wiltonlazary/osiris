package ws.osiris.awsdeploy

import com.amazonaws.services.lambda.AWSLambdaClientBuilder
import com.amazonaws.services.lambda.model.InvocationType
import com.amazonaws.services.lambda.model.InvokeRequest
import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ws.osiris.aws.ApiFactory
import ws.osiris.aws.Stage
import ws.osiris.awsdeploy.cloudformation.DeployResult
import ws.osiris.awsdeploy.cloudformation.Templates
import ws.osiris.awsdeploy.cloudformation.apiId
import ws.osiris.awsdeploy.cloudformation.deployStack
import ws.osiris.core.Api
import java.awt.Desktop
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.stream.Collectors

private val log = LoggerFactory.getLogger("ws.osiris.awsdeploy")

/**
 * Implemented for each build system to hook into the project configuration.
 *
 * The configuration allows the code and CloudFormation template to be generated and the project to be deployed.
 */
interface DeployableProject {

    /** The project name; must be specified by the user in the Maven or Gradle project. */
    val name: String

    /** The project version; Maven requires a version but it's optional in Gradle. */
    val version: String?

    /** The root of the build directory. */
    val buildDir: Path

    /** The directory where jar files are built. */
    val zipBuildDir: Path

    /** The root of the main source directory; normally `src/main`. */
    val sourceDir: Path

    /** The root package of the application; used when generating the CloudFormation template. */
    val rootPackage: String

    /** The name of the environment into which the code is being deployed; used in resource and bucket names. */
    val environmentName: String?

    /** The directory containing the static files; null if the API doesn't serve static files. */
    val staticFilesDirectory: String?

    /** The name of the AWS profile; if not specified the default chain is used to find the profile and region. */
    val awsProfile: String?

    /** The name of the CloudFormation stack; if not specified a name is generated from the app and environment names. */
    val stackName: String?

    /** The jar files on the runtime classpath. */
    val runtimeClasspath: List<Path>

    /** The jar containing the project classes and resources. */
    val projectJar: Path

    private val cloudFormationSourceDir: Path get() = sourceDir.resolve("cloudformation")
    private val rootTemplate: Path get() = cloudFormationSourceDir.resolve("root.template")
    private val generatedCorePackage: String get() = "$rootPackage.core.generated"
    private val lambdaClassName: String get() = "$generatedCorePackage.GeneratedLambda"
    private val lambdaHandler: String get() = "$lambdaClassName::handle"
    private val cloudFormationGeneratedDir: Path get() = buildDir.resolve("cloudformation")
    private val apiFactoryClassName: String get() = "$generatedCorePackage.GeneratedApiFactory"
    private val zipFile: Path get() = zipBuildDir.resolve(zipName)
    private val zipName: String get() = if (version == null) {
        "$name-dist.zip"
    } else {
        "$name-$version-dist.zip"
    }

    private fun profile(): AwsProfile {
        val awsProfile = this.awsProfile
        return if (awsProfile == null) {
            val profile = AwsProfile.default()
            log.info("Using default AWS profile, region = {}", profile.region)
            profile
        } else {
            val profile = AwsProfile.named(awsProfile)
            log.info("Using AWS profile named '{}', region = {}", awsProfile, profile.region)
            profile
        }
    }

    /**
     * Returns a factory that can build the API, the components and the application configuration.
     */
    fun createApiFactory(parentClassLoader: ClassLoader): ApiFactory<*> {
        log.debug("runtime classpath: {}", runtimeClasspath)
        log.debug("project jar: {}", projectJar)
        val classpathJars = runtimeClasspath.map { it.toUri().toURL() } + projectJar.toUri().toURL()
        val classLoader = URLClassLoader(classpathJars.toTypedArray(), parentClassLoader)
        val apiFactoryClass = Class.forName(apiFactoryClassName, true, classLoader)
        return apiFactoryClass.newInstance() as ApiFactory<*>
    }

    fun generateCloudFormation() {
        val apiFactory = createApiFactory(javaClass.classLoader)
        val api = apiFactory.api
        val appConfig = apiFactory.config
        val codeBucket = appConfig.codeBucket
            ?: codeBucketName(appConfig.applicationName, environmentName, appConfig.bucketPrefix)
        val (codeHash, jarKey) = zipS3Key(appConfig.applicationName)
        val lambdaHandler = lambdaHandler
        // Parse the parameters from root.template and pass them to the lambda as env vars
        // This allows the handler code to reference any resources defined in root.template
        val templateParams = generatedTemplateParameters(rootTemplate, codeBucket, appConfig.applicationName)
        val staticHash = staticFilesInfo(api, staticFilesDirectory)?.hash
        deleteContents(cloudFormationGeneratedDir)
        Files.createDirectories(cloudFormationGeneratedDir)
        val templates = Templates.create(
            api,
            appConfig,
            templateParams,
            lambdaHandler,
            codeHash,
            staticHash,
            codeBucket,
            jarKey,
            environmentName,
            appConfig.bucketPrefix,
            appConfig.binaryMimeTypes
        )
        for (file in templates.files) {
            file.write(cloudFormationGeneratedDir)
        }
        // copy all templates from the template src dir to the generated template dir with filtering
        if (!Files.exists(cloudFormationSourceDir)) return
        Files.list(cloudFormationSourceDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { file ->
                val templateText = BufferedReader(FileReader(file.toFile())).use { it.readText() }
                val generatedFile = templateText
                    .replace("\${codeS3Bucket}", codeBucket)
                    .replace("\${codeS3Key}", jarKey)
                    .replace("\${environmentName}", environmentName ?: "null")
                    .replace("\${bucketPrefix}", appConfig.bucketPrefix?.let { "$it-"} ?: "")
                val generatedFilePath = cloudFormationGeneratedDir.resolve(file.fileName)
                log.debug("Copying template from ${file.toAbsolutePath()} to ${generatedFilePath.toAbsolutePath()}")
                Files.write(generatedFilePath, generatedFile.toByteArray(Charsets.UTF_8))
            }
    }

    /**
     * Recursively deletes all files and subdirectories of a directory, leaving the directory empty.
     */
    private fun deleteContents(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private data class ZipKey(val hash: String, val name: String)

    private fun zipS3Key(apiName: String): ZipKey {
        val zipPath = zipBuildDir.resolve(zipName)
        val md5Hash = md5Hash(zipPath)
        return ZipKey(md5Hash, "$apiName.$md5Hash.jar")
    }

    private fun md5Hash(vararg files: Path): String {
        val messageDigest = MessageDigest.getInstance("md5")
        val buffer = ByteArray(1024 * 1024)

        tailrec fun readChunk(stream: InputStream) {
            val bytesRead = stream.read(buffer)
            if (bytesRead == -1) {
                return
            } else {
                messageDigest.update(buffer, 0, bytesRead)
                readChunk(stream)
            }
        }
        for (file in files) {
            Files.newInputStream(file).buffered(1024 * 1024).use { readChunk(it) }
        }
        val digest = messageDigest.digest()
        return digest.joinToString("") { String.format("%02x", it) }
    }

    private fun templateUrl(templateName: String, codeBucket: String): String =
        "https://$codeBucket.s3.amazonaws.com/$templateName"


    private fun generatedTemplateParameters(rootTemplatePath: Path, codeBucketName: String, apiName: String): Set<String> {
        val templateBytes = Files.readAllBytes(rootTemplatePath)
        val templateYaml = String(templateBytes, Charsets.UTF_8)
        return generatedTemplateParameters(templateYaml, codeBucketName, apiName)
    }

    @Suppress("UNCHECKED_CAST")
    fun deploy(): Map<String, String> {
        if (!Files.exists(zipFile)) throw DeployException("Cannot find $zipName")
        val apiFactory = createApiFactory(javaClass.classLoader)
        val appConfig = apiFactory.config
        val api = apiFactory.api
        val appName = appConfig.applicationName
        val profile = profile()
        val codeBucket = appConfig.codeBucket ?: createBucket(profile, appName, environmentName, "code", appConfig.bucketPrefix)
        val (_, jarKey) = zipS3Key(appName)
        log.info("Uploading function code '$zipFile' to $codeBucket with key $jarKey")
        uploadFile(profile, zipFile, codeBucket, jarKey)
        log.info("Upload of function code complete")
        uploadTemplates(profile, codeBucket)
        if (!Files.exists(rootTemplate)) throw IllegalStateException("core/src/main/cloudformation/root.template is missing")
        val deploymentTemplateUrl = templateUrl(rootTemplate.fileName.toString(), codeBucket)
        val apiEnvSuffix = if (environmentName == null) "" else ".$environmentName"
        val apiName = "${appConfig.applicationName}$apiEnvSuffix"
        val localStackName = this.stackName
        val stackName = if (localStackName == null) {
            val stackEnvSuffix = if (environmentName == null) "" else "-$environmentName"
            "${appConfig.applicationName}$stackEnvSuffix"
        } else {
            localStackName
        }
        val deployResult = deployStack(profile, stackName, apiName, deploymentTemplateUrl)
        val staticBucket = appConfig.staticFilesBucket
            ?: staticFilesBucketName(appConfig.applicationName, environmentName, appConfig.bucketPrefix)
        uploadStaticFiles(profile, api, staticBucket, staticFilesDirectory)
        val apiId = deployResult.apiId
        val stackCreated = deployResult.stackCreated
        val deployedStages = deployStages(profile, apiId, apiName, appConfig.stages, stackCreated)
        val stageUrls = deployedStages.associate { Pair(it, stageUrl(apiId, it, profile.region)) }
        for ((stage, url) in stageUrls) log.info("Deployed to stage '$stage' at $url")
        sendKeepAlive(deployResult, appConfig.keepAliveCount, appConfig.keepAliveSleep, profile)
        return stageUrls
    }

    /**
     * Opens a path from a stage in the system default browser.
     *
     * If the stage is null then the first stage is chosen where [Stage.deployOnUpdate] is true.
     * If there are none then the first stage is used.
     *
     * If the path is null then the first `get(...)` path is used. If there are none then the static files
     * path is used if there is an index file. Otherwise a warning is logged.
     */
    fun openBrowser(stage: String, path: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            val apiFactory = createApiFactory(javaClass.classLoader)
            val urlBase = stageUrl(apiFactory.config.applicationName, stage, profile())
            val url = urlBase + path.removePrefix("/")
            log.debug("Opening path {} of stage {} in the default system browser: {}", path, stage, url)
            Desktop.getDesktop().browse(URI(url))
        } else {
            log.warn("Opening a browser is not supported")
        }
    }

    // --------------------------------------------------------------------------------------------------

    private fun uploadStaticFiles(profile: AwsProfile, api: Api<*>, bucket: String, staticFilesDirectory: String?) {
        val staticFilesInfo = staticFilesInfo(api, staticFilesDirectory) ?: return
        val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDir.resolve("static")
        for (file in staticFilesInfo.files) {
            uploadFile(profile, file, bucket, staticFilesDir, bucketDir = staticFilesInfo.hash)
        }
    }

    private fun uploadTemplates(profile: AwsProfile, codeBucket: String) {
        if (!Files.exists(cloudFormationGeneratedDir)) return
        Files.list(cloudFormationGeneratedDir)
            .filter { it.fileName.toString().endsWith(".template") }
            .forEach { uploadFile(profile, it, codeBucket) }
    }

    private fun staticFilesInfo(api: Api<*>, staticFilesDirectory: String?): StaticFilesInfo? {
        if (!api.staticFiles) {
            return null
        }
        val staticFilesDir = staticFilesDirectory?.let { Paths.get(it) } ?: sourceDir.resolve("static")
        val staticFiles = Files.walk(staticFilesDir, Int.MAX_VALUE)
            .filter { !Files.isDirectory(it) }
            .collect(Collectors.toList())
        val hash = md5Hash(*staticFiles.toTypedArray())
        return StaticFilesInfo(staticFiles, hash)
    }

    private fun sendKeepAlive(deployResult: DeployResult, instanceCount: Int, sleepTimeMs: Duration, profile: AwsProfile) {
        if (deployResult.keepAliveLambdaArn == null) return
        val lambdaClient = AWSLambdaClientBuilder.standard()
            .withCredentials(profile.credentialsProvider)
            .withRegion(profile.region)
            .build()
        log.info("Invoking keep-alive lambda {}", deployResult.keepAliveLambdaArn)
        val payloadMap = mapOf(
            "functionArn" to deployResult.lambdaVersionArn,
            "instanceCount" to instanceCount,
            "sleepTimeMs" to sleepTimeMs.toMillis()
        )
        val payloadJson = Gson().toJson(payloadMap)
        lambdaClient.invoke(InvokeRequest().apply {
            functionName = deployResult.keepAliveLambdaArn
            invocationType = InvocationType.Event.name
            payload = ByteBuffer.wrap(payloadJson.toByteArray())
        })
    }
}

/**
 * The static files and the hash of all of them together.
 *
 * The hash is used to derive the name of the folder in the static files bucket that the files are deployed to.
 * Each different set of files must be uploaded to a different location to that different stages can use
 * different sets of files. Using the hash to name a subdirectory of the static files bucket has two advantages:
 *
 * * The template generation code and deployment code can both derive the same location
 * * A new set of files is only created when any of them change and the hash changes
 */
private class StaticFilesInfo(val files: List<Path>, val hash: String)

internal fun stageUrl(apiId: String, stageName: String, region: String) =
    "https://$apiId.execute-api.$region.amazonaws.com/$stageName/"

internal fun stageUrl(apiName: String, stage: String, profile: AwsProfile): String {
    val apiId = apiId(profile, apiName)
    return stageUrl(apiId, stage, profile.region)
}
