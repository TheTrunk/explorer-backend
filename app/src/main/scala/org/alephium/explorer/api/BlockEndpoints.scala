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

package org.alephium.explorer.api

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.{alphJsonBody => jsonBody}
import org.alephium.api.UtilJson._
import org.alephium.explorer.api.BaseEndpoint
import org.alephium.explorer.api.Codecs.blockEntryHashTapirCodec
import org.alephium.explorer.api.model._
import org.alephium.util.AVector

trait BlockEndpoints extends BaseEndpoint with QueryParams {

  private val blocksEndpoint =
    baseEndpoint
      .tag("Blocks")
      .in("blocks")

  val getBlockByHash: BaseEndpoint[BlockEntry.Hash, BlockEntryLite] =
    blocksEndpoint.get
      .in(path[BlockEntry.Hash]("block-hash"))
      .out(jsonBody[BlockEntryLite])
      .description("Get a block with hash")

  val getBlockTransactions: BaseEndpoint[(BlockEntry.Hash, Pagination), AVector[Transaction]] =
    blocksEndpoint.get
      .in(path[BlockEntry.Hash]("block-hash"))
      .in("transactions")
      .in(pagination)
      .out(jsonBody[AVector[Transaction]])
      .description("Get block's transactions")

  val listBlocks: BaseEndpoint[Pagination, ListBlocks] =
    blocksEndpoint.get
      .in(pagination)
      .out(jsonBody[ListBlocks])
      .description("List blocks within time interval")
}
