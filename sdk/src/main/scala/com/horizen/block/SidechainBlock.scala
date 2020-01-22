package com.horizen.block

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonView}
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.google.common.primitives.{Bytes, Longs}
import com.horizen.box.{Box, ForgerBox, NoncedBox}
import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.params.NetworkParams
import com.horizen.proof.Signature25519
import com.horizen.proposition.{Proposition, PublicKey25519Proposition}
import com.horizen.secret.PrivateKey25519
import com.horizen.serialization.{ScorexModifierIdSerializer, Views}
import com.horizen.transaction.{BoxTransaction, SidechainTransaction, Transaction}
import com.horizen.utils.{ListSerializer, MerklePath}
import com.horizen.vrf.VRFProof
import com.horizen.{ScorexEncoding, SidechainTypes}
import scorex.core.block.Block
import scorex.core.serialization.ScorexSerializer
import scorex.core.{ModifierTypeId, NodeViewModifier, bytesToId, idToBytes}
import scorex.crypto.hash.Blake2b256
import scorex.util.ModifierId
import scorex.util.serialization.{Reader, Writer}

import scala.collection.JavaConverters._
import scala.util.Try

@JsonView(Array(classOf[Views.Default]))
@JsonIgnoreProperties(Array("messageToSign", "transactions", "forgerBox", "vrfProof", "merklePath", "version", "serializer", "modifierTypeId", "encoder", "companion"))
class SidechainBlock (
                       @JsonSerialize(using = classOf[ScorexModifierIdSerializer]) override val parentId: ModifierId,
                       override val timestamp: Block.Timestamp,
                       val mainchainBlocks : Seq[MainchainBlockReference],
                       val sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]],
                       val forgerPublicKey: PublicKey25519Proposition, // VRF public key from Forger box shall be used in future
                       val forgerBox: ForgerBox,
                       val vrfProof: VRFProof,
                       val merklePath: MerklePath,
                       val signature: Signature25519,
                       val companion: SidechainTransactionsCompanion)

  extends Block[SidechainTypes#SCBT]
{

  override type M = SidechainBlock

  override lazy val serializer = new SidechainBlockSerializer(companion)

  override lazy val version: Block.Version = 0: Byte

  override val modifierTypeId: ModifierTypeId = SidechainBlock.ModifierTypeId

  @JsonSerialize(using = classOf[ScorexModifierIdSerializer])
  override lazy val id: ModifierId =
    bytesToId(Blake2b256(Bytes.concat(messageToSign, signature.bytes, forgerBox.bytes(), vrfProof.bytes, merklePath.bytes())))

  override lazy val transactions: Seq[BoxTransaction[Proposition, Box[Proposition]]] = {
    var txs = Seq[BoxTransaction[Proposition, Box[Proposition]]]()

    for(b <- mainchainBlocks) {
      if (b.sidechainRelatedAggregatedTransaction.isDefined) {
        txs = txs :+ b.sidechainRelatedAggregatedTransaction.get
      }
    }
    for(tx <- sidechainTransactions)
      txs = txs :+ tx.asInstanceOf[BoxTransaction[Proposition, Box[Proposition]]]
    txs
  }

  lazy val messageToSign: Array[Byte] = {
    val sidechainTransactionsStream = new ByteArrayOutputStream
    sidechainTransactions.foreach {
      tx => sidechainTransactionsStream.write(tx.bytes)
    }

    val mainchainBlocksStream = new ByteArrayOutputStream
    mainchainBlocks.foreach {
      mcblock => mainchainBlocksStream.write(mcblock.bytes)
    }

    Bytes.concat(
      idToBytes(parentId),
      Longs.toByteArray(timestamp),
      sidechainTransactionsStream.toByteArray,
      mainchainBlocksStream.toByteArray,
      forgerPublicKey.bytes,
      forgerBox.bytes(),
      vrfProof.bytes,
      merklePath.bytes()
    )
  }

  def semanticValidity(params: NetworkParams): Boolean = {
    if(parentId == null || parentId.length != 64
        || sidechainTransactions == null || sidechainTransactions.size > SidechainBlock.MAX_SIDECHAIN_TXS_NUMBER
        || mainchainBlocks == null || mainchainBlocks.size > SidechainBlock.MAX_MC_BLOCKS_NUMBER
        || forgerPublicKey == null || signature == null)
      return false

    // Check if timestamp is valid and not too far in the future
    if(timestamp <= 0 || timestamp > Instant.now.getEpochSecond + 2 * 60 * 60) // 2 * 60 * 60 like in Horizen MC
      return false

    // check Block size
    val blockSize: Int = bytes.length
    if(blockSize > SidechainBlock.MAX_BLOCK_SIZE)
      return false

    // check, that signature is valid
    if(!signature.isValid(forgerPublicKey, messageToSign))
      return false

    // Check MainchainBlockReferences order in current block
    for(i <- 1 until mainchainBlocks.size) {
      if(!mainchainBlocks(i).header.hashPrevBlock.sameElements(mainchainBlocks(i-1).hash))
        return false
    }

    // check MainchainBlockReferences validity
    for(b <- mainchainBlocks)
      if(!b.semanticValidity(params))
        return false

    true
  }

  lazy val bestMainchainReferencePoW: Option[BigInteger] = {
    if (mainchainBlocks.isEmpty) {
      None
    }
    else {
      Option(mainchainBlocks.map(block => new BigInteger(1, block.hash)).min)
    }
  }
}


object SidechainBlock extends ScorexEncoding {
  val MAX_BLOCK_SIZE: Int = 2048 * 1024 //2048K
  val MAX_MC_BLOCKS_NUMBER: Int = 3
  val MAX_SIDECHAIN_TXS_NUMBER: Int = 1000
  val ModifierTypeId: ModifierTypeId = scorex.core.ModifierTypeId @@ 3.toByte

  def create(parentId: Block.BlockId,
             timestamp: Block.Timestamp,
             mainchainBlocks : Seq[MainchainBlockReference],
             sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]],
             ownerPrivateKey: PrivateKey25519,
             forgerBox: ForgerBox,
             vrfProof: VRFProof,
             merklePath: MerklePath,
             companion: SidechainTransactionsCompanion,
             params: NetworkParams,
             signatureOption: Option[Signature25519] = None // TO DO: later we should think about different unsigned/signed blocks creation methods
            ) : Try[SidechainBlock] = Try {
    require(parentId.length == 64)
    require(mainchainBlocks != null && mainchainBlocks.size <= SidechainBlock.MAX_MC_BLOCKS_NUMBER)
    require(sidechainTransactions != null)
    require(ownerPrivateKey != null)
    require(forgerBox != null)
    require(vrfProof != null)
    require(merklePath != null)

    val signature = signatureOption match {
      case Some(sig) => sig
      case None =>
        val unsignedBlock: SidechainBlock = new SidechainBlock(
          parentId,
          timestamp,
          mainchainBlocks,
          sidechainTransactions,
          ownerPrivateKey.publicImage(),
          forgerBox,
          vrfProof,
          merklePath,
          new Signature25519(new Array[Byte](Signature25519.SIGNATURE_LENGTH)), // empty signature
          companion
        )

        ownerPrivateKey.sign(unsignedBlock.messageToSign)
    }


    val block: SidechainBlock = new SidechainBlock(
      parentId,
      timestamp,
      mainchainBlocks,
      sidechainTransactions,
      ownerPrivateKey.publicImage(),
      forgerBox,
      vrfProof,
      merklePath,
      signature,
      companion
    )

    if(!block.semanticValidity(params))
      throw new Exception("Sidechain Block is semantically invalid.")

    block
  }
}



