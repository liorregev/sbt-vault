import sbt.Keys.versionScheme

val debugMode = false

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin, GitVersioning)
  .settings(
    name := "sbt-vault",
    organization := "io.github.liorregev",
    homepage := Some(url("https://github.com/sbt/sbt-hello")),
    resolvers += "jitpack" at "https://jitpack.io",
    licenses := Seq(
      ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))
    ),
    scmInfo := Some(ScmInfo(url("https://github.com/liorregev/sbt-vault"), "git@github.com:liorregev/sbt-vault.git")),
    developers := List(Developer("liorregev", "Lior Regev", "lioregev@gmail.com", url("https://github.com/liorregev"))),
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.2.8" // set minimum sbt version
      }
    },
    git.useGitDescribe := true,
    versionScheme := Some("semver-spec"),
    credentials += Credentials(
      "GnuPG Key ID",
      "gpg",
      "0626E80667A0E5FF39B5C10DAF7A50CD7595825D",
      "ignored"
    ),
    credentials += Credentials(Path.userHome / ".sbt" / ".sonatype_credentials"),
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
    sbtPlugin := true,
    publishMavenStyle := true,
    publishTo := Some(
      if (isSnapshot.value)
        "Sonatype Snapshots Nexus" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
      else
        "Sonatype Snapshots Nexus" at "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
    )

  )