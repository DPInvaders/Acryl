package com.acrylplatform.state

import com.acrylplatform.account.{Address, Alias}
import com.acrylplatform.block.Block.BlockId
import com.acrylplatform.block.{Block, BlockHeader}
import com.acrylplatform.common.state.ByteStr
import com.acrylplatform.lang.ValidationError
import com.acrylplatform.lang.script.Script
import com.acrylplatform.settings.BlockchainSettings
import com.acrylplatform.state.reader.LeaseDetails
import com.acrylplatform.transaction.Asset.{IssuedAsset, Acryl}
import com.acrylplatform.transaction.assets.IssueTransaction
import com.acrylplatform.transaction.lease.LeaseTransaction
import com.acrylplatform.transaction.transfer.TransferTransaction
import com.acrylplatform.transaction.{Asset, Transaction, TransactionParser, TransactionParsers}
import com.acrylplatform.utils.CloseableIterator

trait Blockchain {
  def settings: BlockchainSettings

  def height: Int
  def score: BigInt

  def blockHeaderAndSize(height: Int): Option[(BlockHeader, Int)]
  def blockHeaderAndSize(blockId: ByteStr): Option[(BlockHeader, Int)]

  def lastBlock: Option[Block]
  def carryFee: Long
  def blockBytes(height: Int): Option[Array[Byte]]
  def blockBytes(blockId: ByteStr): Option[Array[Byte]]

  def heightOf(blockId: ByteStr): Option[Int]

  /** Returns the most recent block IDs, starting from the most recent  one */
  def lastBlockIds(howMany: Int): Seq[ByteStr]

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  def blockIdsAfter(parentSignature: ByteStr, howMany: Int): Option[Seq[ByteStr]]

  def parentHeader(block: BlockHeader, back: Int = 1): Option[BlockHeader]

  def totalFee(height: Int): Option[Long]

  /** Features related */
  def approvedFeatures: Map[Short, Int]
  def activatedFeatures: Map[Short, Int]
  def featureVotes(height: Int): Map[Short, Int]

  def portfolio(a: Address): Portfolio

  def transferById(id: ByteStr): Option[(Int, TransferTransaction)]
  def transactionInfo(id: ByteStr): Option[(Int, Transaction)]
  def transactionHeight(id: ByteStr): Option[Int]

  def nftList(address: Address, from: Option[IssuedAsset]): CloseableIterator[IssueTransaction]

  def addressTransactions(address: Address, types: Set[TransactionParser], fromId: Option[ByteStr]): CloseableIterator[(Height, Transaction)]

  // Compatibility
  def addressTransactions(address: Address,
                          types: Set[Transaction.Type],
                          count: Int,
                          fromId: Option[ByteStr]): Either[String, Seq[(Height, Transaction)]] = {
    def createTransactionsList(): Seq[(Height, Transaction)] = concurrent.blocking {
      addressTransactions(address, TransactionParsers.forTypeSet(types), fromId)
        .take(count)
        .closeAfter(_.toVector)
    }

    fromId match {
      case Some(id) => transactionInfo(id).toRight(s"Transaction $id does not exist").map(_ => createTransactionsList())
      case None     => Right(createTransactionsList())
    }
  }

  def containsTransaction(tx: Transaction): Boolean

  def assetDescription(id: IssuedAsset): Option[AssetDescription]

  def resolveAlias(a: Alias): Either[ValidationError, Address]

  def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]

  def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee

  /** Retrieves Acryl balance snapshot in the [from, to] range (inclusive) */
  def balanceSnapshots(address: Address, from: Int, to: BlockId): Seq[BalanceSnapshot]

  def accountScript(address: Address): Option[Script]
  def hasScript(address: Address): Boolean

  def assetScript(id: IssuedAsset): Option[Script]
  def hasAssetScript(id: IssuedAsset): Boolean

  def accountDataKeys(address: Address): Seq[String]
  def accountData(acc: Address, key: String): Option[DataEntry[_]]
  def accountData(acc: Address): AccountDataInfo

  def leaseBalance(address: Address): LeaseBalance

  def balance(address: Address, mayBeAssetId: Asset = Acryl): Long

  def assetDistribution(asset: IssuedAsset): AssetDistribution
  def assetDistributionAtHeight(asset: IssuedAsset,
                                height: Int,
                                count: Int,
                                fromAddress: Option[Address]): Either[ValidationError, AssetDistributionPage]
  def acrylDistribution(height: Int): Either[ValidationError, Map[Address, Long]]

  // the following methods are used exclusively by patches
  def allActiveLeases: CloseableIterator[LeaseTransaction]

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    *
    * @note Portfolios passed to `pf` only contain Acryl and Leasing balances to improve performance */
  def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A]

  def invokeScriptResult(txId: TransactionId): Either[ValidationError, InvokeScriptResult]
}
