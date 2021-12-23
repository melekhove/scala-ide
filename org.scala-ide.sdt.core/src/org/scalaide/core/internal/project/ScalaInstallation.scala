package org.scalaide.core.internal.project

import java.net.URLClassLoader
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import scala.collection.mutable.ListBuffer
import scala.Left
import scala.Right
import scala.collection.mutable.Set
import scala.tools.nsc.settings.ScalaVersion
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Platform
import org.eclipse.jdt.core.IClasspathEntry
import org.eclipse.jdt.core.JavaCore
import org.osgi.framework.Bundle
import org.osgi.framework.Version
import org.scalaide.core.IScalaInstallation
import org.scalaide.core.IScalaInstallationChoice
import org.scalaide.core.IScalaModule
import org.scalaide.core.internal.ScalaPlugin
import org.scalaide.util.eclipse.EclipseUtils
import org.scalaide.util.eclipse.OSGiUtils
import org.scalaide.util.internal.CompilerUtils.isBinarySame
import org.scalaide.util.internal.CompilerUtils.shortString

import sbt.internal.inc.ScalaInstance
import java.net.URL

sealed trait ScalaInstallationLabel extends Serializable
case class BundledScalaInstallationLabel() extends ScalaInstallationLabel
case class MultiBundleScalaInstallationLabel() extends ScalaInstallationLabel
case class CustomScalaInstallationLabel(label: String) extends ScalaInstallationLabel

/**
 *  A type that marks the choice of a Labeled Scala Installation : either a Scala Version,
 *  which will dereference to the latest available bundle with the same binary version, or
 *  a scala installation hashcode, which will dereference to the Labeled installation which
 *  hashes to it, if available.
 *
 *  @see ScalaInstallation.resolve
 */
case class ScalaInstallationChoice(marker: Either[ScalaVersion, Int]) extends Serializable with IScalaInstallationChoice {

  override def toString() = marker match {
    case Left(version) => shortString(version)
    case Right(hash) => hash.toString
  }

  override def equals(o: Any) = PartialFunction.cond(o) {
    case that: ScalaInstallationChoice => (marker, that.marker) match {
      case (Right(h1), Right(h2)) => h1 == h2
      case (Left(v1), Left(v2)) => isBinarySame(v1, v2)
      case _ => false
    }
  }
}

object ScalaInstallationChoice {
  def apply(si: LabeledScalaInstallation): ScalaInstallationChoice = ScalaInstallationChoice(Right(si.hashString.hashCode()))
  def apply(sv: ScalaVersion): ScalaInstallationChoice = ScalaInstallationChoice(Left(sv))
}

/**
 * This class represents a valid Scala installation. It encapsulates
 *  a Scala version and paths to the standard Scala jar files:
 *
 *  - scala-library.jar
 *  - scala-compiler.jar
 *  - scala-reflect.jar
 *  - others (actors, swing, etc.)
 */
trait ScalaInstallation extends IScalaInstallation {

  /** The version of Scala */
  def version: ScalaVersion

  def compiler: ScalaModule

  def library: ScalaModule

  def extraJars: Seq[ScalaModule]

  /**
   * All jars provided by Scala (including the compiler)
   *  @see The note in [[MultiBundleScalaInstallation]] below
   */
  def allJars: Seq[ScalaModule] =
    library +: compiler +: extraJars

  override def toString() =
    s"Scala $version: \n\t${allJars.mkString("\n\t")})"

  def isValid(): Boolean = {
    allJars forall (_.isValid())
  }

}

/**
 *  A tag for serializable tagging of Scala Installations
 */
trait LabeledScalaInstallation extends ScalaInstallation {
  def label: ScalaInstallationLabel
  // to recover bundle-less Bundle values from de-serialized Scala Installations
  // this should be relaxed for bundles : our bundles are safe, having one with just the same version should be enough
  def similar(that: LabeledScalaInstallation): Boolean =
    this.label == that.label && this.compiler == that.compiler && this.library == that.library && this.extraJars.toSet == that.extraJars.toSet

  def getName(): Option[String] = PartialFunction.condOpt(label) { case CustomScalaInstallationLabel(tag) => tag }
  def hashString: String = {
    val jarSeq = allJars map (_.hashString)
    getName().fold(jarSeq)(str => str +: jarSeq).mkString
  }

  override def hashCode() = hashString.hashCode()
  override def equals(o: Any) = PartialFunction.cond(o) { case lsi: LabeledScalaInstallation => lsi.hashCode() == this.hashCode() }
}