class SidechainBlockSerializer(companion: SidechainTransactionsCompanion) extends ScorexSerializer[SidechainBlock] {
  private val mcBlocksSerializer: ListSerializer[MainchainBlockReference] = new ListSerializer[MainchainBlockReference](
    MainchainBlockReferenceSerializer,
    SidechainBlock.MAX_MC_BLOCKS_NUMBER
  )

  private val sidechainTransactionsSerializer: ListSerializer[Transaction] = new ListSerializer[Transaction](
    companion,
    SidechainBlock.MAX_SIDECHAIN_TXS_NUMBER
  )

  override def serialize(obj: SidechainBlock, w: Writer): Unit = {
    w.putBytes(idToBytes(obj.parentId))
    w.putLong(obj.timestamp)

    val bw = w.newWriter()
    mcBlocksSerializer.serialize(obj.mainchainBlocks.toList.asJava, bw)
    w.putInt(bw.length())
    w.append(bw)

    val tw = w.newWriter()
    sidechainTransactionsSerializer.serialize(obj.sidechainTransactions.map(t => t.asInstanceOf[Transaction]).toList.asJava, tw)
    w.putInt(tw.length())
    w.append(tw)

    w.putBytes(obj.forgerPublicKey.bytes())
    w.putBytes(obj.forgerBox.bytes())
    w.putBytes(obj.vrfProof.bytes)
    w.putBytes(obj.signature.bytes())
    w.putBytes(obj.merklePath.bytes())
  }

  override def parse(r: Reader): SidechainBlock = {
    require(r.remaining <= SidechainBlock.MAX_BLOCK_SIZE)

    val parentId = bytesToId(r.getBytes(NodeViewModifier.ModifierIdSize))

    val timestamp = r.getLong()

    val mcbSize = r.getInt()

    if (r.remaining < mcbSize)
      throw new IllegalArgumentException("Input data corrupted.")

    val mcblocks: Seq[MainchainBlockReference] = mcBlocksSerializer.parse(r.newReader(r.getChunk(mcbSize))).asScala

    val txSize = r.getInt()

    if (r.remaining < txSize)
      throw new IllegalArgumentException("Input data corrupted.")

    val sidechainTransactions: Seq[SidechainTransaction[Proposition, NoncedBox[Proposition]]] =
      sidechainTransactionsSerializer.parse(r.newReader(r.getChunk(txSize)))
        .asScala
        .map(t => t.asInstanceOf[SidechainTransaction[Proposition, NoncedBox[Proposition]]])

    val owner = new PublicKey25519Proposition(r.getBytes(PublicKey25519Proposition.KEY_LENGTH))

    val forgerBox = ForgerBox.parseBytes(r.getBytes(ForgerBox.length()))

    val vrfProof = VRFProof.parseBytes(r.getBytes(VRFProof.length))

    val ownerSignature = new Signature25519(r.getBytes(Signature25519.SIGNATURE_LENGTH))

    val merklePath = MerklePath.parseBytes(r.getBytes(r.remaining))

    new SidechainBlock(
      parentId,
      timestamp,
      mcblocks,
      sidechainTransactions,
      owner,
      forgerBox,
      vrfProof,
      merklePath,
      ownerSignature,
      companion
    )
  }
}