package com.acrylplatform.transaction.assets

import java.nio.charset.StandardCharsets

import cats.implicits._
import com.google.common.primitives.{Bytes, Longs}
import com.acrylplatform.lang.ValidationError
import com.acrylplatform.lang.script.Script
import com.acrylplatform.serialization.Deser
import com.acrylplatform.transaction.Asset.Acryl
import com.acrylplatform.transaction.validation._
import com.acrylplatform.transaction.{Asset, ProvenTransaction, VersionedTransaction}
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}

trait IssueTransaction extends ProvenTransaction with VersionedTransaction {
  def name: Array[Byte]
  def description: Array[Byte]
  def quantity: Long
  def decimals: Byte
  def reissuable: Boolean
  def fee: Long
  def script: Option[Script]

  final lazy val assetId                     = id
  override final val assetFee: (Asset, Long) = (Acryl, fee)

  val isNFT: Boolean = quantity == 1 && decimals == 0 && !reissuable

  val issueJson: Coeval[JsObject] = Coeval.evalOnce(
    jsonBase() ++ Json.obj(
      "version"     -> version,
      "assetId"     -> assetId().base58,
      "name"        -> new String(name, StandardCharsets.UTF_8),
      "quantity"    -> quantity,
      "reissuable"  -> reissuable,
      "decimals"    -> decimals,
      "description" -> new String(description, StandardCharsets.UTF_8),
    ))

  final protected val bytesBase: Coeval[Array[Byte]] = Coeval.evalOnce(
    Bytes.concat(
      sender,
      Deser.serializeArray(name),
      Deser.serializeArray(description),
      Longs.toByteArray(quantity),
      Array(decimals),
      Deser.serializeBoolean(reissuable),
      Longs.toByteArray(fee),
      Longs.toByteArray(timestamp)
    ))
}
object IssueTransaction {

  val typeId: Byte = 3

  val MaxDescriptionLength = 1000
  val MaxAssetNameLength   = 16
  val MinAssetNameLength   = 4
  val MaxDecimals          = 8

  def validateIssueParams(tx: IssueTransaction): Either[ValidationError, Unit] = {
    validateIssueParams(tx.name, tx.description, tx.quantity, tx.decimals, tx.reissuable, tx.fee)
  }

  def validateIssueParams(name: Array[Byte],
                          description: Array[Byte],
                          quantity: Long,
                          decimals: Byte,
                          reissuable: Boolean,
                          fee: Long): Either[ValidationError, Unit] = {
    (
      validateAmount(quantity, "assets"),
      validateName(name),
      validateDescription(description),
      validateDecimals(decimals),
      validateFee(fee)
    ).mapN { case _ => () }
      .leftMap(_.head)
      .toEither
  }
}
