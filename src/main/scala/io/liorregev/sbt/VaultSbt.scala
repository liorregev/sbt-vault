package io.liorregev.sbt

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
    val vaultAddress = settingKey[vault.VaultConnection]("Address of the Vault server")
    val credentialsKeys = settingKey[Seq[vault.CredentialsKey]]("Data on credentials to fetch")
    val genericSecrets = settingKey[Seq[String]]("Paths of generic secrets to fetch, will be available under 'fetchedSecrets'")
    val fetchedSecrets = taskKey[Map[String, Map[String, String]]]("The fetched data of secrets defined in 'genericSecrets'")
    val selectedLoginMethods = settingKey[Seq[LoginMethod]]("Methods to log in with")
  }

  object vault {
    val vaultAddress = VaultKeys.vaultAddress
    val credentialsKeys = VaultKeys.credentialsKeys
    val selectedLoginMethods = VaultKeys.selectedLoginMethods
    val genericSecrets = VaultKeys.genericSecrets
    val fetchedSecrets = VaultKeys.fetchedSecrets
    val loginMethods: LoginMethods.type = LoginMethods
    final case class CredentialsKey(vaultKey: String, internalUserKey: String, internalPasswordKey: String,
                                    realm: String, host: String)
    final case class VaultConnection(host: String, engineVersion: Int)

    object VaultConnection {
      def apply(host: String): VaultConnection = new VaultConnection(host, 2)
    }
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

  def createVaultClient(conn: vault.VaultConnection, loginMethods: Seq[LoginMethod]): Vault = {
    val config = new VaultConfig()
      .address(conn.host)
      .engineVersion(conn.engineVersion)
    val tokenLoader = loadToken(config.build())
    val token = loginMethods
      .map(tokenLoader)
      .reduce(_ orElse _)
      .get
    new Vault(config.token(token).build())
  }

  def sequence[A, B](s: Seq[Either[A, B]]): Either[Seq[A], Seq[B]] =
    s.foldLeft[Either[List[A], List[B]]](Right(Nil)) {
        case (Left(ls), Left(l)) => Left(l :: ls)
        case (Left(ls), _) => Left(ls)
        case (Right(rs), Right(r)) => Right(r :: rs)
        case (Right(_), Left(l)) => Left(List(l))
      }

  def resolveCreds(conn: vault.VaultConnection, loginMethods: Seq[LoginMethod], keys: Seq[vault.CredentialsKey]): Either[Seq[String], Seq[Credentials]] = {
    lazy val vaultClient = createVaultClient(conn, loginMethods)
    val results = keys
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
    sequence(results)
  }

  def projectSettings: Seq[Setting[_]] = Seq(
    vaultAddress := vault.VaultConnection(""),
    credentialsKeys := Seq.empty,
    genericSecrets := Seq.empty,
    selectedLoginMethods := Seq.empty,
    fetchedSecrets := {
      val logger = streams.value.log
      lazy val vaultClient = createVaultClient(vaultAddress.value, selectedLoginMethods.value)
      val results = genericSecrets.value
        .map { secretPath =>
          val response = vaultClient.logical().read(secretPath)
          Option(response.getRestResponse)
            .map(resp => {
              if(resp.getStatus == 200) {
                val secret = response.getData.asScala.toMap
                Right(secretPath -> secret)
              } else {
                Left(s"Could not resolve secret from Vault ($secretPath): ${resp.getStatus}")
              }
            })
            .getOrElse(Left(s"Could not resolve secrets from Vault ($secretPath)"))
        }
      sequence(results)
        .fold(
          errors => {
            errors.foreach(err => logger.error(err))
            Map.empty
          },
          _.toMap
        )
    },
    ThisBuild / credentials ++= {
      val logger = streams.value.log
      val resolvedCredentials = resolveCreds(vaultAddress.value, selectedLoginMethods.value, credentialsKeys.value)
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