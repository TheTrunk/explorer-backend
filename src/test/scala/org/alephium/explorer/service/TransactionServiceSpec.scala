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

package org.alephium.explorer.service

import scala.concurrent.{ExecutionContext, Future}

import org.scalacheck.Gen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Span}

import org.alephium.explorer.{AlephiumSpec, Generators}
import org.alephium.explorer.api.model.GroupIndex
import org.alephium.explorer.persistence.DatabaseFixture
import org.alephium.explorer.persistence.dao.{BlockDao, TransactionDao}

@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.DefaultArguments"))
class TransactionServiceSpec
    extends AlephiumSpec
    with DatabaseFixture
    with Generators
    with ScalaFutures
    with Eventually {

  implicit val executionContext: ExecutionContext = ExecutionContext.global
  override implicit val patienceConfig            = PatienceConfig(timeout = Span(1, Minutes))

  val blockDao: BlockDao                     = BlockDao(databaseConfig)
  val transactionDao: TransactionDao         = TransactionDao(databaseConfig)
  val transactionService: TransactionService = TransactionService(transactionDao)

  it should "limit the number of transactions in address details" in {

    val transactionService: TransactionService = TransactionService(transactionDao)

    val address = addressGen.sample.get

    val groupIndex = GroupIndex.unsafe(0)
    val blocks = Gen
      .listOfN(20, blockEntityGen(0, groupIndex, groupIndex, None))
      .map(_.map { block =>
        block.copy(outputs = block.outputs.map(_.copy(address = address)))
      })
      .sample
      .get

    val txLimit = 5

    Future.sequence(blocks.map(blockDao.insert)).futureValue
    Future
      .sequence(blocks.map(block => blockDao.updateMainChainStatus(block.hash, true)))
      .futureValue

    transactionService.getTransactionsByAddress(address, txLimit).futureValue.size is txLimit
  }
}
