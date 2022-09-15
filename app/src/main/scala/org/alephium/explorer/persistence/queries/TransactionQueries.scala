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

import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.StrictLogging
import slick.dbio.DBIOAction
import slick.jdbc.{PositionedParameters, SetParameter, SQLActionBuilder}
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.Hash
import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.model._
import org.alephium.explorer.persistence.queries.InputQueries._
import org.alephium.explorer.persistence.queries.OutputQueries._
import org.alephium.explorer.persistence.queries.result._
import org.alephium.explorer.persistence.schema._
import org.alephium.explorer.persistence.schema.CustomGetResult._
import org.alephium.explorer.persistence.schema.CustomJdbcTypes._
import org.alephium.explorer.persistence.schema.CustomSetParameter._
import org.alephium.explorer.util.SlickUtil._
import org.alephium.util.{TimeStamp, U256}

object TransactionQueries extends StrictLogging {

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  val mainTransactions = TransactionSchema.table.filter(_.mainChain)

  def insertAll(transactions: ArraySeq[TransactionEntity],
                outputs: ArraySeq[OutputEntity],
                inputs: ArraySeq[InputEntity]): DBActionRWT[Unit] = {
    DBIOAction
      .seq(insertTransactions(transactions), insertInputs(inputs), insertOutputs(outputs))
      .transactionally
  }

  /** Inserts transactions or ignore rows with primary key conflict */
  // scalastyle:off magic.number
  def insertTransactions(transactions: Iterable[TransactionEntity]): DBActionW[Int] =
    QuerySplitter.splitUpdates(rows = transactions, columnsPerRow = 12) {
      (transactions, placeholder) =>
        val query =
          s"""
           |insert into transactions (hash,
           |                          block_hash,
           |                          block_timestamp,
           |                          chain_from,
           |                          chain_to,
           |                          gas_amount,
           |                          gas_price,
           |                          tx_order,
           |                          main_chain,
           |                          script_execution_ok,
           |                          input_signatures,
           |                          script_signatures)
           |values $placeholder
           |ON CONFLICT ON CONSTRAINT txs_pk
           |    DO NOTHING
           |""".stripMargin

        val parameters: SetParameter[Unit] =
          (_: Unit, params: PositionedParameters) =>
            transactions foreach { transaction =>
              params >> transaction.hash
              params >> transaction.blockHash
              params >> transaction.timestamp
              params >> transaction.chainFrom
              params >> transaction.chainTo
              params >> transaction.gasAmount
              params >> transaction.gasPrice
              params >> transaction.order
              params >> transaction.mainChain
              params >> transaction.scriptExecutionOk
              params >> transaction.inputSignatures
              params >> transaction.scriptSignatures
          }

        SQLActionBuilder(
          queryParts = query,
          unitPConv  = parameters
        ).asUpdate
    }

  private val countBlockHashTransactionsQuery = Compiled { blockHash: Rep[BlockEntry.Hash] =>
    TransactionSchema.table.filter(_.blockHash === blockHash).length
  }

  def countBlockHashTransactions(blockHash: BlockEntry.Hash): DBActionR[Int] =
    countBlockHashTransactionsQuery(blockHash).result

  private val getTransactionQuery = Compiled { txHash: Rep[Transaction.Hash] =>
    mainTransactions
      .filter(_.hash === txHash)
      .map(tx => (tx.blockHash, tx.timestamp, tx.gasAmount, tx.gasPrice))
  }

  def getTransactionAction(txHash: Transaction.Hash)(
      implicit ec: ExecutionContext): DBActionR[Option[Transaction]] =
    getTransactionQuery(txHash).result.headOption.flatMap {
      case None => DBIOAction.successful(None)
      case Some((blockHash, timestamp, gasAmount, gasPrice)) =>
        getKnownTransactionAction(txHash, blockHash, timestamp, gasAmount, gasPrice).map(Some.apply)
    }

  def getOutputRefTransactionAction(key: Hash)(
      implicit ec: ExecutionContext): DBActionR[Option[Transaction]] = {
    OutputQueries.getTxnHash(key).flatMap { txHashes =>
      txHashes.headOption match {
        case None => DBIOAction.successful(None)
        case Some(txHash) =>
          getTransactionQuery(txHash).result.headOption.flatMap {
            case None => DBIOAction.successful(None)
            case Some((blockHash, timestamp, gasAmount, gasPrice)) =>
              getKnownTransactionAction(txHash, blockHash, timestamp, gasAmount, gasPrice).map(
                Some.apply)
          }
      }
    }
  }

