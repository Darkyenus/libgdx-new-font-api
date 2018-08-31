@file:BuildDependencyRepository("jitpack", "https://jitpack.io")
@file:BuildDependency("com.github.Darkyenus", "ResourcePacker", "2.4")
@file:BuildDependency("com.darkyen.wemi", "wemi-plugin-jvm-hotswap", "0.4")

@file:BuildDependency("com.badlogicgames.gdx:gdx-jnigen:1.9.8")
@file:Suppress("unused")

import com.badlogic.gdx.jnigen.*
import com.badlogic.gdx.jnigen.BuildTarget.TargetOs
import com.darkyen.resourcepacker.PackingOperation
import com.darkyen.resourcepacker.PreferSymlinks
import wemi.Archetypes
import wemi.Keys
import wemi.WemiException
import wemi.collections.toMutable
import wemi.compile.JavaCompilerFlags.customFlags
import wemi.dependency.Repository.M2.Companion.Classifier
import wemi.util.LocatedPath
import wemi.util.absolutePath
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.regex.Pattern


val packResources by key<Unit>("Pack resources")
val packResourcesTarget by key<Path>("Output for packed resources")
val jniGen by key<Unit>("Generate JNI stuff")


const val gdxVersion = "1.9.7"

val experimentation by project(Archetypes.JavaProject) {
    projectGroup set {"com.darkyen"}
    projectName set {"experimentation"}
    projectVersion set {"0.0"}

    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx", gdxVersion) }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-backend-lwjgl3", gdxVersion) }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-platform", gdxVersion, Classifier to "natives-desktop") }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-freetype", gdxVersion) }
    libraryDependencies add { dependency("com.badlogicgames.gdx", "gdx-freetype-platform", gdxVersion, Classifier to "natives-desktop") }

    // For proper bidi algorithm and breaking and stuff.
    libraryDependencies add { dependency("com.ibm.icu:icu4j:62.1") }

    extend (compilingJava) {
        compilerOptions[customFlags] += "-Xlint:unchecked"
    }

    runOptions add { "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005" }

    extend (running) {
        unmanagedDependencies add { LocatedPath(path("libs/harfbuzz-natives.jar")) }
    }

    extend (testing) {
        libraryDependencies add { JUnitAPI }
        libraryDependencies add { JUnitEngine }
    }

    if (System.getProperty("os.name").contains("Mac")) {
        runOptions add {"-XstartOnFirstThread"}
    }
    mainClass set {"com.darkyen.libgdx.HarfBuzzTest"}

    packResourcesTarget set { Keys.cacheDirectory.get() / "packed-resources" }

    resourceRoots add {
        packResourcesTarget.get()
    }

    packResources set {
        resourcePack(PackingOperation(path("resources").toFile(), packResourcesTarget.get().toFile(), listOf(PreferSymlinks to true)))
    }

    compile modify { compileOut ->

        fun startProcess(vararg command: String): Boolean {
            println("Executing '${command.joinToString(" ")}'")

            try {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()

                val t = Thread(object : Runnable {
                    override fun run() {
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        try {
                            while (true) {
                                val line = reader.readLine() ?: break
                                // augment output with java file line references :D
                                printFileLineNumber(line)
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }

                    }

                    private fun printFileLineNumber(line: String) {
                        if (line.contains("warning") || line.contains("error")) {
                            try {
                                val fileName = getFileName(line)
                                val error = getError(line)
                                val lineNumber = getLineNumber(line) - 1
                                if (fileName != null && lineNumber >= 0) {
                                    val file = FileDescriptor(fileName)
                                    if (file.exists()) {
                                        val content = file.readString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                                        if (lineNumber < content.size) {
                                            for (i in lineNumber downTo 0) {
                                                val contentLine = content[i]
                                                if (contentLine.startsWith("//@line:")) {
                                                    val javaLineNumber = Integer.parseInt(contentLine.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].trim { it <= ' ' })
                                                    System.out.flush()
                                                    if (line.contains("warning")) {
                                                        println("(" + file.nameWithoutExtension() + ".java:"
                                                                + (javaLineNumber + (lineNumber - i) - 1) + "): " + error + ", original: " + line)
                                                        System.out.flush()
                                                    } else {
                                                        System.err.println("(" + file.nameWithoutExtension() + ".java:"
                                                                + (javaLineNumber + (lineNumber - i) - 1) + "): " + error + ", original: " + line)
                                                        System.err.flush()
                                                    }
                                                    return
                                                }
                                            }
                                        }
                                    } else {
                                        println(line)
                                    }
                                }
                            } catch (t: Throwable) {
                                println(line)
                                // silent death...
                            }

                        } else {
                            println(line)
                        }
                    }

                    private fun getFileName(line: String): String? {
                        val pattern = Pattern.compile("(.*):([0-9])+:[0-9]+:")
                        val matcher = pattern.matcher(line)
                        matcher.find()
                        val fileName = (if (matcher.groupCount() >= 2) matcher.group(1).trim { it <= ' ' } else null)
                                ?: return null
                        val index = fileName.indexOf(" ")
                        return if (index != -1)
                            fileName.substring(index).trim { it <= ' ' }
                        else
                            fileName
                    }

                    private fun getError(line: String): String? {
                        val pattern = Pattern.compile(":[0-9]+:[0-9]+:(.+)")
                        val matcher = pattern.matcher(line)
                        matcher.find()
                        return if (matcher.groupCount() >= 1) matcher.group(1).trim { it <= ' ' } else null
                    }

                    private fun getLineNumber(line: String): Int {
                        val pattern = Pattern.compile(":([0-9]+):[0-9]+:")
                        val matcher = pattern.matcher(line)
                        matcher.find()
                        return if (matcher.groupCount() >= 1) Integer.parseInt(matcher.group(1)) else -1
                    }
                })
                t.isDaemon = true
                t.start()
                process.waitFor()
                return process.exitValue() == 0
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }

        }

        /** Execute the Ant script file with the given parameters.
         * @param buildFile
         * @param params
         * @return whether the Ant succeeded
         */
        fun executeAnt(buildFile: Path, vararg params: String): Boolean {
            val ant = if (System.getProperty("os.name").contains("Windows")) "ant.bat" else "ant"
            return startProcess(ant, "-f", buildFile.absolutePath, *params)
        }

        val jnigen = NativeCodeGenerator()
        jnigen.generate(
                path("src/main/java").absolutePath,
                compileOut.absolutePath+":"+externalClasspath.get().joinToString(":") {it.classpathEntry.absolutePath},
                path("jni").absolutePath,
                sourceFiles.get().map {it.file.absolutePath}.toTypedArray(),
                null)

        //val win32 = BuildTarget.newDefaultTarget(TargetOs.Windows, false)
        //win32.compilerPrefix = "mingw32-"
        //val win64 = BuildTarget.newDefaultTarget(TargetOs.Windows, true)
        //val linux32 = BuildTarget.newDefaultTarget(TargetOs.Linux, false)
        //val linux64 = BuildTarget.newDefaultTarget(TargetOs.Linux, true)
        val mac = BuildTarget.newDefaultTarget(TargetOs.MacOsX, true)
        mac.headerDirs += path("harfbuzz/include").absolutePath
        mac.headerDirs += "/usr/local/include/freetype2/"
        mac.libraries += " -lharfbuzz"
        mac.linkerFlags += " -L${path("harfbuzz/lib").absolutePath}"

        val buildConfig = BuildConfig("harfbuzz")

        AntScriptGenerator().generate(buildConfig, mac)
        //BuildExecutor.executeAnt("jni/build-windows32.xml", "-v -Dhas-compiler=true clean postcompile")
        //BuildExecutor.executeAnt("jni/build-windows64.xml", "-v -Dhas-compiler=true clean postcompile")
        // BuildExecutor.executeAnt("jni/build-linux32.xml", "-v -Dhas-compiler=true clean postcompile");
        // BuildExecutor.executeAnt("jni/build-linux64.xml", "-v -Dhas-compiler=true clean postcompile");
        if (!executeAnt(path("jni/build-macosx64.xml"), "-v", "-Dhas-compiler=true", "clean", "postcompile")
                || !executeAnt(path("jni/build.xml"), "-v", "pack-natives")) {
            throw WemiException.CompilationException("Can't build natives")
        }

        compileOut
    }

}
