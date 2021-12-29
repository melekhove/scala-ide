package org.scalaide.core

import org.eclipse.core.runtime.IPath
import scala.tools.nsc.settings.ScalaVersion
import org.eclipse.jdt.core.IClasspathEntry

/**
 * This trait represents a handle on a Scala module, and its component jars.
 * e.g. scala-compiler, scala-library, scala-reflect, scala-xml ...
 * Immutable.
 */
trait IScalaModule {
  val classJar: IPath
  val sourceJar: Option[IPath]
  /**
   * Are the files pointed to by this module available on the file system ?
   */
  def isValid(): Boolean
  /**
   * Creates a classpath entry for the library.
   */
  def libraryEntries(): IClasspathEntry
  /**
   * Returns a hash string uniquely identifying the module.
   * Depends on the path of contained archives relative to the Scala plugin's location.
   */
  def hashString: String
}

/**
 * This trait represents a handle on a complete Scala installation, containing at least compiler and library modules.
 * Immutable.
 */
trait IScalaInstallation {
  /**
   * A precise Scala version.
   */
  def version: ScalaVersion
  /**
   *  The compiler module itself.
   */
  def compilerModules: Seq[IScalaModule]
  /**
   * The library module for this installation.
   */
  def libraryModules: Seq[IScalaModule]
  /**
   * Extra modules, e.g. reflect, swing, actors, xml.
   */
  def extraModules: Seq[IScalaModule]
  /**
   * Returns the whole set of all jars included in this installation.
   */
  def allModules: Seq[IScalaModule]

  /**
   * The binary of the compiler bridge if available
   */
  def compilerBridge: Option[IScalaModule]

  /**
   * Are the registered components of this installation available on the file system ?
   */
  def isValid(): Boolean
}


/**
 * This trait symbolizes a Scala Installation Choice.
 * Commonly implemented as a case class with several utility methods.
 * The marker constitutes the choice, it can be :
 * - either a Scala version, in which case the Scala Installation to be used will be
 *   the latest available bundle with the same binary-compatible version (same major, minor) as the one specified
 * - either an Int representing a hash, which points to the hash of an available Scala Installation.
 *
 * Immutable.
 */
trait IScalaInstallationChoice{
  val marker: Either[ScalaVersion, Int]
}