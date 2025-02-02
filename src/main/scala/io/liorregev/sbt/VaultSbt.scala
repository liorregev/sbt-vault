package io.liorregev.sbt

import com.bettercloud.vault.api.Auth
import com.bettercloud.vault.{Vault, VaultConfig}
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import sbt.Keys._
import sbt._

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

object VaultSbt {
  val TIMEOUT: Duration = 45 seconds

  sealed trait LoginMethod
  object LoginMethods {
    case object None extends LoginMethod
    final case class UserPass(user: String, password: String) extends LoginMethod
    final case class GCPServiceAccount(role: String) extends LoginMethod
    final case class Token(token: String) extends LoginMethod
  }

  final case class KeyResolutionError(message: String) extends Throwable {
    override val getMessage: String = message
  }

  val resolvedKeys: mutable.Map[vault.CredentialsKey, Future[Either[Throwable, Credentials]]] = mutable.Map.empty

  object VaultKeys {
    val vaultAddress = settingKey[vault.VaultConnection]("Address of the Vault server")
    val credentialsKeys = settingKey[Seq[vault.CredentialsKey]]("Data on credentials to fetch")
    val genericSecrets = settingKey[Seq[String]]("Paths of generic secrets to fetch, will be available under 'fetchedSecrets'")
    val fetchedSecrets = settingKey[Map[String, Map[String, String]]]("The fetched data of secrets defined in 'genericSecrets'")
    val fetchedCredentials = settingKey[Seq[Credentials]]("The fetched credentials from Vault")
    val selectedLoginMethods = settingKey[Seq[LoginMethod]]("Methods to log in with")
  }

  object vault {
    val vaultAddress = VaultKeys.vaultAddress
    val credentialsKeys = VaultKeys.credentialsKeys
    val selectedLoginMethods = VaultKeys.selectedLoginMethods
    val genericSecrets = VaultKeys.genericSecrets
    val fetchedSecrets = VaultKeys.fetchedSecrets
    val fetchedCredentials = VaultKeys.fetchedCredentials
    val loginMethods: LoginMethods.type = LoginMethods
    final case class CredentialsKey(vaultKey: String, internalUserKey: String, internalPasswordKey: String,
                                    realm: String, host: String)
    final case class VaultConnection(host: String, engineVersion: Int)

    object VaultConnection {
      def apply(host: String): VaultConnection = new VaultConnection(host, 2)
    }
  }

  import VaultKeys._

  def closeIamClient(iamClient: IamCredentialsClient)(implicit ec: ExecutionContext): Future[Boolean] = {
    iamClient.shutdownNow()

    def await(): Future[Boolean] =
      Future {
        iamClient.awaitTermination(1, TimeUnit.MINUTES)
      }.flatMap {
        case true => Future.successful(true)
        case false => await()
      }

    await()
  }

  def loadToken(config: VaultConfig)(implicit ec: ExecutionContext): LoginMethod => Future[String] = {
    case LoginMethods.None => Future(config.getToken)
    case LoginMethods.UserPass(user, password) =>
      Future(new Auth(config).loginByUserPass(user, password).getAuthClientToken)
    case LoginMethods.GCPServiceAccount(role) => GCPTokenResolver.loadTokenFromGCP(role, config)
    case LoginMethods.Token(token) => Future(token)
  }

  def createVaultClient(conn: vault.VaultConnection, loginMethods: Seq[LoginMethod])(implicit ec: ExecutionContext): Future[Vault] = {
    val config = new VaultConfig()
      .address(conn.host)
      .engineVersion(conn.engineVersion)
    val tokenLoader = loadToken(config.build())
    loginMethods
      .map(tokenLoader)
      .reduce((x, y) => x
        .recoverWith {
          case _ => y
        }
      )
      .map(token => new Vault(config.token(token).build()))

  }

