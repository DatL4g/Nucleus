/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.ExternalToolRunner
import dev.nucleusframework.desktop.application.internal.UpdateYmlPublish
import dev.nucleusframework.desktop.tasks.AbstractNucleusTask
import dev.nucleusframework.internal.utils.ioFile
import dev.nucleusframework.internal.utils.notNullProperty
import dev.nucleusframework.internal.utils.nullableProperty
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Merges the per-format `<channel><osSuffix>.yml` auto-update manifests produced by the individual
 * electron-builder package runs (for the current OS) into a single union manifest and uploads it to
 * S3.
 *
 * Nucleus packages each [dev.nucleusframework.desktop.application.dsl.TargetFormat] with
 * its own electron-builder invocation. For S3 publishing of multiple auto-updatable formats of the
 * same OS, those runs are configured with `publishAutoUpdate: false`, so electron-builder uploads
 * the artifacts but not the manifests (which all share the `<channel><osSuffix>.yml` key and would
 * otherwise clobber each other). This task takes over that one upload: it reads each run's
 * locally-written manifest, merges them with
 * [dev.nucleusframework.desktop.application.internal.UpdateYmlMerger], and uploads the
 * union so every format survives.
 *
 * See [UpdateYmlPublish] for the testable pieces of this flow.
 */
@DisableCachingByDefault(because = "Publishes (uploads) the merged manifest; always re-runs with its package tasks")
abstract class AbstractMergeUpdateYmlTask : AbstractNucleusTask() {
    /** Output directories of the per-format package tasks; each may contain a `<channel><osSuffix>.yml`. */
    @get:Internal
    val perFormatOutputDirs: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    val s3Enabled: Property<Boolean> = objects.notNullProperty<Boolean>().apply { set(false) }

    @get:Input
    @get:Optional
    val s3Bucket: Property<String> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val s3Region: Property<String> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val s3Path: Property<String> = objects.nullableProperty()

    @get:Input
    @get:Optional
    val s3Acl: Property<String> = objects.nullableProperty()

    /** Publish mode from the `compose.electronBuilder.publishMode` Gradle property, if set. */
    @get:Input
    @get:Optional
    val publishMode: Property<String> = objects.nullableProperty()

    /** Publish mode from the DSL (`publish.publishMode`), used when no env var / property overrides it. */
    @get:Input
    val dslPublishMode: Property<String> = objects.notNullProperty<String>().apply { set("never") }

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @TaskAction
    fun run() {
        val dirs = perFormatOutputDirs.files.filter { it.isDirectory }
        val merged = UpdateYmlPublish.discoverAndMerge(dirs)
        val willUpload = shouldUploadToS3()

        if (merged.isEmpty()) {
            // electron-builder is configured with publishAutoUpdate:false for these formats but still
            // writes the manifests locally — so finding none in a publishing build means the upload
            // would silently leave no manifest on S3 (worse than the original collision). Fail loudly.
            if (willUpload) {
                throw GradleException(
                    "Expected per-format '<channel>' update manifests in the package output directories " +
                        "to merge and upload to S3, but found none in: " +
                        "${dirs.joinToString { it.absolutePath }}. Check the electron-builder output.",
                )
            }
            logger.info("No update manifests found in ${dirs.size} format output dir(s); nothing to merge.")
            return
        }

        val outDir = destinationDir.ioFile.apply { mkdirs() }
        val writtenFiles =
            merged.map { manifest ->
                val file = outDir.resolve(manifest.fileName)
                file.writeText(manifest.content)
                logger.lifecycle("Merged update manifest written to ${file.canonicalPath}")
                file
            }

        if (willUpload) {
            uploadToS3(writtenFiles)
        } else {
            logger.info("Not publishing in this build; merged manifest(s) written locally only.")
        }
    }

    /** Whether to upload the merged manifest in this build (mirrors electron-builder's publish decision). */
    private fun shouldUploadToS3(): Boolean {
        if (!s3Enabled.getOrElse(false)) return false
        val publishFlag =
            UpdateYmlPublish.resolvePublishFlag(
                anyProviderEnabled = true,
                envValue = System.getenv(UpdateYmlPublish.PUBLISH_MODE_ENV),
                propValue = publishMode.orNull,
                dslValue = dslPublishMode.getOrElse("never"),
            )
        return UpdateYmlPublish.shouldPublish(publishFlag, System.getenv())
    }

    private fun uploadToS3(files: List<File>) {
        val bucket =
            s3Bucket.orNull?.takeIf { it.isNotBlank() }
                ?: throw GradleException("S3 publishing is enabled but no S3 bucket is configured.")
        val aws =
            findAwsExecutable()
                ?: throw GradleException(
                    "The merged auto-update manifest must be uploaded to S3, but the AWS CLI ('aws') " +
                        "was not found on PATH. electron-builder uploads the package artifacts via its " +
                        "bundled SDK, but the plugin uploads the merged '<channel>' manifest via " +
                        "'aws s3 cp'. Install the AWS CLI on the build machine, or publish only a single " +
                        "auto-updatable format per OS to S3.",
                )

        for (file in files) {
            val key = UpdateYmlPublish.s3Key(s3Path.orNull, file.name)
            val args =
                buildList {
                    add("s3")
                    add("cp")
                    add(file.absolutePath)
                    add("s3://$bucket/$key")
                    s3Region.orNull?.takeIf { it.isNotBlank() }?.let {
                        add("--region")
                        add(it)
                    }
                    s3Acl.orNull?.takeIf { it.isNotBlank() }?.let {
                        add("--acl")
                        add(it)
                    }
                }
            logger.lifecycle("Uploading merged update manifest to s3://$bucket/$key")
            // AWS credentials are inherited from the process environment (AWS_ACCESS_KEY_ID, etc.),
            // the same way electron-builder's S3 publisher reads them.
            runExternalTool(
                tool = aws,
                args = args,
                logToConsole = ExternalToolRunner.LogToConsole.Always,
            )
        }
    }

    private fun findAwsExecutable(): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        return pathEnv.split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, "aws") }
            .firstOrNull { it.isFile && it.canExecute() }
    }
}
