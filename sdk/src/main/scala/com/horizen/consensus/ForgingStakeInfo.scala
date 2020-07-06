package com.horizen.consensus

import com.google.common.primitives.{Bytes, Longs}
import com.horizen.proposition.{PublicKey25519Proposition, PublicKey25519PropositionSerializer, VrfPublicKey, VrfPublicKeySerializer}
import com.horizen.utils.Utils
import scorex.core.serialization.{BytesSerializable, ScorexSerializer}
import scorex.util.serialization.{Reader, Writer}

case class ForgingStakeInfo(blockSignPublicKey: PublicKey25519Proposition,
                            vrfPublicKey: VrfPublicKey,
                            stakeAmount: Long) extends BytesSerializable {
  require(stakeAmount >= 0, "stakeAmount expected to be non negative.")

  override type M = ForgingStakeInfo

  override def serializer: ScorexSerializer[ForgingStakeInfo] = ForgingStakeInfoSerializer

  def hash: Array[Byte] = Utils.doubleSHA256Hash(
    Bytes.concat(blockSignPublicKey.bytes(), vrfPublicKey.bytes(), Longs.toByteArray(stakeAmount))
  )
}

object ForgingStakeInfoSerializer extends ScorexSerializer[ForgingStakeInfo]{
  override def serialize(obj: ForgingStakeInfo, w: Writer): Unit = {
    val blockSignPublicKeyBytes = PublicKey25519PropositionSerializer.getSerializer.toBytes(obj.blockSignPublicKey)
    w.putInt(blockSignPublicKeyBytes.length)
    w.putBytes(blockSignPublicKeyBytes)

    val vrfPublicKeyBytes = VrfPublicKeySerializer.getSerializer.toBytes(obj.vrfPublicKey)
    w.putInt(vrfPublicKeyBytes.length)
    w.putBytes(vrfPublicKeyBytes)

    w.putLong(obj.stakeAmount)
  }

  override def parse(r: Reader): ForgingStakeInfo = {
    val blockSignPublicKeyBytesLength = r.getInt()
    val blockSignPublicKey = PublicKey25519PropositionSerializer.getSerializer.parseBytes(r.getBytes(blockSignPublicKeyBytesLength))

    val vrfPublicKeyBytesLength = r.getInt()
    val vrfPublicKey = VrfPublicKeySerializer.getSerializer.parseBytes(r.getBytes(vrfPublicKeyBytesLength))

    val stakeAmount = r.getLong()

    ForgingStakeInfo(blockSignPublicKey, vrfPublicKey, stakeAmount)
  }
}