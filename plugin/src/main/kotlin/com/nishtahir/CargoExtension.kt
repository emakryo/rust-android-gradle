package com.nishtahir

import com.android.build.gradle.api.BaseVariant
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.process.ExecSpec
import java.io.File
import java.util.Properties

sealed class Features {
    class All() : Features()

    data class DefaultAnd(val featureSet: Set<String>) : Features()

    data class NoDefaultBut(val featureSet: Set<String>) : Features()
}

@Suppress("unused")
data class FeatureSpec(var features: Features? = null) {
    fun all() {
        this.features = Features.All()
    }

    fun defaultAnd(featureSet: Array<String>) {
        this.features = Features.DefaultAnd(featureSet.toSet())
    }

    fun noDefaultBut(featureSet: Array<String>) {
        this.features = Features.NoDefaultBut(featureSet.toSet())
    }
}

@Suppress("unused")
open class CargoConfig(val name: String){
    lateinit var localProperties: Properties

    var module: String? = null
    var libname: String? = null
    var targets: List<String>? = null
    var prebuiltToolchains: Boolean? = null
    var profile: String? = null
    var verbose: Boolean? = null
    var targetDirectory: String? = null
    var targetIncludes: Array<String>? = null
    var apiLevel: Int? = null
    var apiLevels: Map<String, Int> = mapOf()
    var extraCargoBuildArguments: List<String>? = null

    // It would be nice to use a receiver here, but there are problems interoperating with Groovy
    // and Kotlin that are just not worth working out.  Another JVM language, yet another dynamic
    // invoke solution :(
    var exec: ((ExecSpec, Toolchain) -> Unit)? = null

    var featureSpec: FeatureSpec = FeatureSpec()

    fun features(action: Action<FeatureSpec>) {
        action.execute(featureSpec)
    }

    val toolchainDirectory: File
        get() {
            // Share a single toolchain directory, if one is configured.  Prefer "local.properties"
            // to "ANDROID_NDK_TOOLCHAIN_DIR" to "$TMP/rust-android-ndk-toolchains".
            val local: String? = localProperties.getProperty("rust.androidNdkToolchainDir")
            if (local != null) {
                return File(local).absoluteFile
            }

            val globalDir: String? = System.getenv("ANDROID_NDK_TOOLCHAIN_DIR")
            if (globalDir != null) {
                return File(globalDir).absoluteFile
            }

            val defaultDir = File(System.getProperty("java.io.tmpdir"), "rust-android-ndk-toolchains")
            return defaultDir.absoluteFile
        }

    var cargoCommand: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.cargoCommand", "RUST_ANDROID_GRADLE_CARGO_COMMAND") ?: "cargo"
            }
        }

    var rustupChannel: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.rustupChannel", "RUST_ANDROID_GRADLE_RUSTUP_CHANNEL") ?: ""
            }
        }

    var pythonCommand: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.pythonCommand", "RUST_ANDROID_GRADLE_PYTHON_COMMAND") ?: "python"
            }
        }

    // Required so that we can parse the default triple out of `rustc --version --verbose`. Sadly,
    // there seems to be no way to get this information out of cargo directly. Failure to locate
    // this isn't fatal, however.
    var rustcCommand: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.rustcCommand", "RUST_ANDROID_GRADLE_RUSTC_COMMAND") ?: "rustc"
            }
        }

    fun getFlagProperty(camelCaseName: String, snakeCaseName: String, ifUnset: Boolean): Boolean {
        val propVal = getProperty(camelCaseName, snakeCaseName)
        if (propVal == "1" || propVal == "true") {
            return true
        }
        if (propVal == "0" || propVal == "false") {
            return false
        }
        if (propVal == null || propVal == "") {
            return ifUnset
        }
        throw GradleException("Illegal value for property \"$camelCaseName\" / \"$snakeCaseName\". Must be 0/1/true/false if set")
    }

    internal fun getProperty(camelCaseName: String, snakeCaseName: String): String? {
        val local: String? = localProperties.getProperty(camelCaseName)
        if (local != null) {
            return local
        }
        val global: String? = System.getenv(snakeCaseName)
        if (global != null) {
            return global
        }
        return null
    }

    fun merge(other: CargoConfig) {
        val from = this
        module = other.module ?: from.module
        libname = other.libname ?: from.libname
        targets = other.targets ?: from.targets
        prebuiltToolchains = other.prebuiltToolchains ?: from.prebuiltToolchains
        profile = other.profile ?: from.profile
        verbose = other.verbose ?: from.verbose
        targetDirectory = other.targetDirectory ?: from.targetDirectory
        targetIncludes = other.targetIncludes ?: from.targetIncludes
        apiLevel = other.apiLevel ?: from.apiLevel
        apiLevels = from.apiLevels.toMutableMap().apply {
            other.apiLevels.forEach { (k, v) -> put(k, v) }
        }
        extraCargoBuildArguments = other.extraCargoBuildArguments ?: from.extraCargoBuildArguments
        exec = other.exec ?: from.exec
        featureSpec = FeatureSpec(other.featureSpec.features ?: from.featureSpec.features)
    }

    fun clone(): CargoConfig {
        val from = this
        return CargoConfig(name).apply {
            module = from.module
            libname = from.libname
            targets = from.targets
            prebuiltToolchains = from.prebuiltToolchains
            profile = from.profile
            verbose = from.verbose
            targetDirectory = from.targetDirectory
            targetIncludes = from.targetIncludes
            apiLevel = from.apiLevel
            apiLevels = from.apiLevels
            extraCargoBuildArguments = from.extraCargoBuildArguments
            exec = from.exec
            featureSpec = FeatureSpec(from.featureSpec.features)
        }
    }
}

// `CargoExtension` is documented in README.md.
open class CargoExtension(project: Project): CargoConfig("") {
    val buildTypes: NamedDomainObjectContainer<CargoConfig> = project.container(CargoConfig::class.java)
    val productFlavors: NamedDomainObjectContainer<CargoConfig> = project.container(CargoConfig::class.java)

    fun getConfig(variants: BaseVariant): CargoConfig {
        val config = (this as CargoConfig).clone()
        val buildTypeConfig = buildTypes.findByName(variants.buildType.name)
        if (buildTypeConfig is CargoConfig) {
            config.merge(buildTypeConfig)
        }

        for (flavor in variants.productFlavors.reversed()) {
            val flavorConfig = productFlavors.findByName(flavor.name)
            if (flavorConfig is CargoConfig) {
                config.merge(flavorConfig)
            }
        }

        return config
    }
}
