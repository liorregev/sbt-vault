package io.liorregev.sbt

import com.bettercloud.vault.VaultConfig
import com.bettercloud.vault.api.Auth
import com.google.cloud.iam.credentials.v1.{IamCredentialsClient, IamCredentialsSettings, SignJwtRequest}
import play.api.libs.json._
import sttp.client3._

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

object GCPTokenResolver {
  val TIMEOUT: FiniteDuration = 10 seconds

  private lazy val iamClient = {
    val iamCredentialsSettings = IamCredentialsSettings.newBuilder().build()
    IamCredentialsClient.create(iamCredentialsSettings)
  }

  def loadTokenFromGCP(role: String, config: VaultConfig)(implicit ec: ExecutionContext): Future[String] = {
    val backend = HttpURLConnectionBackend(SttpBackendOptions.Default.copy(connectionTimeout = TIMEOUT))
    for {
      saEmail <- Future {
        basicRequest
          .get(uri"http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email")
          .header("Metadata-Flavor", Option("Google"))
          .readTimeout(TIMEOUT)
          .send(backend)
          .body
          .fold(identity, identity)
      }
      signJwtRequest = {
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
        SignJwtRequest.newBuilder()
          .setName(saName)
          .setPayload(payload.toString())
          .build()
      }
      jwt <- Future(iamClient.signJwt(signJwtRequest).getSignedJwt)
      token <- Future(new Auth(config).loginByGCP(role, jwt).getAuthClientToken)
    } yield token
  }
}
