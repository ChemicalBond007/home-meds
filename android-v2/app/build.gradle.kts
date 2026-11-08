import java.io.StringReader
import java.io.RandomAccessFile
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localBaseUrl = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull?.let { content ->
        Properties().apply { load(StringReader(content)) }.getProperty("homeMedsBaseUrl")?.trim()
    }?.takeIf { it.isNotEmpty() }
val baseUrl = (providers.gradleProperty("homeMedsBaseUrl").orNull ?: localBaseUrl ?: "https://your-domain.example.com").trimEnd('/')
require(Regex("^https://[A-Za-z0-9.-]+(:\\d+)?$").matches(baseUrl)) { "homeMedsBaseUrl must be an HTTPS origin" }
val apkAbis = providers.gradleProperty("homeMedsAbis").orNull?.split(',')?.map { it.trim() }?.distinct()
require(apkAbis == null || apkAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }) {
    "homeMedsAbis must be a comma-separated list of arm64-v8a, armeabi-v7a, x86, x86_64"
}
val apkSuffix = apkAbis?.joinToString("-", prefix = "-").orEmpty()

// 正式签名材料位于仓库外层 .secrets/（已被 Git 忽略）。密码只在构建时读取，不写入脚本。
val signingDir = rootProject.layout.projectDirectory.dir("../.secrets/home-meds_android-release")
val hasReleaseSigning = signingDir.file("homemeds-release.p12").asFile.isFile && signingDir.file("password.txt").asFile.isFile
val releaseStorePassword = providers.fileContents(signingDir.file("password.txt")).asText.map { it.trim() }

android {
    namespace = "com.homemeds.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.homemeds.app.v2"
        minSdk = 29
        targetSdk = 36
        versionCode = 20805
        versionName = "2.8.5"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        apkAbis?.let { selected -> ndk { abiFilters += selected } }
    }
    buildFeatures { buildConfig = true; compose = true; resValues = true }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = signingDir.file("homemeds-release.p12").asFile
                storeType = "PKCS12"
                storePassword = releaseStorePassword.get()
                keyAlias = "homemeds-release"
                keyPassword = releaseStorePassword.get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有签名材料时保持未签名，不回退到调试密钥。
            signingConfig = signingConfigs.findByName("release")
        }
        // 测试版：与正式版同构（R8、资源压缩），但包名、名称与版本号带「测试」标注，可与正式版共存。
        create("qa") {
            initWith(getByName("release"))
            val baseVersion = defaultConfig.versionName!!
            val counter = rootProject.layout.projectDirectory.file(".gradle/test-builds/$baseVersion.txt")
            val previous = providers.fileContents(counter).asText.orElse("0").get().trim().toInt()
            val number = providers.gradleProperty("homeMedsTestBuild").orNull?.toInt()
                ?: Math.addExact(previous, 1)
            require(number > 0) { "homeMedsTestBuild must be a positive integer" }
            val counterFile = counter.asFile
            val reserveNumber = tasks.register("reserveQaBuildNumber") {
                group = "build"
                description = "Reserves a unique test build number without changing the release version."
                doLast {
                    counterFile.parentFile.mkdirs()
                    RandomAccessFile(counterFile, "rw").use { file ->
                        file.channel.lock().use {
                            val current = file.readLine()?.trim()?.toInt() ?: 0
                            check(current == previous && number > current) {
                                "Test build number already used; rerun the build with a higher number."
                            }
                            file.setLength(0)
                            file.writeBytes(number.toString())
                        }
                    }
                }
            }
            tasks.matching { it.name == "preQaBuild" }.configureEach { dependsOn(reserveNumber) }
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test.$number"
            resValue("string", "app_name", "赛博药箱测试")
            // 测试版需要可安装的签名：优先正式密钥，否则使用调试密钥。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    androidResources { localeFilters += listOf("zh-rCN") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

// Android reserves names beginning with "test"; retain qa and provide the documented entry point.
tasks.register("assembleTest") {
    group = "build"
    description = "Builds the independently installable test APK."
    dependsOn("assembleQa")
}

base.archivesName.set("赛博药箱-${android.defaultConfig.versionName}$apkSuffix")

androidComponents {
    beforeVariants(selector().withBuildType("qa")) {
        it.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                val label = if (variant.buildType == "qa") "赛博药箱测试" else "赛博药箱"
                output.outputFileName.set(output.versionName.map { "$label-$it$apkSuffix.apk" })
            }
        }
    }
}

// These manifest-discovered constructors must survive R8, not just debug unit tests.
fun registerScannerVerification(buildType: String) = tasks.register("verify${buildType}Scanner") {
    group = "verification"
    description = "Checks ML Kit registrar constructors in the minified release mapping."
    dependsOn("minify${buildType}WithR8")
    val mapping = layout.buildDirectory.file("outputs/mapping/${buildType.lowercase()}/mapping.txt")
    inputs.file(mapping)
    doLast {
        val lines = mapping.get().asFile.readLines()
        listOf(
            "com.google.mlkit.common.internal.CommonComponentRegistrar",
            "com.google.mlkit.vision.barcode.internal.BarcodeRegistrar",
            "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
        ).forEach { registrar ->
            val start = lines.indexOf("$registrar -> $registrar:")
            check(start >= 0) { "ML Kit registrar removed or renamed: $registrar" }
            val members = lines.drop(start + 1).takeWhile { it.startsWith(" ") || it.startsWith("#") }
            check(members.any { Regex("""\bvoid <init>\(\)""").containsMatchIn(it) }) {
                "ML Kit registrar constructor removed by R8: $registrar"
            }
        }
        logger.lifecycle("ML Kit $buildType registrar constructors verified.")
    }
}
val verifyReleaseScanner = registerScannerVerification("Release")
val verifyQaScanner = registerScannerVerification("Qa")
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyReleaseScanner) }
tasks.matching { it.name == "assembleQa" }.configureEach { dependsOn(verifyQaScanner) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.commonmark:commonmark:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.32.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
