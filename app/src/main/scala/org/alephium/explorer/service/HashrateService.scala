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

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.scalalogging.StrictLogging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import org.alephium.explorer.api.model.Hashrate
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.model.HashrateEntity
import org.alephium.explorer.persistence.queries.HashrateQueries
import org.alephium.explorer.persistence.schema._
import org.alephium.protocol.ALPH
import org.alephium.util.{Duration, TimeStamp}

trait HashrateService extends SyncService {
  def get(from: TimeStamp, to: TimeStamp, interval: Int): Future[Seq[Hashrate]]
}

object HashrateService {

  val tenMinStepBack: Duration = Duration.ofHoursUnsafe(2)
  val hourlyStepBack: Duration = Duration.ofHoursUnsafe(2)
  val dailyStepBack: Duration  = Duration.ofDaysUnsafe(1)

  def apply(syncPeriod: Duration, config: DatabaseConfig[JdbcProfile])(
      implicit executionContext: ExecutionContext): HashrateService =
    new Impl(syncPeriod, config)

  private class Impl(val syncPeriod: Duration, val config: DatabaseConfig[JdbcProfile])(
      implicit val executionContext: ExecutionContext)
      extends HashrateService
      with HashrateQueries
      with BlockHeaderSchema
      with HashrateSchema
      with DBRunner
      with StrictLogging {
    import config.profile.api._

    def syncOnce(): Future[Unit] = {
      logger.debug("Updating hashrates")
      val startedAt = TimeStamp.now()
      updateHashrates().map { _ =>
        val duration = TimeStamp.now().deltaUnsafe(startedAt)
        logger.debug(s"Hashrates updated in ${duration.millis} ms")
      }
    }

    def get(from: TimeStamp, to: TimeStamp, interval: Int): Future[Seq[Hashrate]] = {
      run(getHashratesQuery(from, to, interval)).map(_.map {
        case (timestamp, hashrate) =>
          Hashrate(timestamp, hashrate)
      })
    }

    private def updateHashrates(): Future[Unit] = {
      run(
        for {
          tenMinTs <- findLatestHashrateAndStepBack(0, compute10MinStepBack)
          hourlyTs <- findLatestHashrateAndStepBack(1, computeHourlyStepBack)
          dailyTs  <- findLatestHashrateAndStepBack(2, computeDailyStepBack)
          _        <- update1OMinutes(tenMinTs)
          _        <- updateHourly(hourlyTs)
          _        <- updateDaily(dailyTs)
        } yield ()
      )
    }

    private def update1OMinutes(from: TimeStamp) = {
      updateInterval(from, 0, compute10MinutesHashrate)
    }

    private def updateHourly(from: TimeStamp) = {
      updateInterval(from, 1, computeHourlyHashrate)
    }

    private def updateDaily(from: TimeStamp) = {
      updateInterval(from, 2, computeDailyHashrate)
    }

    private def updateInterval(from: TimeStamp,
                               interval: Int,
                               select: TimeStamp => DBActionSR[(TimeStamp, BigDecimal)]) = {
      select(from).flatMap { hashrates =>
        val values = hashrates.map {
          case (timestamp, hashrate) =>
            (timestamp, hashrate)
        }
        insert(values, interval)
      }
    }

    private def findLatestHashrate(intervalType: Int): DBActionR[Option[HashrateEntity]] = {
      hashrateTable
        .filter(_.intervalType === intervalType)
        .sortBy(_.timestamp.desc)
        .result
        .headOption
    }

    private def findLatestHashrateAndStepBack(
        intervalType: Int,
        computeStepBack: TimeStamp => TimeStamp): DBActionR[TimeStamp] = {
      findLatestHashrate(intervalType).map(
        _.map(h => computeStepBack(h.timestamp)).getOrElse(ALPH.LaunchTimestamp))
    }
  }

  /*
   * We truncate to a round value and add 1 millisecond to be sure
   * to recompute a complete time interval and not step back in the middle of it.
   */

  def compute10MinStepBack(timestamp: TimeStamp): TimeStamp = {
    truncatedToHour(timestamp.minusUnsafe(tenMinStepBack)).plusMillisUnsafe(1)
  }

  def computeHourlyStepBack(timestamp: TimeStamp): TimeStamp = {
    truncatedToHour(timestamp.minusUnsafe(hourlyStepBack)).plusMillisUnsafe(1)
  }

  def computeDailyStepBack(timestamp: TimeStamp): TimeStamp = {
    truncatedToDay(timestamp.minusUnsafe(dailyStepBack)).plusMillisUnsafe(1)
  }

  private def mapInstant(timestamp: TimeStamp)(f: Instant => Instant): TimeStamp = {
    val instant = Instant.ofEpochMilli(timestamp.millis)
    TimeStamp
      .unsafe(
        f(instant).toEpochMilli
      )
  }

  private def truncatedToHour(timestamp: TimeStamp): TimeStamp = {
    mapInstant(timestamp)(_.truncatedTo(ChronoUnit.HOURS))
  }

  private def truncatedToDay(timestamp: TimeStamp): TimeStamp = {
    mapInstant(timestamp)(_.truncatedTo(ChronoUnit.DAYS))
  }
}
