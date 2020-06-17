package org.alephium.explorer.persistence.dao

import scala.concurrent.{ExecutionContext, Future}

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import org.alephium.explorer.Hash
import org.alephium.explorer.api.model.Transaction
import org.alephium.explorer.persistence.DBRunner
import org.alephium.explorer.persistence.queries.TransactionQueries

trait TransactionDao {
  def get(hash: Hash): Future[Option[Transaction]]
}

object TransactionDao {
  def apply(config: DatabaseConfig[JdbcProfile])(
      implicit executionContext: ExecutionContext): TransactionDao =
    new Impl(config)

  private class Impl(val config: DatabaseConfig[JdbcProfile])(
      implicit val executionContext: ExecutionContext)
      extends TransactionDao
      with TransactionQueries
      with DBRunner {

    def get(hash: Hash): Future[Option[Transaction]] =
      run(getTransactionAction(hash))
  }
}
