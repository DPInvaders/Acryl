package com.acrylplatform.transaction.transfer

import cats.implicits._
import com.google.common.primitives.{Bytes, Longs}
import com.acrylplatform.account.AddressOrAlias
import com.acrylplatform.common.utils.Base58
import com.acrylplatform.lang.ValidationError
import com.acrylplatform.serialization.Deser
import com.acrylplatform.transaction._
import com.acrylplatform.transaction.Asset.{IssuedAsset, Acryl}
import com.acrylplatform.transaction.validation._
import com.acrylplatform.utils.base58Length
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}

trait TransferTransaction extends ProvenTransaction with VersionedTransaction {
  def assetId: Asset
  def recipient: AddressOrAlias
  def amount: Long
  def feeAssetId: Asset
  def fee: Long
  def attachment: Array[Byte]
  def version: Byte

  override val assetFee: (Asset, Long) = (feeAssetId, fee)

  override final val json: Coeval[JsObject] = Coeval.evalOnce(
    jsonBase() ++ Json.obj(
      "version"    -> version,
      "recipient"  -> recipient.stringRepr,
      "assetId"    -> assetId.maybeBase58Repr,
      "feeAsset"   -> feeAssetId.maybeBase58Repr, // legacy v0.11.1 compat
      "amount"     -> amount,
      "attachment" -> Base58.encode(attachment)
    ))

  final protected val bytesBase: Coeval[Array[Byte]] = Coeval.evalOnce {
    val timestampBytes  = Longs.toByteArray(timestamp)
    val assetIdBytes    = assetId.byteRepr
    val feeAssetIdBytes = feeAssetId.byteRepr
    val amountBytes     = Longs.toByteArray(amount)
    val feeBytes        = Longs.toByteArray(fee)

    Bytes.concat(
      sender,
      assetIdBytes,
      feeAssetIdBytes,
      timestampBytes,
      amountBytes,
      feeBytes,
      recipient.bytes.arr,
      Deser.serializeArray(attachment)
    )
  }
  override def checkedAssets(): Seq[IssuedAsset] = assetId match {
    case Acryl => Seq()
    case a: IssuedAsset => Seq(a)
  }
}

object TransferTransaction {

  val typeId: Byte = 4

  val MaxAttachmentSize            = 140
  val MaxAttachmentStringSize: Int = base58Length(MaxAttachmentSize)

  def validate(tx: TransferTransaction): Either[ValidationError, Unit] = {
    validate(tx.amount, tx.assetId, tx.fee, tx.feeAssetId, tx.attachment)
  }

  def validate(amt: Long, maybeAmtAsset: Asset, feeAmt: Long, maybeFeeAsset: Asset, attachment: Array[Byte]): Either[ValidationError, Unit] = {
    (
      validateAmount(amt, maybeAmtAsset.maybeBase58Repr.getOrElse("acryl")),
      validateFee(feeAmt),
      validateAttachment(attachment)
    ).mapN { case _ => () }
      .toEither
      .leftMap(_.head)
  }
}