case class ScalaModule(classJar: IPath, sourceJar: Option[IPath]) extends IScalaModule {

  def isValid(): Boolean = {
    sourceJar.fold(List(classJar))(List(_, classJar)) forall { path => path.toFile().isFile() }
  }

  def libraryEntries(): IClasspathEntry = {
    JavaCore.newLibraryEntry(classJar, sourceJar.orNull, null)
  }

  private def relativizedString(path: IPath) = {
    path.makeRelativeTo(ScalaPlugin().getStateLocation()).toPortableString()
  }
  def hashString: String = sourceJar.map { relativizedString }.fold(relativizedString(classJar))(s => relativizedString(classJar) + s)
}

object ScalaModule {
  def apply(bundleId: String, classJar: IPath): ScalaModule = {
    ScalaModule(classJar, EclipseUtils.computeSourcePath(bundleId, classJar))
  }
}

/**
 * Represent a version of Scala installed as a bundle containing the necessary jars.
 */
case class BundledScalaInstallation(
    override val version: ScalaVersion,
    bundle: Bundle,
    override val library: ScalaModule,
    override val compiler: ScalaModule,
    override val extraJars: Seq[ScalaModule]) extends LabeledScalaInstallation {

  override val label = BundledScalaInstallationLabel()
  def osgiVersion = bundle.getVersion()
}

object BundledScalaInstallation {

  private val ScalaBundleRegex = "org\\.scala-ide\\.scala-sdk\\.scala-[0-9]{1,2}\\.[0-9]{1,2}(\\.[0-9]{1,2})?".r
  private val ScalaLibraryRegex = "scala-library-[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}\\.jar".r
  private val ScalaLibrarySourcesRegex = "scala-library-[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}-sources\\.jar".r
  private val ScalaCompilerRegex = "scala-compiler-[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}\\.jar".r
  private val ScalaCompilerSourcesRegex = "scala-compiler-[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}-sources\\.jar".r

  def apply(bundle: Bundle): Option[BundledScalaInstallation] = {
    
    def urlToPath(url: URL): IPath = Path.fromOSString(FileLocator.toFileURL(url).getPath)

    if (!OSGiUtils.getBundlePath(bundle).fold(false)(_.toFile().isDirectory())){
      return None
    }
    
    val sourcesSuffix = "-sources.jar"
    var scalaLibrary: IPath = null
    var scalaLibrarySources: Option[IPath] = None
    var scalaCompiler: IPath = null
    var scalaCompilerSources: Option[IPath] = None
    val extraJars = new ListBuffer[IPath]
    val extraSources = new ListBuffer[IPath]

    val version = ScalaVersion(bundle.getVersion().toString())
    val bundleJars = bundle.findEntries("./", "*.jar", false)
    while (bundleJars.hasMoreElements()) {
      val modulePath = urlToPath(bundleJars.nextElement());
      val moduleName = modulePath.lastSegment();
      if (ScalaLibraryRegex.matches(moduleName)) {
        scalaLibrary = modulePath
      } else if (ScalaLibrarySourcesRegex.matches(moduleName)) {
        scalaLibrarySources = Some(modulePath)
      } else if (ScalaCompilerRegex.matches(moduleName)) {
        scalaCompiler = modulePath
      } else if (ScalaCompilerSourcesRegex.matches(moduleName)) {
        scalaCompilerSources = Some(modulePath)
      } else {
        if (moduleName.endsWith(sourcesSuffix)) {
          extraSources += modulePath
        } else {
          extraJars += modulePath
        }
      }
    }

    if (scalaLibrary == null || scalaCompiler == null) {
      None
    } else {
      val extraModules = extraJars.map(binaryJarPath => {
        val binaryJarName = binaryJarPath.lastSegment()
        val sourcesJarName = binaryJarName.substring(0, binaryJarName.length() - 4) + sourcesSuffix
        ScalaModule(binaryJarPath, extraSources.find(_.lastSegment() == sourcesJarName))
      })
      Some(BundledScalaInstallation(
        version,
        bundle,
        ScalaModule(scalaLibrary, scalaLibrarySources),
        ScalaModule(scalaCompiler, scalaCompilerSources),
        extraModules.toList))
    }
  }

