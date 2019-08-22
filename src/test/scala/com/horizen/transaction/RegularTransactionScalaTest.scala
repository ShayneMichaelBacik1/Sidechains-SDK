package com.horizen.transaction

import java.lang.{Long => JLong}
import java.util.{ArrayList => JArrayList}

import javafx.util.{Pair => JPair}
import com.horizen.box.RegularBox
import com.horizen.proposition.PublicKey25519Proposition
import com.horizen.secret.{PrivateKey25519, PrivateKey25519Creator}
import io.circe.Json
import org.junit.Assert.assertEquals
import org.junit.Test

import org.scalatest.junit.JUnitSuite
import scorex.core.utils.ScorexEncoder
import scorex.crypto.signatures.Curve25519

class RegularTransactionScalaTest
  extends JUnitSuite
{

  @Test
  def testToJson(): Unit = {
    val fee = 10
    val timestamp = 1547798549470L

    val from = new JArrayList[JPair[RegularBox, PrivateKey25519]]
    val to = new JArrayList[JPair[PublicKey25519Proposition, JLong]]

    val creator = PrivateKey25519Creator.getInstance
    val pk1 = creator.generateSecret("test_seed1".getBytes)
    val pk2 = creator.generateSecret("test_seed2".getBytes)
    val pk3 = creator.generateSecret("test_seed3".getBytes)

    from.add(new JPair[RegularBox, PrivateKey25519](new RegularBox(pk1.publicImage, 1, 60), pk1))
    from.add(new JPair[RegularBox, PrivateKey25519](new RegularBox(pk2.publicImage, 1, 50), pk2))
    from.add(new JPair[RegularBox, PrivateKey25519](new RegularBox(pk3.publicImage, 1, 20), pk3))

    val pk4 = creator.generateSecret("test_seed4".getBytes)
    val pk5 = creator.generateSecret("test_seed5".getBytes)
    val pk6 = creator.generateSecret("test_seed6".getBytes)

    to.add(new JPair[PublicKey25519Proposition, JLong](pk4.publicImage, 10L))
    to.add(new JPair[PublicKey25519Proposition, JLong](pk5.publicImage, 20L))
    to.add(new JPair[PublicKey25519Proposition, JLong](pk6.publicImage, 90L))

    val transaction = RegularTransaction.create(from, to, fee, timestamp)

    val json = transaction.toJson

    json.hcursor.get[String]("id") match {
      case Right(id) => assertEquals("Transaction id json value must be the same.",
        ScorexEncoder.default.encode(transaction.id), id)
      case Left(decodingFailure) => fail("Transaction id doesn't not found in json.")
    }

    json.hcursor.get[Long]("fee") match {
      case Right(fee) => assertEquals("Transaction fee json value must be the same.",
        transaction.fee(), fee)
      case Left(decodingFailure) => fail("Transaction fee doesn't not found in json.")
    }

    json.hcursor.get[Long]("timestamp") match {
      case Right(timestamp) => assertEquals("Transaction timestamp json value must be the same.",
        transaction.timestamp(), timestamp)
      case Left(decodingFailure) => fail("Transaction timestamp doesn't not found in json.")
    }

    json.hcursor.get[Json]("inputs") match {
      case Right(i) =>
        i.asArray match {
          case Some(inputs) => assertEquals("Count of transaction inputs in json must be the same.",
            transaction.unlockers().size(), inputs.size)
          case None => fail("Transaction inputs in json have invalid format.")
        }
      case Left(decodingFailure) => fail("Transaction inputs do not found in json.")
    }

    json.hcursor.get[Json]("newBoxes") match {
      case Right(i) =>
        i.asArray match {
          case Some(newBoxes) => assertEquals("Count of transaction new boxes in json must be the same.",
            transaction.newBoxes().size(), newBoxes.size)
          case None => fail("Transaction newBoxes in json have invalid format.")
        }
      case Left(decodingFailure) => fail("Transaction new boxes do not found in json.")
    }
  }
}