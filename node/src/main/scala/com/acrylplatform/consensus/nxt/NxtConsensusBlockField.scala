package com.acrylplatform.consensus.nxt

import com.google.common.primitives.{Bytes, Longs}
import play.api.libs.json.{JsObject, Json}
import com.acrylplatform.block.BlockField

case class NxtConsensusBlockField(override val value: NxtLikeConsensusBlockData) extends BlockField[NxtLikeConsensusBlockData] {

  override val name: String = "nxt-consensus"

  override def b: Array[Byte] =
    Bytes.ensureCapacity(Longs.toByteArray(value.baseTarget), 8, 0) ++
      value.generationSignature.arr

  override def j: JsObject =
    Json.obj(
      name -> Json.obj(
        "base-target"          -> value.baseTarget,
        "generation-signature" -> value.generationSignature.base58
      ))
}
