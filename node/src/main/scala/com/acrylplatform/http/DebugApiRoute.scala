package com.acrylplatform.http

import java.net.{InetAddress, InetSocketAddress, URI}
import java.util.concurrent.ConcurrentMap

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.implicits._
import cats.kernel.Monoid
import com.typesafe.config.{ConfigObject, ConfigRenderOptions}
import com.acrylplatform.account.Address
import com.acrylplatform.api.http.ApiError.InvalidAddress
import com.acrylplatform.api.http._
import com.acrylplatform.block.Block
import com.acrylplatform.block.Block.BlockId
import com.acrylplatform.common.state.ByteStr
import com.acrylplatform.common.utils.Base58
import com.acrylplatform.crypto
import com.acrylplatform.lang.ValidationError
import com.acrylplatform.mining.{Miner, MinerDebugInfo}
import com.acrylplatform.network.{LocalScoreChanged, PeerDatabase, PeerInfo, _}
import com.acrylplatform.settings.AcrylSettings
import com.acrylplatform.state.diffs.TransactionDiffer
import com.acrylplatform.state.{Blockchain, LeaseBalance, NG, TransactionId}
import com.acrylplatform.transaction.TxValidationError.{GenericError, InvalidRequestSignature}
import com.acrylplatform.transaction._
import com.acrylplatform.transaction.smart.script.trace.TracedResult
import com.acrylplatform.transaction.smart.{InvokeScriptTransaction, Verifier}
import com.acrylplatform.utils.{ScorexLogging, Time}
import com.acrylplatform.utx.UtxPool
import com.acrylplatform.wallet.Wallet
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import io.swagger.annotations._
import javax.ws.rs.Path
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

