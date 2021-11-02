package com.liorregev.sbt

import sbt._
import Keys._
import com.bettercloud.vault.{Vault, VaultConfig}

import scala.collection.JavaConverters._

object VaultSbt extends AutoPlugin {
  object autoImport {
    val resolveCreds = taskKey[Unit]("say hello")
  }

  import autoImport._
  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    resolveCreds := {
      val s = streams.value
      val config = new VaultConfig()
        .address("http://127.0.0.1:8200")
        .token("s.u5nlha7girpxOiQbnxmGEDrJ")
        .build()
      val vault = new Vault(config)
      val secret = vault.logical().read("secret/services/jfrog").getData.asScala
      s.log.info(secret.mkString(","))
    }
  )
}
