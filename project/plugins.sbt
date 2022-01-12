resolvers += Resolver.bintrayRepo("sbt", "sbt-plugin-releases")

addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.15")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.10")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.1.2")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.2")