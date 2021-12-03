package com.horizen.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import com.fasterxml.jackson.annotation.JsonView
import com.horizen.api.http.SidechainCswRestScheme.{ReqCswInfo, ReqGenerationCswState, ReqNullifier, RespCswBoxIds, RespCswHasCeasedState, RespCswInfo, RespGenerationCswState, RespNullifier}
import com.horizen.serialization.Views
import java.util.{Optional => JOptional}

import akka.pattern.ask
import com.horizen.api.http.SidechainCswErrorResponse.{ErrorCswGenerationState, ErrorRetrievingCeasingState}
import com.horizen.csw.CswManager.ReceivableMessages.{GenerateCswProof, GetBoxNullifier, GetCeasedStatus, GetCswBoxIds, GetCswInfo}
import com.horizen.csw.CswManager.Responses.{CswInfo, GenerateCswProofStatus, InvalidAddress, NoProofData, ProofCreationFinished, ProofGenerationInProcess, ProofGenerationStarted, SidechainIsAlive}
import com.horizen.api.http.JacksonSupport._
import com.horizen.utils.{BytesUtils, CswData}
import scorex.core.settings.RESTApiSettings

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

case class SidechainCswApiRoute(override val settings: RESTApiSettings,
                                sidechainNodeViewHolderRef: ActorRef,
                                cswManager: ActorRef)
                               (implicit val context: ActorRefFactory, override val ec: ExecutionContext) extends SidechainApiRoute {

  override val route: Route = pathPrefix("csw") {
    hasCeased ~ generateCswProof ~ cswInfo ~ cswBoxIds ~ nullifier
  }

  /**
   * Return ceasing status of the Sidechain
   */
  def hasCeased: Route = (post & path("hasCeased")) {
    Try {
      Await.result(cswManager ? GetCeasedStatus, timeout.duration).asInstanceOf[Boolean]
    } match {
      case Success(res) =>
        ApiResponseUtil.toResponse(RespCswHasCeasedState(res))
      case Failure(e) =>
        log.error("Unable to retrieve ceasing status of the Sidechain.")
        ApiResponseUtil.toResponse(ErrorRetrievingCeasingState("Unable to retrieve ceasing status of the Sidechain.", JOptional.of(e)))
    }
  }

  /**
   * Create a request for generation of CSW proof for specified
   * and informs about current status of this proof
   */
  def generateCswProof: Route = (post & path("generateCswProof")) {
    entity(as[ReqGenerationCswState]) { body =>
      Try {
        Await.result(cswManager ? GenerateCswProof(BytesUtils.fromHexString(body.boxId), body.mcAddress), timeout.duration).asInstanceOf[GenerateCswProofStatus]
      } match {
        case Success(res) =>
          res match {
            case SidechainIsAlive => ApiResponseUtil.toResponse(RespGenerationCswState("Sidechain is alive"))
            case InvalidAddress  => ApiResponseUtil.toResponse(RespGenerationCswState("Invalid MC address"))
            case NoProofData => ApiResponseUtil.toResponse(RespGenerationCswState("Sidechain is alive"))
            case ProofGenerationStarted => ApiResponseUtil.toResponse(RespGenerationCswState("CSW proof generation is started"))
            case ProofGenerationInProcess => ApiResponseUtil.toResponse(RespGenerationCswState("CSW proof generation in process"))
            case ProofCreationFinished => ApiResponseUtil.toResponse(RespGenerationCswState("CSW proof generation is finished"))
          }
        case Failure(e) =>
          log.error("Unexpected error during CSW proof generation.")
          ApiResponseUtil.toResponse(ErrorCswGenerationState("Unexpected error during CSW proof generation.", JOptional.of(e)))
      }
    }
  }

  /**
   * return the csw info for given BoxId
   */
  def cswInfo: Route = (post & path("cswInfo")) {
     entity(as[ReqCswInfo]) { body =>
       Try {
         Await.result(cswManager ? GetCswInfo(BytesUtils.fromHexString(body.boxId)), timeout.duration).asInstanceOf[CswInfo]
       } match {
         case Success(cswInfo: CswInfo) =>
           ApiResponseUtil.toResponse(RespCswInfo(cswInfo))
         case Failure(e) =>
           log.error("Unexpected error during retrieving CSW info.")
           ApiResponseUtil.toResponse(ErrorRetrievingCeasingState("Unexpected error during retrieving CSW info.", JOptional.of(e)))
       }
     }
  }

  /**
   * Return the list with all box ids.
   */
  def cswBoxIds: Route = (post & path("cswBoxIds")) {
    Try {
      Await.result(cswManager ? GetCswBoxIds, timeout.duration).asInstanceOf[Seq[Array[Byte]]]
    } match {
      case Success(boxIds: Seq[Array[Byte]]) => {
        val boxIdsStr = boxIds.map(id => BytesUtils.toHexString(id))
        ApiResponseUtil.toResponse(RespCswBoxIds(boxIdsStr))
      }
      case Failure(e) =>
        log.error("Unexpected error during retrieving CSW Box Ids.")
        ApiResponseUtil.toResponse(ErrorRetrievingCeasingState("Unexpected error during retrieving CSW Box Ids.", JOptional.of(e)))
    }
  }

  /**
   * Return the nullifier for the given coin box id.
   */
  def nullifier: Route = (post & path("nullifier")) {
    entity(as[ReqNullifier]) { body =>
      Try {
        Await.result(cswManager ? GetBoxNullifier(BytesUtils.fromHexString(body.boxId)), timeout.duration).asInstanceOf[Array[Byte]]
      } match {
        case Success(nullifier: Array[Byte]) =>
          ApiResponseUtil.toResponse(RespNullifier(BytesUtils.toHexString(nullifier)))
        case Failure(e) =>
          log.error("Unexpected error during retrieving the nullifier.")
          ApiResponseUtil.toResponse(ErrorRetrievingCeasingState("Unexpected error during retrieving the nullifier.", JOptional.of(e)))
      }
    }
  }
}

object SidechainCswRestScheme {
  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespCswHasCeasedState(state: Boolean) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqGenerationCswState(boxId: String, mcAddress: String) {
    require(boxId.length == 64, s"Invalid id $boxId. Id length must be 64")
    require(mcAddress.length == 64, s"Invalid address $mcAddress. Address length must be 64")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespGenerationCswState(state: String) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqCswInfo(boxId: String) {
    require(boxId.length == 64, s"Invalid id $boxId. Id length must be 64")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespCswInfo(cswInfo: CswInfo) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespCswBoxIds(cswBoxIds: Seq[String]) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqNullifier(boxId: String) {
    require(boxId.length == 64, s"Invalid id $boxId. Id length must be 64")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespNullifier(nullifier: String) extends SuccessResponse
}

object SidechainCswErrorResponse {
  case class ErrorRetrievingCeasingState(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0701"
  }

  case class ErrorCswGenerationState(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0702"
  }
}