package com.ccplayer.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;

/** Runs the pinned native dependency build outside the Gradle script class-loader. */
public abstract class BuildFfmpegTask extends DefaultTask {
    @Inject
    protected abstract ExecOperations getExecOperations();

    @Input
    public abstract Property<String> getNdkPath();

    @Input
    public abstract Property<String> getCmakePath();

    @Input
    public abstract Property<String> getAbis();

    @Input
    public abstract Property<Integer> getJobs();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getBuildInputs();

    @Internal
    public abstract DirectoryProperty getNativeRoot();

    @OutputDirectory
    public abstract DirectoryProperty getInstallDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getConfigDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getJniLibsDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getDownloadsDirectory();

    @TaskAction
    public void build() {
        getExecOperations().exec(spec -> {
            spec.workingDir(getProject().getRootDir());
            spec.commandLine(
                    "bash",
                    getProject().getRootProject().file("scripts/build-ffmpeg-android.sh").getAbsolutePath(),
                    "--ndk", getNdkPath().get(),
                    "--cmake", getCmakePath().get(),
                    "--output", getNativeRoot().get().getAsFile().getAbsolutePath(),
                    "--abis", getAbis().get(),
                    "--jobs", getJobs().get().toString()
            );
        });
    }
}