  /**
   * Find and return the complete bundled Scala installations.
   */
  def detectBundledInstallations(): List[BundledScalaInstallation] = {
    val matchingBundles =
      ScalaPlugin().getBundle().getBundleContext().getBundles()
        .filter { b => ScalaBundleRegex.unapplySeq(b.getSymbolicName()).isDefined }
        .toList
    matchingBundles.flatMap(BundledScalaInstallation(_))
  }
}

/**
 * Represent a version of Scala installed as a set of bundles, each bundle with an identical version.
 *
 *  TODO: We SHOULD reuse the current class loader if this installation is the platform installation.
 *
 *  @note We don't reuse it because of weird interactions between the OSGi classloader and the compiler-bridge.jar,
 *        resulting in AbstractMethodErrors. The `Reporter` interface is defined in scala-reflect, but implemented in
 *        compiler-bridge.jar (which is NOT a bundle), and `info0` is not seen.
 *
 *        See ticket #1002175
 */
case class MultiBundleScalaInstallation(
    override val version: ScalaVersion,
    libraryBundleVersion: Version,
    override val library: ScalaModule,
    override val compiler: ScalaModule) extends LabeledScalaInstallation {

  import MultiBundleScalaInstallation._

  override val label = MultiBundleScalaInstallationLabel()
  def osgiVersion = libraryBundleVersion

  override lazy val extraJars = Seq(
    findLibraryForBundle(ScalaReflectBundleId, libraryBundleVersion),
    findLibraryForBundle(ScalaSwingBundleId, libraryBundleVersion)).flatten
}

object MultiBundleScalaInstallation {

  val ScalaLibraryBundleId = "org.scala-lang.scala-library"
  val ScalaCompilerBundleId = "org.scala-lang.scala-compiler"
  val ScalaReflectBundleId = "org.scala-lang.scala-reflect"
  val ScalaXmlBundleId = "org.scala-lang.modules.scala-xml"
  val ScalaCollectionParallelBundleId = "org.scala-lang.modules.scala-parallel-collections";
  val ScalaParserCombinatorsBundleId = "org.scala-lang.modules.scala-parser-combinators"
  val ScalaSwingBundleId = "org.scala-lang.scala-swing"

  private def bundlePath(bundle: Bundle) =
    Path.fromOSString(FileLocator.getBundleFile(bundle).getAbsolutePath())

  private def findBundle(bundleId: String, version: Version): Option[Bundle] = {
    def doesBundleVersionQualifierEncloseVersionQualifier(bundleQualifier: String, qualifier: String) =
      qualifier.intersect(bundleQualifier) == qualifier
    Option(Platform.getBundles(bundleId, null)).getOrElse(Array[Bundle]()).find { bundle =>
      val bundleVersion = bundle.getVersion
      bundleVersion.getMajor == version.getMajor &&
        bundleVersion.getMinor == version.getMinor &&
        bundleVersion.getMicro == version.getMicro &&
        doesBundleVersionQualifierEncloseVersionQualifier(bundleVersion.getQualifier, version.getQualifier)
    }
  }

  private def findLibraryForBundle(bundleId: String, version: Version): Option[ScalaModule] = {
    val classPath = findBundle(bundleId, version).map(bundlePath)
    classPath.map(cp => ScalaModule(cp, EclipseUtils.computeSourcePath(bundleId, cp)))
  }

  def apply(libraryBundle: Bundle): Option[MultiBundleScalaInstallation] = {
    val libraryBundleVersion = libraryBundle.getVersion()

    for {
      version <- ScalaInstallation.extractVersion(bundlePath(libraryBundle))
      library = bundlePath(libraryBundle)
      compiler <- findLibraryForBundle(ScalaCompilerBundleId, libraryBundleVersion)
    } yield MultiBundleScalaInstallation(
      version,
      libraryBundleVersion,
      ScalaModule(bundlePath(libraryBundle), EclipseUtils.computeSourcePath(ScalaLibraryBundleId, library)),
      compiler)
  }

  def detectInstallations(): List[MultiBundleScalaInstallation] = {

    val scalaLibraryBundles = Platform.getBundles(ScalaLibraryBundleId, null).toList

    scalaLibraryBundles.flatMap(MultiBundleScalaInstallation(_))
  }
}

object ScalaInstallation {

  val installationsTracker = new ScalaInstallationSaver()
  private def savedScalaInstallations() = Try(installationsTracker.getSavedInstallations())
  lazy val initialScalaInstallations = savedScalaInstallations() match {
    case Success(sis) => sis filter (_.isValid()) filter { deserial => !(bundledInstallations ++ multiBundleInstallations exists (_.similar(deserial))) }
    // we need to silently fail, as this happens early in initialization
    case Failure(_) => Nil
  }

