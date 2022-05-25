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

package org.alephium.explorer.web

import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.server.Route
import slick.basic.DatabaseConfig
import slick.jdbc.PostgresProfile

import org.alephium.explorer.{sideEffect, GroupSetting}
import org.alephium.explorer.api.UtilsEndpoints
import org.alephium.explorer.cache.BlockCache
import org.alephium.explorer.service.{BlockFlowClient, SanityChecker}

class UtilsServer()(implicit ec: ExecutionContext,
                    dc: DatabaseConfig[PostgresProfile],
                    blockFlowClient: BlockFlowClient,
                    blockCache: BlockCache,
                    groupSetting: GroupSetting)
    extends Server
    with UtilsEndpoints {

  val route: Route =
    toRoute(sanityCheck) { _ =>
      sideEffect(SanityChecker.check())
      Future.successful(Right(()))
    }
}
