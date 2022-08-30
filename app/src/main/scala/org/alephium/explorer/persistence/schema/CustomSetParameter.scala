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

package org.alephium.explorer.persistence.schema

import java.math.BigInteger

import akka.util.ByteString
import slick.jdbc.{PositionedParameters, SetParameter}

import org.alephium.explorer
import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence.model.OutputEntity
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp, U256}

/** [[slick.jdbc.SetParameter]] implicits for setting values in SQL queries */
object CustomSetParameter {

  implicit object BlockEntryHashSetParameter extends SetParameter[BlockEntry.Hash] {
    override def apply(input: BlockEntry.Hash, params: PositionedParameters): Unit =
      params setBytes input.value.bytes.toArray
  }

  implicit object TokenSetParameter extends SetParameter[Token] {
    override def apply(input: Token, params: PositionedParameters): Unit =
      params setBytes input.id.bytes.toArray
  }

  implicit object GroupIndexSetParameter extends SetParameter[GroupIndex] {
    override def apply(input: GroupIndex, params: PositionedParameters): Unit =
      params setInt input.value
  }

  implicit object IntervalTypeSetParameter extends SetParameter[IntervalType] {
    override def apply(input: IntervalType, params: PositionedParameters): Unit =
      params setInt input.value
  }

  implicit object OutputTypeSetParameter extends SetParameter[OutputEntity.OutputType] {
    override def apply(input: OutputEntity.OutputType, params: PositionedParameters): Unit =
      params setInt input.value
  }

  implicit object ExplorerHashSetParameter extends SetParameter[explorer.Hash] {
    override def apply(input: explorer.Hash, params: PositionedParameters): Unit = {
      params setBytes input.bytes.toArray
    }
  }

  implicit object U256SetParameter extends SetParameter[U256] {
    override def apply(input: U256, params: PositionedParameters): Unit =
      params setBigDecimal BigDecimal(input.toBigInt)
  }

  implicit object OptionU256SetParameter extends SetParameter[Option[U256]] {
    override def apply(option: Option[U256], params: PositionedParameters): Unit =
      option match {
        case Some(u256) =>
          U256SetParameter(u256, params)

        case None =>
          params setBigDecimalOption None
      }
  }

  implicit object AddressSetParameter extends SetParameter[Address] {
    override def apply(input: Address, params: PositionedParameters): Unit =
      params setString input.value
  }

  implicit object OptionAddressSetParameter extends SetParameter[Option[Address]] {
    override def apply(option: Option[Address], params: PositionedParameters): Unit =
      option match {
        case Some(address) =>
          AddressSetParameter(address, params)

        case None =>
          params setStringOption None
      }
  }

  implicit object ByteStringSetParameter extends SetParameter[ByteString] {
    override def apply(input: ByteString, params: PositionedParameters): Unit =
      params setBytes input.toArray
  }

  implicit object ByteStringOptionSetParameter extends SetParameter[Option[ByteString]] {

    /** {{{Params.setBytesOption(input.map(_.value.bytes.toArray[Byte]))}}} sets the value
      * to java.lang.Object instead of null which fails and requires casting at SQL level.
      *
      * ERROR: org.postgresql.util.PSQLException: ERROR: column "***" is of type bytea but expression is of type oid
      * Hint: You will need to rewrite or cast the expression.
      *
      * To keep this simple `null` is used and which set the column value as expected.
      */
    override def apply(input: Option[ByteString], params: PositionedParameters): Unit =
      input match {
        case Some(bytes) =>
          params setBytes bytes.toArray

        case None =>
          //scalastyle:off null
          params setBytes null
        //scalastyle:on null
      }
  }

  implicit object TokensOptionSetParameter extends SetParameter[Option[Seq[Token]]] {

    /** {{{Params.setBytesOption(input.map(_.value.bytes.toArray[Byte]))}}} sets the value
      * to java.lang.Object instead of null which fails and requires casting at SQL level.
      *
      * ERROR: org.postgresql.util.PSQLException: ERROR: column "***" is of type bytea but expression is of type oid
      * Hint: You will need to rewrite or cast the expression.
      *
      * To keep this simple `null` is used and which set the column value as expected.
      */
    override def apply(input: Option[Seq[Token]], params: PositionedParameters): Unit =
      input match {
        case Some(tokens) =>
          params setBytes serialize(AVector.unsafe(tokens.toArray)).toArray

        case None =>
          //scalastyle:off null
          params setBytes null
        //scalastyle:on null
      }
  }

  implicit object ByteStringsOptionSetParameter extends SetParameter[Option[Seq[ByteString]]] {
    override def apply(input: Option[Seq[ByteString]], params: PositionedParameters): Unit =
      input match {
        case Some(byteStrings) =>
          params setBytes serialize(AVector.unsafe(byteStrings.toArray)).toArray

        case None =>
          //scalastyle:off null
          params setBytes null
        //scalastyle:on null
      }
  }

  implicit object BigIntegerSetParameter extends SetParameter[BigInteger] {
    override def apply(input: BigInteger, params: PositionedParameters): Unit =
      params setBigDecimal BigDecimal(input)
  }

  implicit object HeightSetParameter extends SetParameter[Height] {
    override def apply(input: Height, params: PositionedParameters): Unit =
      params setInt input.value
  }

  implicit object TransactionHashSetParameter extends SetParameter[Transaction.Hash] {
    override def apply(input: Transaction.Hash, params: PositionedParameters): Unit =
      params setBytes input.value.bytes.toArray
  }

  implicit object BlockEntryHashOptionSetParameter extends SetParameter[Option[BlockEntry.Hash]] {

    /** {{{Params.setBytesOption(input.map(_.value.bytes.toArray[Byte]))}}} sets the value
      * to java.lang.Object instead of null which fails and requires casting at SQL level.
      *
      * ERROR: org.postgresql.util.PSQLException: ERROR: column "***" is of type bytea but expression is of type oid
      * Hint: You will need to rewrite or cast the expression.
      *
      * To keep this simple `null` is used and which set the column value as expected.
      */
    override def apply(input: Option[BlockEntry.Hash], params: PositionedParameters): Unit =
      input match {
        case Some(value) =>
          params setBytes value.value.bytes.toArray

        case None =>
          //scalastyle:off null
          params setBytes null
        //scalastyle:on null
      }
  }

  implicit object TransationHashOptionSetParameter extends SetParameter[Option[Transaction.Hash]] {
    override def apply(input: Option[Transaction.Hash], params: PositionedParameters): Unit =
      input match {
        case Some(value) =>
          params setBytes value.value.bytes.toArray

        case None =>
          //scalastyle:off null
          params setBytes null
        //scalastyle:on null
      }
  }
  implicit object TimeStampSetParameter extends SetParameter[TimeStamp] {
    override def apply(input: TimeStamp, params: PositionedParameters): Unit =
      params setLong input.millis
  }

  implicit object TimeStampOptionSetParameter extends SetParameter[Option[TimeStamp]] {
    override def apply(option: Option[TimeStamp], params: PositionedParameters): Unit =
      option match {
        case Some(timestamp) =>
          TimeStampSetParameter(timestamp, params)

        case None =>
          params setTimestampOption None
      }
  }
}
