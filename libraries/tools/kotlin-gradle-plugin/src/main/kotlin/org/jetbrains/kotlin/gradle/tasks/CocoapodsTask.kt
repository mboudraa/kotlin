/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.KotlinCocoapodsPlugin
import org.jetbrains.kotlin.gradle.plugin.cocoapods.cocoapodsBuildDirs
import java.io.File

/**
 * The task generates a podspec file which allows a user to
 * integrate a Kotlin/Native framework into a Cocoapods project.
 */
open class PodspecTask: DefaultTask() {

    @OutputFile
    val outputFile: File = project.projectDir.resolve("${project.name}.podspec")

    @Nested
    lateinit var settings: CocoapodsExtension

    // TODO: Framework name customization.
    // TODO: Update compiler version to be able to build static frameworks
    // TODO: UP-TO-DATE check is incorrect if pods changed
    // TODO: Generate defs in a separate task.
    // TODO: Move the taret mapping in a separate class.
    // TODO: Do we need preserve_path_patterns?
    @TaskAction
    fun generate() {
        val frameworkDir = project.cocoapodsBuildDirs.framework.relativeTo(outputFile.parentFile).path
        val dependencies = settings.pods.map { pod ->
            val versionSuffix = if (pod.version != null) ", '${pod.version}'" else ""
            "|    spec.dependency '${pod.name}'$versionSuffix"
        }.joinToString(separator = "\n")
        val specName = project.name.replace('-', '_')

        outputFile.writeText("""
            |Pod::Spec.new do |spec|
            |    spec.name                     = '$specName'
            |    spec.version                  = '${settings.version}'
            |    spec.homepage                 = '${settings.homepage.orEmpty()}'
            |    spec.source                   = { :git => "Not Published", :tag => "Cocoapods/#{spec.name}/#{spec.version}" }
            |    spec.authors                  = '${settings.authors.orEmpty()}'
            |    spec.license                  = '${settings.license.orEmpty()}'
            |    spec.summary                  = '${settings.summary.orEmpty()}'
            |
            |    spec.static_framework         = true
            |    spec.vendored_frameworks      = "$frameworkDir/#{spec.name}.framework"
            |    spec.libraries                = "c++"
            |    spec.module_name              = "#{spec.name}_umbrella"
            |
            $dependencies
            |
            |    spec.pod_target_xcconfig = {
            |        'KOTLIN_TARGET[sdk=iphonesimulator*]' => 'ios_x64',
            |        'KOTLIN_TARGET[sdk=iphoneos*]' => 'ios_arm64',
            |        'KOTLIN_TARGET[sdk=macosx*]' => 'macos_x64'
            |    }
            |
            |    preserve_path_patterns = ['*.gradle', 'gradle*', '*.properties', 'src/**/*']
            |
            |    spec.script_phases = [
            |        {
            |            :name => 'Build $specName',
            |            :execution_position => :before_compile,
            |            :shell_path => '/bin/sh',
            |            :script => <<-SCRIPT
            |                set -ev
            |                REPO_ROOT=`realpath "${'$'}PODS_TARGET_SRCROOT"`
            |                ${'$'}REPO_ROOT/gradlew -p "${'$'}REPO_ROOT" syncFramework -i \
            |                    -P${KotlinCocoapodsPlugin.TARGET_PROPERTY}=${'$'}KOTLIN_TARGET \
            |                    -P${KotlinCocoapodsPlugin.CONFIGURATION_PROPERTY}=${'$'}CONFIGURATION \
            |                    -P${KotlinCocoapodsPlugin.CFLAGS_PROPERTY}="${'$'}OTHER_CFLAGS" \
            |                    -P${KotlinCocoapodsPlugin.HEADER_PATHS_PROPERTY}="${'$'}HEADER_SEARCH_PATHS" \
            |                    -P${KotlinCocoapodsPlugin.FRAMEWORK_PATHS_PROPERTY}="${'$'}FRAMEWORK_SEARCH_PATHS"
            |            SCRIPT
            |        }
            |    ]
            |end
        """.trimMargin())
    }
}

/**
 * Creates a dummy framework in the target directory.
 * This framework is used during Cocoapods installing process
 * since the real framework isn't built yet at this stage.
 */
open class DummyFrameworkTask: DefaultTask() {
    @OutputDirectory
    val destinationDir = project.cocoapodsBuildDirs.framework

    @get:Input
    val frameworkName
        get() = project.name.replace('-', '_')

    private val frameworkDir: File
        get() = destinationDir.resolve("$frameworkName.framework")

    private fun copyResource(from: String, to: File) {
        to.parentFile.mkdirs()
        to.outputStream().use {
            javaClass.getResourceAsStream(from).copyTo(it)
        }
    }

    private fun copyFrameworkFile(relativeFrom: String, relativeTo: String = relativeFrom) =
        copyResource(
            "/cocoapods/dummy.framework/$relativeFrom",
            frameworkDir.resolve(relativeTo)
        )

    @TaskAction
    fun create() {
        // Reset the destination directory
        with(destinationDir) {
            deleteRecursively()
            mkdirs()
        }

        // Copy files for the dummy framework.
        copyFrameworkFile("Info.plist")
        copyFrameworkFile("dummy", frameworkName)
        copyFrameworkFile("Modules/module.modulemap")
        copyFrameworkFile("Headers/dummy.h")
    }
}