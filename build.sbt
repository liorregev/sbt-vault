import sbt.io.Path.userHome

ThisBuild / organization := "com.github.liorregev"
ThisBuild / homepage := Some(url("https://github.com/sbt/sbt-hello"))
ThisBuild / resolvers += "jitpack" at "https://jitpack.io"
ThisBuild / licenses := Seq(
  ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
)

val debugMode = false

lazy val LocalMavenResolverForSbtPlugins = {
  // remove scala and sbt versions from the path, as it does not work with jitpack
  val pattern  = "[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"
  val name     = "local-maven-for-sbt-plugins"
  val location = userHome / ".m2" / "repository"
  Resolver.file(name, location)(Patterns().withArtifactPatterns(Vector(pattern)))
}

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-vault",
    version := "0.0.1",
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.2.8" // set minimum sbt version
      }
    },
    libraryDependencies ++= Seq(
      "com.google.cloud"               % "google-cloud-iamcredentials" % "2.0.5",
      "com.softwaremill.sttp.client3" %% "core"                        % "3.3.16",
      "com.typesafe.play"             %% "play-json"                   % "2.9.2",
      "com.bettercloud"                % "vault-java-driver"           % "5.1.0"
    ),
    scriptedLaunchOpts := { scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value) ++
      (if(debugMode) Seq("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005") else Nil)
    },
    scriptedBufferLog := false,
    publishMavenStyle := true,
    resolvers += LocalMavenResolverForSbtPlugins,
    publishM2Configuration := publishM2Configuration.value.withResolverName(LocalMavenResolverForSbtPlugins.name)
  )