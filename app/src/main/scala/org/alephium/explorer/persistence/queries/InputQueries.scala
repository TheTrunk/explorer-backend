// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.explorer.persistence.queries

import slick.dbio.DBIOAction
import slick.jdbc.{PositionedParameters, SetParameter, SQLActionBuilder}
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.Hash
import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.model._
import org.alephium.explorer.persistence.schema.CustomGetResult._
import org.alephium.explorer.persistence.schema.CustomSetParameter._
import org.alephium.util.U256

object InputQueries {

  /** Inserts inputs or ignore rows with primary key conflict */
  // scalastyle:off magic.number
  def insertInputs(inputs: Iterable[InputEntity]): DBActionW[Int] =
    QuerySplitter.splitUpdates(rows = inputs, columnsPerRow = 11) { (inputs, placeholder) =>
      val query =
        s"""
           |INSERT INTO inputs ("block_hash",
           |                    "tx_hash",
           |                    "block_timestamp",
           |                    "hint",
           |                    "output_ref_key",
           |                    "unlock_script",
           |                    "main_chain",
           |                    "input_order",
           |                    "tx_order",
           |                    "output_ref_address",
           |                    "output_ref_amount")
           |VALUES $placeholder
           |ON CONFLICT
           |    ON CONSTRAINT inputs_pk
           |    DO NOTHING
           |""".stripMargin

      val parameters: SetParameter[Unit] =
        (_: Unit, params: PositionedParameters) =>
          inputs foreach { input =>
            params >> input.blockHash
            params >> input.txHash
            params >> input.timestamp
            params >> input.hint
            params >> input.outputRefKey
            params >> input.unlockScript
            params >> input.mainChain
            params >> input.inputOrder
            params >> input.txOrder
            params >> input.outputRefAddress
            params >> input.outputRefAmount
        }

      SQLActionBuilder(
        queryParts = query,
        unitPConv  = parameters
      ).asUpdate
    }

  // format: off
  def inputsFromTxsSQL(txHashes: Seq[Transaction.Hash]):
    DBActionR[Seq[(Transaction.Hash, Int, Int, Hash, Option[String], Address, U256, Option[Seq[Token]])]] = {
  // format: on
    if (txHashes.nonEmpty) {
      val values = txHashes.map(hash => s"'\\x$hash'").mkString(",")
      sql"""
    SELECT
      inputs.tx_hash,
      inputs.input_order,
      inputs.hint,
      inputs.output_ref_key,
      inputs.unlock_script,
      outputs.address,
      outputs.amount,
      outputs.tokens
    FROM inputs
    JOIN outputs ON inputs.output_ref_key = outputs.key AND outputs.main_chain = true
    WHERE inputs.tx_hash IN (#$values) AND inputs.main_chain = true
    """.as
    } else {
      DBIOAction.successful(Seq.empty)
    }
  }

  // format: off
  def inputsFromTxsNoJoin(hashes: Seq[(Transaction.Hash,BlockEntry.Hash)]):
    DBActionR[Seq[(Transaction.Hash, Int, Int, Hash, Option[String], Option[Address], Option[U256], Option[Seq[Token]])]] = {
  // format: on
    if (hashes.nonEmpty) {
      val values =
        hashes.map { case (txHash, blockHash) => s"('\\x$txHash','\\x$blockHash')" }.mkString(",")
      sql"""
    SELECT tx_hash, input_order, hint, output_ref_key, unlock_script, output_ref_address, output_ref_amount, output_ref_tokens
    FROM inputs
    WHERE (tx_hash, block_hash) IN (#$values)
    """.as
    } else {
      DBIOAction.successful(Seq.empty)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  def getInputsQuery(txHash: Transaction.Hash, blockHash: BlockEntry.Hash)
    : DBActionSR[(Int, Hash, Option[String], Option[Address], Option[U256], Option[Seq[Token]])] = {
    sql"""
    SELECT hint, output_ref_key, unlock_script, output_ref_address, output_ref_amount, output_ref_tokens
    FROM inputs
    WHERE tx_hash = $txHash
    AND block_hash = $blockHash
    ORDER BY input_order
    """.as
  }

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  val toApiInput = {
    (hint: Int,
     key: Hash,
     unlockScript: Option[String],
     address: Option[Address],
     amount: Option[U256],
     tokens: Option[Seq[Token]]) =>
      Input(OutputRef(hint, key), unlockScript, address, amount, tokens)
  }.tupled
}
