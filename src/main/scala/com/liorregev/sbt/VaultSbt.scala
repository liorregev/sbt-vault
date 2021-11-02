package com.liorregev.sbt

import com.bettercloud.vault.api.Auth
import com.bettercloud.vault.{Vault, VaultConfig}
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters._

object VaultSbt {
  sealed trait LoginMethod
  object LoginMethods {
    case object None extends LoginMethod
    final case class UserPass(user: String, password: String) extends LoginMethod
  }
  object VaultKeys {
    val vaultAddress = settingKey[String]("Address of the Vault server")
    val loginMethod = settingKey[LoginMethod]("Method to log in with")
    val resolveCreds = taskKey[Unit]("say hello")
  }

  object vault {
    val vaultAddress = VaultKeys.vaultAddress
    val resolveCredentials = VaultKeys.resolveCreds
    val loginMethod = VaultKeys.loginMethod
    val loginMethods: LoginMethods.type = LoginMethods
  }

  import VaultKeys._

  def loadToken(config: VaultConfig): LoginMethod => String = {
    case LoginMethods.None => config.getToken
    case LoginMethods.UserPass(user, password) =>
      new Auth(config).loginByUserPass(user, password).getAuthClientToken
  }

  def projectSettings: Seq[Setting[_]] = Seq(
    vaultAddress := "",
    loginMethod := LoginMethods.None,
    resolveCreds := {
      val s = streams.value
      val config = new VaultConfig()
        .address(vaultAddress.value)
      val token = loadToken(config.build())(loginMethod.value)
      val vaultClient = new Vault(config.token(token).build())
      val response = vaultClient.logical().read("secret/services/jfrog")
      if(Option(response.getLeaseId).isEmpty) {
        s.log.warn("Could not resolve credentials from Vault")
      } else {
        val secret = response.getData.asScala
        s.log.info(secret.mkString(","))
      }
    }
  )
}

object VaultPlugin extends AutoPlugin {
  override def requires: Plugins = sbt.plugins.CorePlugin

  object autoImport {
    val vault: VaultSbt.vault.type = VaultSbt.vault
  }

  override lazy val projectSettings: Seq[Setting[_]] = VaultSbt.projectSettings
}