import play.sbt.routes.RoutesKeys

name := """ScalaPlayBlog"""

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "redis.clients" % "jedis" % "2.1.0",
  "nl.grons" %% "metrics-scala" % "3.5.1",
  "com.lambdaworks" % "scrypt" % "1.3.3",
  "org.apache.httpcomponents" % "httpcore" % "4.1.2",
  "org.apache.httpcomponents" % "httpclient" % "4.1.2",
  "org.specs2" %% "specs2" % "2.3.12" % "test",
  "com.typesafe.play" %% "play-specs2" % "2.5.0" % "test",
  "org.scalacheck" %% "scalacheck" % "1.10.0" % "test")

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalacOptions := Seq("-deprecation", "-feature", "-unchecked", "-Ywarn-value-discard", "-Ywarn-adapted-args")

// Avoid running tests using both specs2 and junit runner.
testFrameworks in Test := Seq(TestFrameworks.Specs2)

// Override Play! defaults to enable parallel test execution
testOptions in Test := Seq(Tests.Argument(TestFrameworks.Specs2, "junitxml", "console"))

RoutesKeys.routesImport ++= Seq("events._", "eventstore.{ StoreRevision, StreamRevision }", "support.Binders._")

TwirlKeys.templateImports ++= Seq("events._", "eventstore.{ StoreRevision, StreamRevision }")

//includeFilter in (Assets, LessKeys.less) := "bootstrap.less" | "responsive.less"

//excludeFilter in (Assets, LessKeys.less) := "_*.less"

// for minified *.min.css files
LessKeys.compress := true