  private def getTxHashesByBlockHashQuery(
      blockHash: BlockEntry.Hash): DBActionSR[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)] =
    sql"""
      SELECT hash, block_hash, block_timestamp, tx_order
      FROM transactions
      WHERE block_hash = $blockHash
      ORDER BY tx_order
    """.asAS[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)]

  private def getTxHashesByBlockHashWithPaginationQuery(blockHash: BlockEntry.Hash,
                                                        offset: Long,
                                                        limit: Long) =
    sql"""
      SELECT hash, block_hash, block_timestamp, tx_order
      FROM transactions
      WHERE block_hash = $blockHash
      ORDER BY tx_order
      LIMIT $limit
      OFFSET $offset
    """.asAS[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)]

  def countAddressTransactionsSQLNoJoin(address: Address): DBActionSR[Int] = {
    sql"""
    SELECT COUNT(*)
    FROM transaction_per_addresses
    WHERE main_chain = true AND address = $address
    """.asAS[Int]
  }

  def getTxHashesByAddressQuerySQLNoJoin(address: Address,
                                         offset: Int,
                                         limit: Int): DBActionSR[TxByAddressQR] = {
    sql"""
      SELECT tx_hash, block_hash, block_timestamp, tx_order
      FROM transaction_per_addresses
      WHERE main_chain = true AND address = $address
      ORDER BY block_timestamp DESC, tx_order
      LIMIT $limit
      OFFSET $offset
    """.asAS[TxByAddressQR]
  }

  def getTransactionsByBlockHash(blockHash: BlockEntry.Hash)(
      implicit ec: ExecutionContext): DBActionSR[Transaction] = {
    for {
      txHashesTs <- getTxHashesByBlockHashQuery(blockHash)
      txs        <- getTransactionsSQL(TxByAddressQR(txHashesTs))
    } yield txs
  }

  def getTransactionsByBlockHashWithPagination(blockHash: BlockEntry.Hash, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    val offset = pagination.offset.toLong
    val limit  = pagination.limit.toLong
    val toDrop = offset * limit
    for {
      txHashesTs <- getTxHashesByBlockHashWithPaginationQuery(blockHash, toDrop, limit)
      txs        <- getTransactionsSQL(TxByAddressQR(txHashesTs))
    } yield txs
  }

  def getTransactionsByAddressSQL(address: Address, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    val offset = pagination.offset
    val limit  = pagination.limit
    val toDrop = offset * limit
    for {
      txHashesTs <- getTxHashesByAddressQuerySQLNoJoin(address, toDrop, limit)
      txs        <- getTransactionsSQL(txHashesTs)
    } yield txs
  }

  def getTransactionsByAddressNoJoin(address: Address, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    val offset = pagination.offset
    val limit  = pagination.limit
    val toDrop = offset * limit
    for {
      txHashesTs <- getTxHashesByAddressQuerySQLNoJoin(address, toDrop, limit)
      txs        <- getTransactionsNoJoin(txHashesTs)
    } yield txs
  }

  def getTransactionsSQL(txHashesTs: ArraySeq[TxByAddressQR])(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    if (txHashesTs.nonEmpty) {
      val hashes   = txHashesTs.map(_.hashes())
      val txHashes = txHashesTs.map(_.txHash)
      for {
        inputs  <- inputsFromTxsSQL(txHashes)
        outputs <- outputsFromTxsSQL(txHashes)
        gases   <- gasFromTxsSQL(hashes)
      } yield {
        buildTransaction(txHashesTs, inputs, outputs, gases)
      }
    } else {
      DBIOAction.successful(ArraySeq.empty)
    }
  }

  def getTransactionsNoJoin(txHashesTs: ArraySeq[TxByAddressQR])(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    if (txHashesTs.nonEmpty) {
      val hashes = txHashesTs.map(_.hashes())
      for {
        inputs  <- inputsFromTxsNoJoin(hashes)
        outputs <- outputsFromTxsNoJoin(hashes)
        gases   <- gasFromTxsSQL(hashes)
      } yield {
        buildTransaction(txHashesTs, inputs, outputs, gases)
      }
    } else {
      DBIOAction.successful(ArraySeq.empty)
    }
  }

  private def buildTransaction(txHashesTs: ArraySeq[TxByAddressQR],
                               inputs: ArraySeq[InputsFromTxQR],
                               outputs: ArraySeq[OutputsFromTxQR],
                               gases: ArraySeq[GasFromTxsQR]) = {
    val insByTx = inputs.groupBy(_.txHash).view.mapValues { values =>
      values
        .sortBy(_.inputOrder)
        .map(_.toApiInput())
    }
    val ousByTx = outputs.groupBy(_.txHash).view.mapValues { values =>
      values
        .sortBy(_.outputOrder)
        .map(_.toApiOutput())
    }
    val gasByTx = gases.groupBy(_.txHash).view.mapValues(_.map(_.gasInfo()))
    txHashesTs.map { txn =>
      val ins                   = insByTx.getOrElse(txn.txHash, ArraySeq.empty)
      val ous                   = ousByTx.getOrElse(txn.txHash, ArraySeq.empty)
      val gas                   = gasByTx.getOrElse(txn.txHash, ArraySeq.empty)
      val (gasAmount, gasPrice) = gas.headOption.getOrElse((0, U256.Zero))
      Transaction(txn.txHash, txn.blockHash, txn.blockTimestamp, ins, ous, gasAmount, gasPrice)
    }
  }

  def gasFromTxsSQL(
      hashes: ArraySeq[(Transaction.Hash, BlockEntry.Hash)]): DBActionSR[GasFromTxsQR] = {
    if (hashes.nonEmpty) {
      val values =
        hashes.map { case (txHash, blockHash) => s"('\\x$txHash','\\x$blockHash')" }.mkString(",")
      sql"""
    SELECT hash, gas_amount, gas_price
    FROM transactions
    WHERE (hash, block_hash) IN (#$values)
    """.asAS[GasFromTxsQR]
    } else {
      DBIOAction.successful(ArraySeq.empty)
    }
  }

  private def getKnownTransactionAction(
      txHash: Transaction.Hash,
      blockHash: BlockEntry.Hash,
      timestamp: TimeStamp,
      gasAmount: Int,
      gasPrice: U256)(implicit ec: ExecutionContext): DBActionR[Transaction] =
    for {
      ins  <- getInputsQuery(txHash, blockHash)
      outs <- getOutputsQuery(txHash, blockHash)
    } yield {
      Transaction(txHash,
                  blockHash,
                  timestamp,
                  ins.map(_.toApiInput()),
                  outs.map(_.toApiOutput()),
                  gasAmount,
                  gasPrice)
    }

  def areAddressesActiveAction(addresses: ArraySeq[Address])(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Boolean]] =
    filterExistingAddresses(addresses.toSet) map { existing =>
      addresses map existing.contains
    }

  /** Filters input addresses that exist in DB */
  def filterExistingAddresses(addresses: Set[Address]): DBActionR[ArraySeq[Address]] =
    if (addresses.isEmpty) {
      DBIO.successful(ArraySeq.empty)
    } else {
      val query =
        List
          .fill(addresses.size) {
            "SELECT address FROM transaction_per_addresses WHERE address = ?"
          }
          .mkString("\nUNION\n")

      val parameters: SetParameter[Unit] =
        (_: Unit, params: PositionedParameters) =>
          addresses foreach { address =>
            params >> address
        }

      SQLActionBuilder(
        queryParts = query,
        unitPConv  = parameters
      ).asAS[Address]
    }

  def getBalanceAction(address: Address)(implicit ec: ExecutionContext): DBActionR[(U256, U256)] =
    getBalanceUntilLockTime(
      address  = address,
      lockTime = TimeStamp.now()
    ) map {
      case (total, locked) =>
        (total.getOrElse(U256.Zero), locked.getOrElse(U256.Zero))
    }

  // switch logger.trace when we can disable debugging mode
  protected def debugShow(query: slickProfile.ProfileAction[_, _, _]) = {
    print(s"${query.statements.mkString}\n")
  }
}