@Path("/debug")
@Api(value = "/debug")
case class DebugApiRoute(ws: AcrylSettings,
                         time: Time,
                         blockchain: Blockchain,
                         wallet: Wallet,
                         ng: NG,
                         peerDatabase: PeerDatabase,
                         establishedConnections: ConcurrentMap[Channel, PeerInfo],
                         rollbackTask: ByteStr => Task[Either[ValidationError, Seq[Block]]],
                         allChannels: ChannelGroup,
                         utxStorage: UtxPool,
                         miner: Miner with MinerDebugInfo,
                         historyReplier: HistoryReplier,
                         extLoaderStateReporter: Coeval[RxExtensionLoader.State],
                         mbsCacheSizesReporter: Coeval[MicroBlockSynchronizer.CacheSizes],
                         scoreReporter: Coeval[RxScoreObserver.Stats],
                         configRoot: ConfigObject)
    extends ApiRoute
    with WithSettings
    with ScorexLogging {

  import DebugApiRoute._

  private lazy val configStr             = configRoot.render(ConfigRenderOptions.concise().setJson(true).setFormatted(true))
  private lazy val fullConfig: JsValue   = Json.parse(configStr)
  private lazy val acrylConfig: JsObject = Json.obj("acryl" -> (fullConfig \ "acryl").get)

  override val settings = ws.restAPISettings
  override lazy val route: Route = pathPrefix("debug") {
    blocks ~ state ~ info ~ stateAcryl ~ rollback ~ rollbackTo ~ blacklist ~ portfolios ~ minerInfo ~ historyInfo ~ configInfo ~ print ~ validate ~ stateChanges
  }

  @Path("/blocks/{howMany}")
  @ApiOperation(value = "Blocks", notes = "Get sizes and full hashes for last blocks", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "howMany", value = "How many last blocks to take", required = true, dataType = "string", paramType = "path")
    ))
  def blocks: Route = {
    (path("blocks" / IntNumber) & get & withAuth) { howMany =>
      complete(JsArray(ng.lastBlocks(howMany).map { block =>
        val bytes = block.bytes()
        Json.obj(bytes.length.toString -> Base58.encode(crypto.fastHash(bytes)))
      }))
    }
  }

  @Path("/print")
  @ApiOperation(
    value = "Print",
    notes = "Prints a string at DEBUG level, strips to 100 chars",
    httpMethod = "POST"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "body",
        value = "Json with data",
        required = true,
        paramType = "body",
        dataType = "com.acrylplatform.http.DebugMessage",
        defaultValue = "{\n\t\"message\": \"foo\"\n}"
      )
    ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json portfolio")))
  def print: Route = (path("print") & post & withAuth) {
    json[DebugMessage] { params =>
      log.debug(params.message.take(250))
      ""
    }
  }
  @Path("/portfolios/{address}")
  @ApiOperation(
    value = "Portfolio",
    notes = "Get current portfolio considering pessimistic transactions in the UTX pool",
    httpMethod = "GET"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "address",
        value = "An address of portfolio",
        required = true,
        dataType = "string",
        paramType = "path"
      ),
      new ApiImplicitParam(
        name = "considerUnspent",
        value = "Taking into account pessimistic transactions from UTX pool",
        required = false,
        dataType = "boolean",
        paramType = "query",
        defaultValue = "true"
      )
    ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json portfolio")))
  def portfolios: Route = path("portfolios" / Segment) { rawAddress =>
    (get & withAuth & parameter('considerUnspent.as[Boolean].?)) { considerUnspent =>
      Address.fromString(rawAddress) match {
        case Left(_) => complete(InvalidAddress)
        case Right(address) =>
          val base      = ng.portfolio(address)
          val portfolio = if (considerUnspent.getOrElse(true)) Monoid.combine(base, utxStorage.pessimisticPortfolio(address)) else base
          complete(Json.toJson(portfolio))
      }
    }
  }

  @Path("/state")
  @ApiOperation(value = "State", notes = "Get current state", httpMethod = "GET")
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json state")))
  def state: Route = (path("state") & get & withAuth) {
    complete(ng.acrylDistribution(ng.height).map(_.map { case (a, b) => a.stringRepr -> b }))
  }

  @Path("/stateAcryl/{height}")
  @ApiOperation(value = "State at block", notes = "Get state at specified height", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "height", value = "height", required = true, dataType = "integer", paramType = "path")
    ))
  def stateAcryl: Route = (path("stateAcryl" / IntNumber) & get & withAuth) { height =>
    complete(ng.acrylDistribution(height).map(_.map { case (a, b) => a.stringRepr -> b }))
  }

  private def rollbackToBlock(blockId: ByteStr, returnTransactionsToUtx: Boolean)(implicit ec: ExecutionContext): Future[Either[ValidationError, JsObject]] = {
    rollbackTask(blockId).asyncBoundary
      .map(_.map { blocks =>
        allChannels.broadcast(LocalScoreChanged(ng.score))
        if (returnTransactionsToUtx) blocks.view.flatMap(_.transactionData).foreach(utxStorage.putIfNew(_))
        miner.scheduleMining()
        Json.obj("BlockId" -> blockId.toString)
      })
      .runAsyncLogErr(Scheduler(ec))
  }

  @Path("/rollback")
  @ApiOperation(value = "Rollback to height", notes = "Removes all blocks after given height", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "body",
        value = "Json with data",
        required = true,
        paramType = "body",
        dataType = "com.acrylplatform.http.RollbackParams",
        defaultValue = "{\n\t\"rollbackTo\": 3,\n\t\"returnTransactionsToUTX\": false\n}"
      )
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "200 if success, 404 if there are no block at this height")
    ))
  def rollback: Route = (path("rollback") & post & withAuth & withRequestTimeout(15.minutes) & extractExecutionContext) { implicit ec =>
    json[RollbackParams] { params =>
      ng.blockAt(params.rollbackTo) match {
        case Some(block) =>
          rollbackToBlock(block.uniqueId, params.returnTransactionsToUtx)
        case None =>
          (StatusCodes.BadRequest, "Block at height not found")
      }
    } ~ complete(StatusCodes.BadRequest)
  }

  @Path("/info")
  @ApiOperation(value = "State", notes = "All info you need to debug", httpMethod = "GET")
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json state")
    ))
  def info: Route = (path("info") & get & withAuth) {
    complete(
      Json.obj(
        "stateHeight"                      -> ng.height,
        "extensionLoaderState"             -> extLoaderStateReporter().toString,
        "historyReplierCacheSizes"         -> Json.toJson(historyReplier.cacheSizes),
        "microBlockSynchronizerCacheSizes" -> Json.toJson(mbsCacheSizesReporter()),
        "scoreObserverStats"               -> Json.toJson(scoreReporter()),
        "minerState"                       -> Json.toJson(miner.state)
      ))
  }

  @Path("/minerInfo")
  @ApiOperation(value = "State", notes = "All miner info you need to debug", httpMethod = "GET")
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json state")
    ))
  def minerInfo: Route = (path("minerInfo") & get & withAuth) {
    complete(
      wallet.privateKeyAccounts
        .filterNot(account => ng.hasScript(account.toAddress))
        .map { account =>
          (account.toAddress, miner.getNextBlockGenerationOffset(account))
        }
        .collect {
          case (address, Right(offset)) =>
            AccountMiningInfo(
              address.stringRepr,
              ng.effectiveBalance(address,
                                  ws.blockchainSettings.functionalitySettings.generatingBalanceDepth(ng.height),
                                  ng.microblockIds.lastOption.getOrElse(ByteStr.empty)),
              System.currentTimeMillis() + offset.toMillis
            )
        }
    )
  }

  @Path("/historyInfo")
  @ApiOperation(value = "State", notes = "All history info you need to debug", httpMethod = "GET")
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json state")
    ))
  def historyInfo: Route = (path("historyInfo") & get & withAuth) {
    val a = ng.lastPersistedBlockIds(10)
    val b = ng.microblockIds
    complete(HistoryInfo(a, b))

  }

  @Path("/configInfo")
  @ApiOperation(value = "Config", notes = "Currently running node config", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "full",
        value = "Exposes full typesafe config",
        required = false,
        dataType = "boolean",
        paramType = "query",
        defaultValue = "false"
      )))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "Json state")
    ))
  def configInfo: Route = (path("configInfo") & get & parameter('full.as[Boolean]) & withAuth) { full =>
    complete(if (full) fullConfig else acrylConfig)
  }

  @Path("/rollback-to/{signature}")
  @ApiOperation(value = "Block signature", notes = "Rollback the state to the block with a given signature", httpMethod = "DELETE")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "signature", value = "Base58-encoded block signature", required = true, dataType = "string", paramType = "path")
    ))
  def rollbackTo: Route = path("rollback-to" / Segment) { signature =>
    (delete & withAuth & extractExecutionContext) { implicit ec =>
      val signatureEi: Either[ValidationError, ByteStr] =
        ByteStr
          .decodeBase58(signature)
          .toEither
          .leftMap(_ => InvalidRequestSignature)
      signatureEi
        .fold(
          err => complete(ApiError.fromValidationError(err)),
          sig => complete(rollbackToBlock(sig, returnTransactionsToUtx = false))
        )
    }
  }

  @Path("/blacklist")
  @ApiOperation(value = "Blacklist given peer", notes = "Moving peer to blacklist", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "address", value = "IP address of node", required = true, dataType = "string", paramType = "body")
    ))
  @ApiResponses(
    Array(
      new ApiResponse(code = 200, message = "200 if success, 404 if there are no peer with such address")
    ))
  def blacklist: Route = (path("blacklist") & post & withAuth) {
    entity(as[String]) { socketAddressString =>
      try {
        val uri     = new URI("node://" + socketAddressString)
        val address = InetAddress.getByName(uri.getHost)
        establishedConnections.entrySet().stream().forEach { entry =>
          entry.getValue.remoteAddress match {
            case x: InetSocketAddress if x.getAddress == address =>
              peerDatabase.blacklistAndClose(entry.getKey, "Debug API request")
            case _ =>
          }
        }
        complete(StatusCodes.OK)
      } catch {
        case NonFatal(_) => complete(StatusCodes.BadRequest)
      }
    } ~ complete(StatusCodes.BadRequest)
  }

  @Path("/validate")
  @ApiOperation(value = "Validate Transaction", notes = "Validates a transaction and measures time spent in milliseconds", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "transaction", value = "Signed transaction", required = true, dataType = "string", paramType = "body")
    ))
  def validate: Route = (path("validate") & post) {
    handleExceptions(jsonExceptionHandler) {
      json[JsObject] { jsv =>

        val h  = blockchain.height
        val t0 = System.nanoTime
        val tracedDiff = for {
          tx <- TracedResult(TransactionFactory.fromSignedRequest(jsv))
          _  <- Verifier(blockchain, h)(tx)
          ei <- TransactionDiffer(blockchain.lastBlockTimestamp, time.correctedTime(), h)(blockchain, tx)
        } yield ei
        val timeSpent = (System.nanoTime - t0) / 1000 / 1000.0
        val response = Json.obj(
          "valid"          -> tracedDiff.resultE.isRight,
          "validationTime" -> timeSpent,
          "trace"          -> tracedDiff.trace.map(_.toString)
        )
        tracedDiff.resultE.fold(
          err => response + ("error" -> JsString(ApiError.fromValidationError(err).message)),
          _ => response
        )
      }
    }
  }

  def stateChanges: Route = stateChangesById ~ stateChangesByAddress

  @Path("/stateChanges/info/{id}")
  @ApiOperation(value = "Transaction state changes", notes = "Returns state changes made by the transaction", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "id", value = "Transaction ID", required = true, dataType = "string", paramType = "path")
    ))
  def stateChangesById: Route = (get & path("stateChanges" / "info" / B58Segment) & handleExceptions(jsonExceptionHandler)) { id =>
    blockchain.transactionInfo(id) match {
      case Some((h, tx: InvokeScriptTransaction)) =>
        val resultE = blockchain
          .invokeScriptResult(TransactionId(tx.id()))
          .map(isr => tx.json.map(_ ++ Json.obj("height" -> h, "stateChanges" -> isr))())
        complete(resultE)

      case Some((_, tx)) =>
        complete(ApiError.UnsupportedTransactionType)

      case None =>
        complete(ApiError.TransactionDoesNotExist)

    }
  }

  @Path("/stateChanges/address/{address}/limit/{limit}")
  @ApiOperation(value = "List of transactions by address with state changes",
                notes = "Get list of transactions with state changes where specified address has been involved",
                httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "address", value = "Address", required = true, dataType = "string", paramType = "path"),
      new ApiImplicitParam(name = "limit",
                           value = "Number of transactions to be returned",
                           required = true,
                           dataType = "integer",
                           paramType = "path"),
      new ApiImplicitParam(name = "after", value = "Id of transaction to paginate after", required = false, dataType = "string", paramType = "query")
    ))
  def stateChangesByAddress: Route =
    (get & path("stateChanges" / "address" / AddrSegment / "limit" / IntNumber) & parameter('after.?) & handleExceptions(jsonExceptionHandler)) {
      (address, limit, afterOpt) =>
        validate(limit <= settings.transactionsByAddressLimit, s"Max limit is ${settings.transactionsByAddressLimit}") {
          import cats.implicits._
          val resultE: Either[ValidationError, Seq[JsObject]] = for {
            txs <- concurrent
              .blocking(
                blockchain.addressTransactions(address,
                                               Set.empty[Byte],
                                               limit,
                                               afterOpt.flatMap(str => Base58.tryDecodeWithLimit(str).map(ByteStr(_)).toOption)))
              .left
              .map(GenericError(_))
            jsons <- txs
              .map {
                case (height, tx: InvokeScriptTransaction) =>
                  blockchain
                    .invokeScriptResult(TransactionId(tx.id()))
                    .map(isr => tx.json() ++ Json.obj("height" -> JsNumber(height), "stateChanges" -> isr))

                case (height, tx) =>
                  Right(tx.json() ++ Json.obj("height" -> JsNumber(height)))
              }
              .toList
              .sequence
          } yield jsons

          complete(resultE)
        }
    }
}

