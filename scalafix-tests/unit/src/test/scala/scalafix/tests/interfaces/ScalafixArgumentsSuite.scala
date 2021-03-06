package scalafix.tests.interfaces

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

import scala.collection.JavaConverters._

import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath

import buildinfo.RulesBuildInfo
import org.scalatest.funsuite.AnyFunSuite
import scalafix.interfaces.ScalafixArguments
import scalafix.interfaces.ScalafixDiagnostic
import scalafix.interfaces.ScalafixMainCallback
import scalafix.interfaces.ScalafixMainMode
import scalafix.internal.interfaces.ScalafixArgumentsImpl
import scalafix.internal.rule.RemoveUnused
import scalafix.internal.rule.RemoveUnusedConfig
import scalafix.internal.tests.utils.SkipWindows
import scalafix.test.StringFS
import scalafix.testkit.DiffAssertions
import scalafix.tests.core.Classpaths
import scalafix.tests.util.ScalaVersions
import scalafix.tests.util.SemanticdbPlugin
import scalafix.v1.SemanticRule

class ScalafixArgumentsSuite extends AnyFunSuite with DiffAssertions {
  val scalaBinaryVersion: String =
    RulesBuildInfo.scalaVersion.split('.').take(2).mkString(".")
  val scalaVersion = RulesBuildInfo.scalaVersion
  val removeUnused: String =
    if (ScalaVersions.isScala213)
      "-Wunused:imports"
    else "-Ywarn-unused-import"
  val api: ScalafixArguments = ScalafixArgumentsImpl()

  val charset = StandardCharsets.US_ASCII
  val cwd: Path = StringFS
    .string2dir(
      """|/src/Main.scala
         |import scala.concurrent.duration
         |import scala.concurrent.Future
         |
         |object Main extends App {
         |  import scala.concurrent.Await
         |  println("test");
         |  println("ok")
         |}
      """.stripMargin,
      charset
    )
    .toNIO
  val d: Path = cwd.resolve("out")
  val target: Path = cwd.resolve("target")
  val src: Path = cwd.resolve("src")
  Files.createDirectories(d)
  val main: Path = src.resolve("Main.scala")
  val relativePath: Path = cwd.relativize(main)

  val scalacOptions: Array[String] = Array[String](
    "-Yrangepos",
    removeUnused,
    s"-Xplugin:${SemanticdbPlugin.semanticdbPluginPath()}",
    "-Xplugin-require:semanticdb",
    "-classpath",
    s"${scalaLibrary.mkString(":")}",
    s"-P:semanticdb:sourceroot:$src",
    s"-P:semanticdb:targetroot:$target",
    "-d",
    d.toString,
    main.toString
  )

  test("ScalafixArguments.evaluate with a semantic rule", SkipWindows) {
    val _ = scala.tools.nsc.Main.process(scalacOptions)
    val result = api
      .withRules(
        List(
          removeUnsuedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalaVersion(scalaVersion)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .evaluate()

    val errors = result.getErrors.toList.map(_.toString)
    assert(errors == List("LinterError"))
    assert(result.getFileEvaluations.length == 1)
    val fileEvaluation = result.getFileEvaluations.head
    val expected =
      """|
         |object Main extends App {
         |  println("test");
         |  println("ok")
         |}
         |""".stripMargin
    val obtained = fileEvaluation.previewPatches.get()
    assertNoDiff(obtained, expected)

    val linterError = fileEvaluation.getDiagnostics.toList
    val linterErrorFormatted = linterError
      .map { d =>
        d.position()
          .get()
          .formatMessage(d.severity().toString, d.message())
      }
      .mkString("\n\n")
      .replaceAllLiterally(main.toString, relativePath.toString)
      .replace('\\', '/') // for windows
    assertNoDiff(
      linterErrorFormatted,
      """|src/Main.scala:6:18: ERROR: semicolons are disabled
         |  println("test");
         |                 ^
      """.stripMargin
    )

    val unifiedDiff = fileEvaluation.previewPatchesAsUnifiedDiff.get()
    assert(unifiedDiff.nonEmpty)
    val patches = fileEvaluation.getPatches.toList

    val expectedWithOnePatch =
      """|
         |import scala.concurrent.Future
         |
         |object Main extends App {
         |  import scala.concurrent.Await
         |  println("test");
         |  println("ok")
         |}
         |""".stripMargin
    // if applying all patches we should get the same result
    val obtained2 =
      fileEvaluation.previewPatches(patches.toArray).get()
    assertNoDiff(obtained2, expected)

    val obtained3 = fileEvaluation
      .previewPatches(Seq(patches.head).toArray)
      .get
    assertNoDiff(obtained3, expectedWithOnePatch)

  }

  test(
    "ScalafixArguments.evaluate doesn't take into account withMode and withMainCallback",
    SkipWindows
  ) {
    val _ = scala.tools.nsc.Main.process(scalacOptions)
    val contentBeforeEvaluation =
      FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    var maybeDiagnostic: Option[ScalafixDiagnostic] = None
    val scalafixMainCallback = new ScalafixMainCallback {
      override def reportDiagnostic(diagnostic: ScalafixDiagnostic): Unit =
        maybeDiagnostic = Some(diagnostic)
    }
    val result = api
      .withRules(
        List(
          removeUnsuedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalaVersion(scalaVersion)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .withMode(ScalafixMainMode.IN_PLACE)
      .withMainCallback(scalafixMainCallback)
      .evaluate()

    val fileEvaluation = result.getFileEvaluations.toSeq.head
    assert(fileEvaluation.getDiagnostics.toSeq.nonEmpty)
    assert(maybeDiagnostic.isEmpty)
    val content = FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    assert(contentBeforeEvaluation == content)
    val run = api
      .withRules(
        List(
          removeUnsuedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalaVersion(scalaVersion)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .withMode(ScalafixMainMode.IN_PLACE)
      .withMainCallback(scalafixMainCallback)
      .run()

    val contentAfterRun =
      FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    assert(contentAfterRun == fileEvaluation.previewPatches().get)
  }

  def removeUnsuedRule(): SemanticRule = {
    val config = RemoveUnusedConfig.default
    new RemoveUnused(config)
  }

  def scalaLibrary: Seq[AbsolutePath] = Classpaths.scalaLibrary.entries

}
