import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.DockerComposePlugin
import de.undercouch.gradle.tasks.download.Download
import org.apache.http.Header
import org.apache.http.HttpHeaders
import org.apache.http.HttpHost
import org.apache.http.HttpStatus
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.FileEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.BasicAuthCache
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.HttpClientBuilder
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestStackTraceFilter
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.sonatype.nexus.httpclient.internal.NexusHttpRoutePlanner

buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath("com.avast.gradle:gradle-docker-compose-plugin:0.8.12")
        classpath("org.sonatype.nexus:nexus-rest-client:3.14.0-04")
    }
}

plugins {
    val kotlinVersion = "1.3.11"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion

    id("base")
    id("idea")
    id("com.github.ben-manes.versions") version "0.20.0"
    id("de.undercouch.download") version "3.4.3"

}

apply<DockerComposePlugin>()

//scmVersion {
//    versionIncrementer("incrementMinorIfNotOnRelease", mapOf("releaseBranchPattern" to "release-.*"))
//}
val isOsLinux = System.getProperty("os.name").toLowerCase().contains("linux")

var dockerComposeVersion = "1.23.2"
val dockerComposeCacheDir = "$rootDir/.gradle/docker-compose/"
val dockerComposeScript = "$dockerComposeCacheDir/$dockerComposeVersion/docker-compose.sh"
val dockerComposeCommand = if (isOsLinux) dockerComposeScript else "docker-compose"

val spekVersion = "2.0.0-rc.1"

repositories {
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(gradleApi())

    implementation("com.linkedin.pygradle:pygradle-plugin:0.6.20")
    implementation("org.apache.httpcomponents:httpclient:4.5.5")
    implementation("org.apache.httpcomponents:httpmime:4.5.5")
    implementation("org.apache.ivy:ivy:2.4.0")

    testImplementation(gradleTestKit())
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spekVersion") {
        exclude("org.jetbrains.kotlin")
    }
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:$spekVersion") {
        exclude("org.junit.platform")
        exclude("org.jetbrains.kotlin")
    }
}

configure<ComposeExtension> {
    projectName = project.name
    executable = dockerComposeCommand
    dockerComposeWorkingDirectory = projectDir.absolutePath
    useComposeFiles = listOf("docker-compose.yml")
    removeVolumes = true
    removeOrphans = true
    waitForTcpPorts = true
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    withType<Test> {
        useJUnitPlatform {
            includeEngines.add("spec2")
        }
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            stackTraceFilters = setOf(TestStackTraceFilter.ENTRY_POINT)
            showStandardStreams = true
        }
    }

    withType<Wrapper> {
        gradleVersion = "4.7"
        distributionType = Wrapper.DistributionType.ALL
    }

    val downloadDockerCompose by creating(Download::class) {
        group = "build setup"

        val composeSrc = "https://github.com/docker/compose/releases/download/$dockerComposeVersion/docker-compose-linux-x86_64"

        inputs.property("dockerComposeVersion", dockerComposeVersion)

        overwrite(false)
        src(composeSrc)
        dest(dockerComposeScript)

        onlyIf { isOsLinux }

        doFirst { delete(dockerComposeCacheDir) }
        doLast { file(dockerComposeScript).setExecutable(true) }
    }


    "composeUp" {
        dependsOn(downloadDockerCompose)
    }

    val createPythonRepo by creating {
        group = "build setup"
        dependsOn(":composeUp")
        doLast {

            val credentials = UsernamePasswordCredentials("admin", "admin123")
            val credentialsProvider = BasicCredentialsProvider()
            val targetHost = HttpHost("localhost", 8081, "http")

            credentialsProvider.setCredentials(
                    AuthScope(targetHost.getHostName(), targetHost.getPort()),
                    credentials)
            val authCache = BasicAuthCache()
            val basicAuth = BasicScheme()
            authCache.put(targetHost, basicAuth)
            val context = HttpClientContext.create()
            context.setCredentialsProvider(credentialsProvider)
            context.setAuthCache(authCache)


            val client = HttpClientBuilder.create()
                    .setDefaultCredentialsProvider(credentialsProvider)
                    .build()

            val loadScriptRequest = HttpPost("/service/rest/v1/script")
            loadScriptRequest.setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            loadScriptRequest.entity = FileEntity(File(projectDir, "pypi.json"))

            val loadScriptResponse = client.execute(targetHost, loadScriptRequest, context)
            val loadStatusCode = loadScriptResponse.statusLine.statusCode
            if (loadStatusCode != HttpStatus.SC_NO_CONTENT) {
                throw GradleScriptException("Script load failed : $loadScriptResponse", null)
            }

            val runScriptRequest = HttpPost("/service/rest/v1/script/pypi/run")
            runScriptRequest.setHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
            val runScriptResponse = client.execute(targetHost, runScriptRequest, context)
            val runStatusCode = runScriptResponse.statusLine.statusCode
            if (runStatusCode != HttpStatus.SC_OK) {
                throw GradleScriptException("Script run failed : $runScriptResponse", null)
            }
        }
    }
}
