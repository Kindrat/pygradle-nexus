import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.DockerComposePlugin
import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestStackTraceFilter
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath("com.avast.gradle:gradle-docker-compose-plugin:0.8.12")
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
val dockerComposeCommand = if (isOsLinux && project.hasProperty("dockerComposeVersion")) dockerComposeScript else "docker-compose"

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
}
