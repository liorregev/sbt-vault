package com.liorregev.sbt

import com.bettercloud.vault.api.Auth
import com.bettercloud.vault.{Vault, VaultConfig}
import com.google.cloud.iam.credentials.v1.{IamCredentialsClient, IamCredentialsSettings, SignJwtRequest}
import play.api.libs.json.{JsNumber, JsObject, JsString, JsValue}
import sbt.Keys._
import sbt._
import sttp.client3._

import java.time.Instant
import scala.collection.JavaConverters._
import scala.util.Try

object VaultSbt {
  sealed trait LoginMethod
  object LoginMethods {
    case object None extends LoginMethod
    final case class UserPass(user: String, password: String) extends LoginMethod
    final case class GCPServiceAccount(role: String) extends LoginMethod
    final case class Token(token: String) extends LoginMethod
  }


  object VaultKeys {
    val vaultAddress = settingKey[String]("Address of the Vault server")
    val credentialsKeys = settingKey[Seq[vault.CredentialsKey]]("Data on credentials to fetch")
    val selectedLoginMethods = settingKey[Seq[LoginMethod]]("Methods to log in with")
    val resolveCreds = taskKey[Either[List[String], List[Credentials]]]("Resolve credentials")
  }

  object vault {
    val vaultAddress = VaultKeys.vaultAddress
    val credentialsKeys = VaultKeys.credentialsKeys
    val resolveCredentials = VaultKeys.resolveCreds
    val selectedLoginMethods = VaultKeys.selectedLoginMethods
    val loginMethods: LoginMethods.type = LoginMethods
    final case class CredentialsKey(vaultKey: String, internalUserKey: String, internalPasswordKey: String,
                                    realm: String, host: String)
  }

  import VaultKeys._

  def loadTokenFromGCP(role: String, config: VaultConfig): String = {
    val backend = HttpURLConnectionBackend()
    val saEmail = basicRequest
      .get(uri"http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email")
      .header("Metadata-Flavor", Option("Google"))
      .send(backend)
      .body
      .fold(identity, identity)

    val iamClient = {
      val iamCredentialsSettings = IamCredentialsSettings.newBuilder().build()
      IamCredentialsClient.create(iamCredentialsSettings)
    }
    val now = Instant.now()
    val expires = now.plusSeconds(900)
    val payload = JsObject(
      Map[String, JsValue](
        "iat" -> JsNumber(now.getEpochSecond),
        "exp" -> JsNumber(expires.getEpochSecond),
        "sub" -> JsString(saEmail),
        "aud" -> JsString(s"vault/$role")
      )
    )
    val saName = s"projects/-/serviceAccounts/$saEmail"
    val request = SignJwtRequest.newBuilder()
      .setName(saName)
      .setPayload(payload.toString())
      .build()
    val jwt = iamClient.signJwt(request).getSignedJwt
    new Auth(config).loginByGCP(role, jwt).getAuthClientToken
  }

  def loadToken(config: VaultConfig): LoginMethod => Option[String] = {
    case LoginMethods.None => Try(config.getToken).toOption
    case LoginMethods.UserPass(user, password) =>
      Try(new Auth(config).loginByUserPass(user, password).getAuthClientToken).toOption
    case LoginMethods.GCPServiceAccount(role) => Try(loadTokenFromGCP(role, config)).toOption
    case LoginMethods.Token(token) => Option(token)
  }

  def projectSettings: Seq[Setting[_]] = Seq(
    vaultAddress := "",
    credentialsKeys := Seq.empty,
    selectedLoginMethods := Seq.empty,
    resolveCreds := {
      val config = new VaultConfig()
        .address(vaultAddress.value)
      val tokenLoader = loadToken(config.build())
      val token = selectedLoginMethods.value
        .map(tokenLoader)
        .reduce(_ orElse _)
        .get
      val vaultClient = new Vault(config.token(token).build())
      credentialsKeys.value
        .map(key => {
          val response = vaultClient.logical().read(key.vaultKey)
          Option(response.getRestResponse)
            .map(resp => {
              if(resp.getStatus == 200) {
                val secret = response.getData.asScala
                Right(Credentials(key.realm, key.host, secret(key.internalUserKey), secret(key.internalPasswordKey)))
              } else {
                Left(s"Could not resolve credentials from Vault (${key.vaultKey}): ${resp.getStatus}")
              }
            })
            .getOrElse(Left(s"Could not resolve credentials from Vault (${key.vaultKey})"))
        })
        .foldLeft[Either[List[String], List[Credentials]]](Right(Nil)) {
          case (Left(errorsSoFar), Left(error)) => Left(error :: errorsSoFar)
          case (Left(errorsSoFar), _) => Left(errorsSoFar)
          case (Right(credsSoFar), Right(creds)) => Right(creds :: credsSoFar)
          case (Right(_), Left(error)) => Left(List(error))
        }
    },
    ThisBuild / credentials ++= {
      val logger = streams.value.log
      val resolvedCredentials = resolveCreds.value
      resolvedCredentials.fold(
        msgs => {
          msgs.foreach(msg => logger.error(msg))
          Seq.empty
        },
        identity
      )
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