resolvers += Resolver.bintrayRepo("sbt", "sbt-plugin-releases")
resolvers += "Artifactory" at "https://placer.jfrog.io/artifactory/placer-mvn-snapshot-local"
credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.15")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.3-placer")