object DebugApiRoute {
  implicit val assetsFormat: Format[Map[ByteStr, Long]] = Format[Map[ByteStr, Long]](
    {
      case JsObject(m) =>
        m.foldLeft[JsResult[Map[ByteStr, Long]]](JsSuccess(Map.empty)) {
          case (e: JsError, _) => e
          case (JsSuccess(m, _), (rawAssetId, JsNumber(count))) =>
            (ByteStr.decodeBase58(rawAssetId), count) match {
              case (Success(assetId), count) if count.isValidLong => JsSuccess(m.updated(assetId, count.toLong))
              case (Failure(_), _)                                => JsError(s"Can't parse '$rawAssetId' as base58 string")
              case (_, count)                                     => JsError(s"Invalid count of assets: $count")
            }
          case (_, (_, rawCount)) =>
            JsError(s"Invalid count of assets: $rawCount")
        }
      case _ => JsError("The map is expected")
    },
    m => Json.toJson(m.map { case (assetId, count) => assetId.base58 -> count })
  )
  implicit val leaseInfoFormat: Format[LeaseBalance] = Json.format

  case class AccountMiningInfo(address: String, miningBalance: Long, timestamp: Long)

  implicit val accountMiningBalanceFormat: Format[AccountMiningInfo] = Json.format