  def sequence[A, B](s: Seq[Either[A, B]]): Either[Seq[A], Seq[B]] =
    s.foldLeft[Either[List[A], List[B]]](Right(Nil)) {
      case (Left(ls), Left(l)) => Left(l :: ls)
      case (Left(ls), _) => Left(ls)
      case (Right(rs), Right(r)) => Right(r :: rs)
      case (Right(_), Left(l)) => Left(List(l))
    }

  def resolveCreds(conn: vault.VaultConnection, loginMethods: Seq[LoginMethod], keys: Seq[vault.CredentialsKey])
                  (implicit ec: ExecutionContext): Future[Either[Seq[Throwable], Seq[Credentials]]] = {
    lazy val eventualClient = createVaultClient(conn, loginMethods)
    val eventualResolutions = keys
      .map(key => {
        resolvedKeys.getOrElseUpdate(key, {
          eventualClient
            .flatMap( vaultClient =>
              Future(vaultClient.logical().read(key.vaultKey))
                .map { response =>
                  Option(response.getRestResponse)
                    .map(resp => {
                      if(resp.getStatus == 200) {
                        val secret = response.getData.asScala
                        Right(Credentials(key.realm, key.host, secret(key.internalUserKey), secret(key.internalPasswordKey)))
                      } else {
                        Left(KeyResolutionError(s"Could not resolve credentials from Vault (${key.vaultKey}): ${resp.getStatus}"))
                      }
                    })
                    .getOrElse(Left(KeyResolutionError(s"Could not resolve credentials from Vault (${key.vaultKey})")))
                }
                .recover {
                  case ex => Left(ex)
                }
            )
        })
      })
    Future.sequence(eventualResolutions).map(sequence)
  }

  def projectSettings: Seq[Setting[_]] = {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(
      new java.util.concurrent.ForkJoinPool(1)
    )
    Seq(
      vaultAddress := vault.VaultConnection(""),
      credentialsKeys := Seq.empty,
      genericSecrets := Seq.empty,
      selectedLoginMethods := Seq.empty,
      fetchedSecrets := {
        val logger = sLog.value
        val eventualResults = for {
          vaultClient <- createVaultClient(vaultAddress.value, selectedLoginMethods.value)
          results <- Future.sequence(
            genericSecrets.value
              .map { secretPath =>
                Future(vaultClient.logical().read(secretPath))
                  .map { response =>
                    Option(response.getRestResponse)
                      .map(resp => {
                        if (resp.getStatus == 200) {
                          val secret = response.getData.asScala.toMap
                          Right(secretPath -> secret)
                        } else {
                          Left(KeyResolutionError(s"Could not resolve secret from Vault ($secretPath): ${resp.getStatus}"))
                        }
                      })
                      .getOrElse(Left(KeyResolutionError(s"Could not resolve secrets from Vault ($secretPath)")))
                  }
              }
          )
        } yield sequence(results)
        val finalResults = eventualResults
          .recover {
            case ex => Left(Seq(ex))
          }
          .map(_.fold(
            errors => {
              errors.map(_.getMessage).foreach(msg => logger.error(msg))
              Map.empty[String, Map[String, String]]
            },
            _.toMap
          ))
        Await.result(finalResults, TIMEOUT)
      },
      fetchedCredentials := {
        val logger = sLog.value
        val resolvedCredentials = Await.result(resolveCreds(vaultAddress.value, selectedLoginMethods.value, credentialsKeys.value), TIMEOUT)
        resolvedCredentials.fold(
          errors => {
            errors.map(_.getMessage).foreach(msg => logger.error(msg))
            Seq.empty
          },
          identity
        )
      },
      ThisBuild / credentials ++= fetchedCredentials.value
    )
  }
}

object VaultPlugin extends AutoPlugin {
  override def requires: Plugins = sbt.plugins.CorePlugin

  object autoImport {
    val vault: VaultSbt.vault.type = VaultSbt.vault
  }

  override lazy val projectSettings: Seq[Setting[_]] = VaultSbt.projectSettings
}