  // This lets you see installs retrieved from serialized bundles as newly-defined custom installations
  private def customize(install: LabeledScalaInstallation) = install.label match {
    case CustomScalaInstallationLabel(_) => install
    case BundledScalaInstallationLabel() | MultiBundleScalaInstallationLabel() => new LabeledScalaInstallation() {
      override def label = new CustomScalaInstallationLabel(s"Scala (legacy with hash ${ScalaInstallationChoice(install).toString()})")
      override def compiler = install.compiler
      override def library = install.library
      override def extraJars = install.extraJars
      override def version = install.version
    }
  }

  def scalaInstanceForInstallation(si: IScalaInstallation): ScalaInstance = {
    val store = ScalaPlugin().classLoaderStore

    // TODO: upgrade to zinc 1.6 It compiles bridge, but additional work is necessary to make sure it is correct
    val scalaLoader: ClassLoader = store.getOrUpdate(si)(new URLClassLoader(si.allJars.map(_.classJar.toFile.toURI.toURL).toArray, ClassLoader.getSystemClassLoader))
    val loaderCompilerOnly = new URLClassLoader(Array[java.net.URL](
          si.compiler.classJar.toFile.toURI.toURL,
          si.library.classJar.toFile.toURI.toURL),
        ClassLoader.getSystemClassLoader)
    val loaderLibraryOnly = new URLClassLoader(Array[java.net.URL](si.library.classJar.toFile.toURI.toURL), ClassLoader.getSystemClassLoader)

    new ScalaInstance(
      si.version.unparse,
      scalaLoader,
      scalaLoader,
      loaderLibraryOnly,
      Array[java.io.File](si.library.classJar.toFile),
      Array[java.io.File](si.compiler.classJar.toFile),
      si.extraJars.map(_.classJar.toFile).toArray,
      None)
  }

  lazy val customInstallations: Set[LabeledScalaInstallation] = initialScalaInstallations.view.map(customize(_)).to(Set)

  /** Return the Scala installation currently running in Eclipse. */
  lazy val platformInstallation: LabeledScalaInstallation =
    multiBundleInstallations.find(_.version == ScalaVersion.current).get

  lazy val bundledInstallations: List[LabeledScalaInstallation] =
    BundledScalaInstallation.detectBundledInstallations()

  lazy val multiBundleInstallations: List[LabeledScalaInstallation] =
    MultiBundleScalaInstallation.detectInstallations()

  def availableBundledInstallations: List[LabeledScalaInstallation] = {
    //TODO disable multiBundle installations for now
    //perhaps it makes sence to get rid of them at all
    /*multiBundleInstallations ++ */
    bundledInstallations
  }

  def availableInstallations: List[LabeledScalaInstallation] = {
    multiBundleInstallations ++ bundledInstallations ++ customInstallations
  }

  val LibraryPropertiesPath = "library.properties"

  def labelInFile(scalaPath: IPath): Option[String] = {
    val scalaJarRegex = """scala-(\w+)(?:.2\.\d+(?:\.\d*)?(?:-.*)?)?.jar""".r
    scalaPath.toFile().getName() match {
      case scalaJarRegex(qualifier) => Some(qualifier + ".properties")
      case _ => None
    }
  }

  def extractVersion(scalaLibrary: IPath): Option[ScalaVersion] = {
    val propertiesPath = labelInFile(scalaLibrary).getOrElse(LibraryPropertiesPath)
    val zipFile = new ZipFile(scalaLibrary.toFile())
    try {
      def getVersion(propertiesEntry: ZipEntry) = {
        val properties = new Properties()
        properties.load(zipFile.getInputStream(propertiesEntry))
        Option(properties.getProperty("version.number"))
      }

      for {
        propertiesEntry <- Option(zipFile.getEntry(propertiesPath))
        version <- getVersion(propertiesEntry)
      } yield ScalaVersion(version)
    } finally {
      zipFile.close()
    }

  }

  def resolve(choice: IScalaInstallationChoice): Option[LabeledScalaInstallation] = choice.marker match {
    case Left(version) => availableBundledInstallations.filter { si => isBinarySame(version, si.version) }.sortBy(_.version).lastOption
    case Right(hash) => availableInstallations.find(si => ScalaInstallationChoice(si).toString equals hash.toString())
  }

}
