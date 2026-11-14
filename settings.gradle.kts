import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Bootstrap the gitignored Sherpa-ONNX AAR into local-repo/ so fresh clones build out of the box.
run {
    val aarFile = rootDir.resolve("local-repo/com/k2fsa/sherpa/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar")
    val aarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
    val expectedSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

    if (!aarFile.exists() || aarFile.length() == 0L) {
        if (gradle.startParameter.isOffline) {
            throw GradleException(
                "Missing ${aarFile.relativeTo(rootDir)} and Gradle is in --offline mode.\n" +
                    "Download it manually from:\n  $aarUrl\nand place it at:\n  ${aarFile.absolutePath}"
            )
        }
        aarFile.parentFile.mkdirs()
        val tmpFile = aarFile.parentFile.resolve("${aarFile.name}.tmp")
        println("Bootstrapping Sherpa-ONNX 2.0.1 AAR into ${aarFile.relativeTo(rootDir)}...")
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(aarUrl))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmpFile.toPath()))
        if (response.statusCode() !in 200..299) {
            tmpFile.delete()
            throw GradleException(
                "Failed to download Sherpa-ONNX AAR (HTTP ${response.statusCode()}) from $aarUrl.\n" +
                    "Download it manually and place it at: ${aarFile.absolutePath}"
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        tmpFile.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != expectedSha256) {
            tmpFile.delete()
            throw GradleException(
                "SHA-256 mismatch for downloaded Sherpa-ONNX AAR: expected $expectedSha256, got $actualSha256"
            )
        }
        Files.move(tmpFile.toPath(), aarFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        println("Sherpa-ONNX 2.0.1 AAR verified and saved.")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = URI("https://jitpack.io") }
        maven { url = rootDir.resolve("local-repo").toURI() }
    }
}

rootProject.name = "TrekoAssistant"
include(":app")
include(":core-common")
include(":core-audio")
include(":core-kws")
include(":core-network")
include(":core-mcp")
