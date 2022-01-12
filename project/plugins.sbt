resolvers += Resolver.bintrayRepo("sbt", "sbt-plugin-releases")
credentials += Credentials(Path.userHome / ".sbt" / ".credentials")

addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.15")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")