  implicit val addressWrites: Format[Address] = new Format[Address] {
    override def writes(o: Address): JsValue = JsString(o.stringRepr)

    override def reads(json: JsValue): JsResult[Address] = ???
  }

  case class HistoryInfo(lastBlockIds: Seq[BlockId], microBlockIds: Seq[BlockId])

  implicit val historyInfoFormat: Format[HistoryInfo] = Format(
    Reads { json =>
      ???
    },
    Writes { info =>
      ???
    }
  )

  implicit val hrCacheSizesFormat: Format[HistoryReplier.CacheSizes]          = Json.format
  implicit val mbsCacheSizesFormat: Format[MicroBlockSynchronizer.CacheSizes] = Json.format
  implicit val BigIntWrite: Writes[BigInt]                                    = (bigInt: BigInt) => JsNumber(BigDecimal(bigInt))
  implicit val scoreReporterStatsWrite: Writes[RxScoreObserver.Stats]         = Json.writes[RxScoreObserver.Stats]

  import MinerDebugInfo._
  implicit val minerStateWrites: Writes[MinerDebugInfo.State] = (s: MinerDebugInfo.State) =>
    JsString(s match {
      case MiningBlocks      => "mining blocks"
      case MiningMicroblocks => "mining microblocks"
      case Disabled          => "disabled"
      case Error(err)        => s"error: $err"
    })
}
