package com.twitter.sbt

import _root_.sbt._
import java.io.{FileWriter, File}
import java.util.{Date, Properties}
import java.util.jar.Attributes
import java.text.SimpleDateFormat
import scala.collection.jcl

trait StandardManagedProject extends BasicManagedProject
  with SourceControlledProject with ReleaseManagement with Versions
  with PublishLocalWithMavenStyleBasePattern
{
  override def disableCrossPaths = true
  override def managedStyle = ManagedStyle.Maven
}

class StandardProject(info: ProjectInfo) extends DefaultProject(info) with StandardManagedProject {
  override def dependencyPath = "libs"
  def timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date)

  val environment = jcl.Map(System.getenv())

  // override ivy cache
  override def ivyCacheDirectory = environment.get("SBT_CACHE").map { cacheDir =>
    Path.fromFile(new File(cacheDir))
  }

  // override me for releases!
  def releaseBuild = false

  // local repositories
  val localLibs = Resolver.file("local-libs", new File("libs"))(Patterns("[artifact]-[revision].[ext]")) transactional()

  // make a build.properties file and sneak it into the packaged jar.
  def buildPackage = organization + "." + name
  def packageResourcesPath = buildPackage.split("\\.").foldLeft(mainResourcesOutputPath ##) { _ / _ }
  def buildPropertiesPath = packageResourcesPath / "build.properties"
  override def packagePaths = super.packagePaths +++ buildPropertiesPath

  def writeBuildPropertiesTask = task {
    packageResourcesPath.asFile.mkdirs()
    val buildProperties = new Properties
    buildProperties.setProperty("name", name)
    buildProperties.setProperty("version", version.toString)
    buildProperties.setProperty("build_name", timestamp)
    currentRevision.foreach(buildProperties.setProperty("build_revision", _))
    val fileWriter = new FileWriter(buildPropertiesPath.asFile)
    buildProperties.store(fileWriter, "")
    fileWriter.close()
    None
  }.dependsOn(copyResources)

  val WriteBuildPropertiesDescription = "Writes a build.properties file into the target folder."
  lazy val writeBuildProperties = writeBuildPropertiesTask dependsOn(copyResources) describedAs WriteBuildPropertiesDescription

  // need to add mainResourcesOutputPath so the build.properties file can be found.
  override def consoleAction = interactiveTask {
    val console = new Console(buildCompiler)
    val classpath = consoleClasspath +++ mainResourcesOutputPath
    console(classpath.get, compileOptions.map(_.asString), "", log)
  } dependsOn(writeBuildProperties)

  // need to add mainResourcesOutputPath so the build.properties file can be found.
  override def runAction = task { args => runTask(getMainClass(true), runClasspath +++ mainResourcesOutputPath, args) dependsOn(compile, writeBuildProperties) }

  // workaround bug in sbt that hides scala-compiler.
  override def filterScalaJars = false

  // build the executable jar's classpath.
  // (why is it necessary to explicitly remove the target/{classes,resources} paths? hm.)
  def dependentJars = {
    val jars =
      publicClasspath +++ mainDependencies.scalaJars --- mainCompilePath --- mainResourcesOutputPath
    if (jars.get.find { jar => jar.name.startsWith("scala-library-") }.isDefined) {
      // workaround bug in sbt: if the compiler is explicitly included, don't include 2 versions
      // of the library.
      jars --- jars.filter { jar =>
        jar.absolutePath.contains("/boot/") && jar.name == "scala-library.jar"
      }
    } else {
      jars
    }
  }
  def dependentJarNames = dependentJars.getFiles.map(_.getName).filter(_.endsWith(".jar"))
  override def manifestClassPath = Some(dependentJarNames.map { "libs/" + _ }.mkString(" "))

  def distName = if (releaseBuild) (name + "-" + version) else name
  def distPath = "dist" / distName ##

  def configPath = "config" ##
  def configOutputPath = distPath / "config"

  def scriptsPath = "src" / "scripts" ##
  def scriptsOutputPath = distPath / "scripts"

  def distZipName = {
    val revName = currentRevision.map(_.substring(0, 8)).getOrElse(version)
    "%s-%s.zip".format(name, if (releaseBuild) version else revName)
  }

  // thrift generation.
  def compileThriftAction(lang: String) = task {
    import Process._
    outputPath.asFile.mkdirs()
    val thriftBin = environment.get("THRIFT_BIN").getOrElse("thrift")
    val tasks = thriftSources.getPaths.map { path =>
      execTask { "%s --gen %s -o %s %s".format(thriftBin,lang, outputPath.absolutePath, path) }
    }
    if (tasks.isEmpty) None else tasks.reduceLeft { _ && _ }.run
  }

  def thriftSources = (mainSourcePath / "thrift" ##) ** "*.thrift"
  def thriftJavaPath = outputPath / "gen-java"
  def thriftRubyPath = outputPath / "gen-rb"

  // turn on more warnings.
  override def compileOptions = super.compileOptions ++ Seq(Unchecked)

  lazy val cleanThrift = (cleanTask(thriftJavaPath) && cleanTask(thriftRubyPath)) describedAs("Clean thrift generated folder")
  lazy val compileThriftJava = compileThriftAction("java") describedAs("Compile thrift into java")
  lazy val compileThriftRuby = compileThriftAction("rb") describedAs("Compile thrift into ruby")

  /** override to disable auto-compiling of thrift */
  def autoCompileThriftEnabled = true

  lazy val autoCompileThriftJava = task {
    if (autoCompileThriftEnabled) compileThriftJava.run else None
  }

  lazy val autoCompileThriftRuby = task {
    if (autoCompileThriftEnabled) compileThriftRuby.run else None
  }

  override def compileOrder = CompileOrder.JavaThenScala
  override def mainSourceRoots = super.mainSourceRoots +++ (outputPath / "gen-java" ##)

  // copy scripts.
  val CopyScriptsDescription = "Copies scripts into the dist folder."
  val copyScripts = task {
    val filters = Map(
      "CLASSPATH" -> (publicClasspath +++ mainDependencies.scalaJars).getPaths.mkString(":"),
      "TEST_CLASSPATH" -> testClasspath.getPaths.mkString(":"),
      "DIST_CLASSPATH" -> (dependentJarNames.map { "${DIST_HOME}/libs/" + _ }.mkString(":") +
        ":${DIST_HOME}/" + defaultJarName),
      "DIST_NAME" -> name,
      "VERSION" -> version.toString
    )

    scriptsOutputPath.asFile.mkdirs()
    (scriptsPath ***).filter { !_.isDirectory }.get.foreach { path =>
      val dest = Path.fromString(scriptsOutputPath, path.relativePath)
      new File(dest.absolutePath.toString).getParentFile().mkdirs()
      FileFilter.filter(path, dest, filters)
      Runtime.getRuntime().exec(List("chmod", "+x", dest.absolutePath.toString).toArray).waitFor()
    }
    None
  } named("copy-scripts") dependsOn(`compile`) describedAs CopyScriptsDescription

  /**
   * copy into dist:
   * - packaged jar
   * - pom file for export
   * - dependent libs
   * - config files
   * - scripts
   */
  def packageDistTask = task {
    distPath.asFile.mkdirs()
    (distPath / "libs").asFile.mkdirs()
    configOutputPath.asFile.mkdirs()

    FileUtilities.copyFlat(List(jarPath), distPath, log).left.toOption orElse
      FileUtilities.copyFlat(dependentJars.get, distPath / "libs", log).left.toOption orElse
      FileUtilities.copy((configPath ***).get, configOutputPath, log).left.toOption orElse
      FileUtilities.copy(((outputPath ##) ** "*.pom").get, distPath, log).left.toOption orElse
      FileUtilities.zip((("dist" / distName) ##).get, "dist" / distZipName, true, log)
  }

  val PackageDistDescription = "Creates a deployable zip file with dependencies, config, and scripts."
  lazy val packageDist = packageDistTask dependsOn(`package`, makePom, copyScripts) describedAs PackageDistDescription

  override def testOptions = {
    if (environment.get("NO_TESTS").isDefined || environment.get("NO_TEST").isDefined) {
      List(TestFilter(_ => false))
    } else {
      Nil
    } ++ super.testOptions
  }

  lazy val checkDepsExist = task {
    if (!libraryDependencies.isEmpty && !managedDependencyRootPath.asFile.exists) {
      Some("You must run 'sbt update' first to download dependent jars.")
    } else if (!(organization contains ".")) {
      Some("Your organization name doesn't look like a valid package name. It needs to be something like 'com.example'.")
    } else {
      None
    }
  }

  override def compileAction = super.compileAction dependsOn(checkDepsExist, autoCompileThriftJava, autoCompileThriftRuby)
  override def packageAction = super.packageAction dependsOn(testAction, writeBuildProperties)

  val cleanDist = cleanTask("dist" ##) describedAs("Erase any packaged distributions.")
  override def cleanAction = super.cleanAction dependsOn(cleanThrift, cleanDist)

  // generate ensime config
  lazy val genEnsime = task (args => {
    if (args.length == 1) {
      genEnsimeConstructor(args(0).toString)
    } else {
      task { Some("Usage: gen-ensime <project package name>") }
    }
  }) describedAs("Generate a .ensime file for this project")

  def genEnsimeConstructor(packageName: String) = task {
    val ensime = new StringBuffer()
    // if you have more libs, override this bit
    val jarDirs = List(dependencyPath, crossPath("lib_managed") + "/compile", crossPath("lib_managed") + "/test").map("\"" + _ + "\"")
    // ditto with more src dirs
    val srcDirs = List(thriftJavaPath.toString).map("\"" + _ + "\"")

    ensime.append(";; this config was generated by standard-project.  Feel free to customize!\n")
    ensime.append("(\n")
    ensime.append(":project-package \"").append(packageName).append("\"\n")
    ensime.append(":use-sbt t\n")
    ensime.append(":compile-jars (").append(jarDirs.mkString(" ")).append(")\n")
    ensime.append(":sources (").append(srcDirs.mkString(" ")).append(")\n")
    ensime.append(")\n")

    // rename old file (if it exists)
    val oldFile = new File(".ensime")
    if (oldFile.exists) {
      val newFile = new File(".ensime-%d".format(System.currentTimeMillis()))
      oldFile.renameTo(newFile)
    }
    // and dump our config
    val newFile = new File(".ensime")
    val writer = new FileWriter(newFile)
    writer.write(ensime.toString())
    writer.close()
    None
  }

  log.info("Standard project rules " + BuildInfo.version + " loaded (" + BuildInfo.date + ").")
}

class StandardParentProject(info: ProjectInfo) extends ParentProject(info) with StandardManagedProject {
  override def usesMavenStyleBasePatternInPublishLocalConfiguration = false
}
