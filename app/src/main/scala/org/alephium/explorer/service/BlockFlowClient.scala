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

import java.math.BigInteger
import java.net.InetAddress

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

import akka.http.scaladsl.model.Uri
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import sttp.client3._

import org.alephium.api
import org.alephium.api.Endpoints
import org.alephium.api.model.{ChainInfo, ChainParams, HashesAtHeight, SelfClique}
import org.alephium.explorer.{GroupSetting, Hash}
import org.alephium.explorer.api.model._
import org.alephium.explorer.error.ExplorerError
import org.alephium.explorer.error.ExplorerError._
import org.alephium.explorer.persistence.model._
import org.alephium.http.EndpointSender
import org.alephium.protocol
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.mining.HashRate
import org.alephium.protocol.model.{Hint, Target}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Duration, Service, TimeStamp, U256}

trait BlockFlowClient extends Service {
  def fetchBlock(fromGroup: GroupIndex, hash: BlockEntry.Hash): Future[BlockEntity]

  def fetchChainInfo(fromGroup: GroupIndex, toGroup: GroupIndex): Future[ChainInfo]

  def fetchHashesAtHeight(fromGroup: GroupIndex,
                          toGroup: GroupIndex,
                          height: Height): Future[HashesAtHeight]

  def fetchBlocks(fromTs: TimeStamp, toTs: TimeStamp, uri: Uri): Future[Seq[Seq[BlockEntity]]]

  def fetchBlocksAtHeight(fromGroup: GroupIndex, toGroup: GroupIndex, height: Height)(
      implicit executionContext: ExecutionContext): Future[Seq[BlockEntity]] =
    fetchHashesAtHeight(fromGroup, toGroup, height).flatMap { hashesAtHeight =>
      Future
        .sequence(
          hashesAtHeight.headers
            .map(hash => fetchBlock(fromGroup, new BlockEntry.Hash(hash)))
            .toSeq)
    }

  def fetchSelfClique(): Future[SelfClique]

  def fetchChainParams(): Future[ChainParams]

  def fetchUnconfirmedTransactions(uri: Uri): Future[Seq[UnconfirmedTransaction]]

  def start(): Future[Unit]

  def close(): Future[Unit]
}

object BlockFlowClient extends StrictLogging {
  def apply(uri: Uri, groupNum: Int, maybeApiKey: Option[api.model.ApiKey])(
      implicit executionContext: ExecutionContext
  ): BlockFlowClient =
    new Impl(uri, groupNum, maybeApiKey)

  private class Impl(uri: Uri, groupNum: Int, val maybeApiKey: Option[api.model.ApiKey])(
      implicit val executionContext: ExecutionContext
  ) extends BlockFlowClient
      with Endpoints {

    private val endpointSender = new EndpointSender(maybeApiKey)

    override def startSelfOnce(): Future[Unit] = {
      endpointSender.start()
    }

    override def stopSelfOnce(): Future[Unit] = {
      close()
    }

    override def subServices: ArraySeq[Service] = ArraySeq.empty

    implicit val groupSetting: GroupSetting = GroupSetting(groupNum)

    implicit val groupConfig: GroupConfig = groupSetting.groupConfig

    private implicit def groupIndexConversion(x: GroupIndex): protocol.model.GroupIndex =
      protocol.model.GroupIndex.unsafe(x.value)

    private def _send[A, B](
        endpoint: BaseEndpoint[A, B],
        uri: Uri,
        a: A
    ): Future[B] = {
      endpointSender
        .send(endpoint, a, uri"${uri.toString}")
        .flatMap {
          case Right(res) => Future.successful(res)
          case Left(error) =>
            Future.failed(NodeApiError(error.detail))
        }
        .recoverWith { error =>
          Future.failed(UnreachableNode(error))
        }
    }

    def fetchBlock(fromGroup: GroupIndex, hash: BlockEntry.Hash): Future[BlockEntity] =
      fetchSelfCliqueAndChainParams().flatMap {
        case (selfClique, chainParams) =>
          selfCliqueIndex(selfClique, chainParams, fromGroup) match {
            case Left(error) => Future.failed(new Throwable(error))
            case Right((nodeAddress, restPort)) =>
              val uri = s"http://${nodeAddress.getHostAddress}:${restPort}"
              _send(getBlock, uri, hash.value).map(blockProtocolToEntity)
          }
      }

    def fetchChainInfo(fromGroup: GroupIndex, toGroup: GroupIndex): Future[ChainInfo] = {
      _send(getChainInfo, uri, protocol.model.ChainIndex(fromGroup, toGroup))
    }

    def fetchHashesAtHeight(fromGroup: GroupIndex,
                            toGroup: GroupIndex,
                            height: Height): Future[HashesAtHeight] =
      _send(getHashesAtHeight, uri, (protocol.model.ChainIndex(fromGroup, toGroup), height.value))

    def fetchBlocks(fromTs: TimeStamp, toTs: TimeStamp, uri: Uri): Future[Seq[Seq[BlockEntity]]] = {
      _send(getBlockflow, uri, api.model.TimeInterval(fromTs, toTs))
        .map(_.blocks.map(_.map(blockProtocolToEntity).toSeq).toSeq)
    }

    def fetchUnconfirmedTransactions(uri: Uri): Future[Seq[UnconfirmedTransaction]] =
      _send(listUnconfirmedTransactions, uri, ())
        .map { utxs =>
          utxs.flatMap { utx =>
            utx.unconfirmedTransactions.map { tx =>
              val inputs  = tx.unsigned.inputs.map(inputToUInput).toSeq
              val outputs = tx.unsigned.fixedOutputs.map(outputToUOutput).toSeq
              txToUTx(tx, utx.fromGroup, utx.toGroup, inputs, outputs, TimeStamp.now())
            }
          }.toSeq
        }

    def fetchSelfClique(): Future[SelfClique] =
      _send(getSelfClique, uri, ())

    def fetchChainParams(): Future[ChainParams] =
      _send(getChainParams, uri, ())

    private def fetchSelfCliqueAndChainParams(): Future[(SelfClique, ChainParams)] = {
      fetchSelfClique().flatMap { selfClique =>
        fetchChainParams().map(chainParams => (selfClique, chainParams))
      }
    }

    private def selfCliqueIndex(selfClique: SelfClique,
                                chainParams: ChainParams,
                                group: GroupIndex): Either[ExplorerError, (InetAddress, Int)] = {
      if (chainParams.groupNumPerBroker <= 0) {
        Left(InvalidChainGroupNumPerBroker(chainParams.groupNumPerBroker))
      } else {
        Right(selfClique.peer(group)).map(node => (node.address, node.restPort))
      }
    }

    override def close(): Future[Unit] = {
      endpointSender.stop()
    }
  }

  def blockProtocolToInputEntities(block: api.model.BlockEntry): Seq[InputEntity] = {
    val hash         = new BlockEntry.Hash(block.hash)
    val mainChain    = false
    val transactions = block.transactions.toSeq.zipWithIndex
    val inputs =
      transactions.flatMap {
        case (tx, txOrder) =>
          tx.unsigned.inputs.toSeq.zipWithIndex.map {
            case (in, index) =>
              inputToEntity(in, hash, tx.unsigned.txId, block.timestamp, mainChain, index, txOrder)
          }
      }
    val contractInputs =
      transactions.flatMap {
        case (tx, txOrder) =>
          tx.contractInputs.toSeq.zipWithIndex.map {
            case (outputRef, index) =>
              val shiftIndex = index + tx.unsigned.inputs.length
              outputRefToInputEntity(outputRef,
                                     hash,
                                     tx.unsigned.txId,
                                     block.timestamp,
                                     mainChain,
                                     shiftIndex,
                                     txOrder)
          }
      }
    inputs ++ contractInputs
  }

  def blockProtocolToOutputEntities(block: api.model.BlockEntry): Seq[OutputEntity] = {
    val hash         = new BlockEntry.Hash(block.hash)
    val mainChain    = false
    val transactions = block.transactions.toSeq.zipWithIndex
    val outputs =
      transactions.flatMap {
        case (tx, txOrder) =>
          tx.unsigned.fixedOutputs.toSeq.zipWithIndex.map {
            case (out, index) =>
              outputToEntity(out.upCast(),
                             hash,
                             tx.unsigned.txId,
                             index,
                             block.timestamp,
                             mainChain,
                             txOrder)
          }
      }
    val generatedOutputs =
      transactions.flatMap {
        case (tx, txOrder) =>
          tx.generatedOutputs.toSeq.zipWithIndex.map {
            case (out, index) =>
              val shiftIndex = index + tx.unsigned.fixedOutputs.length
              outputToEntity(out,
                             hash,
                             tx.unsigned.txId,
                             shiftIndex,
                             block.timestamp,
                             mainChain,
                             txOrder)
          }
      }
    outputs ++ generatedOutputs
  }
  def blockProtocolToEntity(block: api.model.BlockEntry)(
      implicit groupSetting: GroupSetting): BlockEntity = {
    val hash         = new BlockEntry.Hash(block.hash)
    val mainChain    = false
    val transactions = block.transactions.toSeq.zipWithIndex
    val chainFrom    = block.chainFrom
    val chainTo      = block.chainTo
    val inputs       = blockProtocolToInputEntities(block)
    val outputs      = blockProtocolToOutputEntities(block)
    BlockEntity(
      hash,
      block.timestamp,
      GroupIndex.unsafe(block.chainFrom),
      GroupIndex.unsafe(block.chainTo),
      Height.unsafe(block.height),
      block.deps.map(new BlockEntry.Hash(_)).toSeq,
      transactions.map {
        case (tx, index) =>
          txToEntity(tx, hash, block.timestamp, index, mainChain, chainFrom, chainTo)
      },
      inputs,
      outputs,
      mainChain = mainChain,
      block.nonce,
      block.version,
      block.depStateHash,
      block.txsHash,
      block.target,
      computeHashRate(block.target)
    )
  }

  private def txToUTx(tx: api.model.TransactionTemplate,
                      chainFrom: Int,
                      chainTo: Int,
                      inputs: Seq[UInput],
                      outputs: Seq[UOutput],
                      timestamp: TimeStamp): UnconfirmedTransaction =
    UnconfirmedTransaction(
      new Transaction.Hash(tx.unsigned.txId),
      GroupIndex.unsafe(chainFrom),
      GroupIndex.unsafe(chainTo),
      inputs,
      outputs,
      tx.unsigned.gasAmount,
      tx.unsigned.gasPrice,
      timestamp
    )

  private def txToEntity(tx: api.model.Transaction,
                         blockHash: BlockEntry.Hash,
                         timestamp: TimeStamp,
                         index: Int,
                         mainChain: Boolean,
                         chainFrom: Int,
                         chainTo: Int): TransactionEntity =
    TransactionEntity(
      new Transaction.Hash(tx.unsigned.txId),
      blockHash,
      timestamp,
      GroupIndex.unsafe(chainFrom),
      GroupIndex.unsafe(chainTo),
      tx.unsigned.gasAmount,
      tx.unsigned.gasPrice,
      index,
      mainChain,
      tx.scriptExecutionOk,
      if (tx.inputSignatures.isEmpty) None else Some(tx.inputSignatures.toSeq),
      if (tx.scriptSignatures.isEmpty) None else Some(tx.scriptSignatures.toSeq)
    )

  private def addressFromProtocolInput(input: api.model.AssetInput): Option[Address] =
    input.toProtocol() match {
      case Right(value) =>
        value.unlockScript match {
          case protocol.vm.UnlockScript.P2PKH(pk) =>
            Some(Address.unsafe(protocol.model.Address.p2pkh(pk).toBase58)): Option[Address]
          case protocol.vm.UnlockScript.P2SH(script, _) =>
            val lockup = protocol.vm.LockupScript.p2sh(protocol.Hash.hash(script))
            Some(Address.unsafe(protocol.model.Address.from(lockup).toBase58)): Option[Address]
          case protocol.vm.UnlockScript.P2MPKH(_) =>
            None
        }
      case Left(error) =>
        logger.error(s"Cannot decode protocol input: $error")
        None
    }

  private def inputToUInput(input: api.model.AssetInput): UInput = {
    UInput(
      OutputRef(input.outputRef.hint, input.outputRef.key),
      addressFromProtocolInput(input),
      Some(input.unlockScript)
    )
  }

  private def inputToEntity(input: api.model.AssetInput,
                            blockHash: BlockEntry.Hash,
                            txId: Hash,
                            timestamp: TimeStamp,
                            mainChain: Boolean,
                            index: Int,
                            txOrder: Int): InputEntity = {
    InputEntity(
      blockHash,
      new Transaction.Hash(txId),
      timestamp,
      input.outputRef.hint,
      input.outputRef.key,
      Some(input.unlockScript),
      mainChain,
      index,
      txOrder,
      addressFromProtocolInput(input),
      None,
      None
    )
  }

  private def outputRefToInputEntity(outputRef: api.model.OutputRef,
                                     blockHash: BlockEntry.Hash,
                                     txId: Hash,
                                     timestamp: TimeStamp,
                                     mainChain: Boolean,
                                     index: Int,
                                     txOrder: Int): InputEntity = {
    InputEntity(
      blockHash,
      new Transaction.Hash(txId),
      timestamp,
      outputRef.hint,
      outputRef.key,
      None,
      mainChain,
      index,
      txOrder,
      None,
      None,
      None
    )
  }

  private def outputToUOutput(output: api.model.FixedAssetOutput): UOutput = {
    val lockTime = output match {
      case asset: api.model.FixedAssetOutput if asset.lockTime.millis > 0 => Some(asset.lockTime)
      case _                                                              => None
    }
    UOutput(
      output.attoAlphAmount.value,
      new Address(output.address.toBase58),
      lockTime
    )
  }

  // scalastyle:off method.length
  private def outputToEntity(output: api.model.Output,
                             blockHash: BlockEntry.Hash,
                             txId: Hash,
                             index: Int,
                             timestamp: TimeStamp,
                             mainChain: Boolean,
                             txOrder: Int): OutputEntity = {
    val lockTime = output match {
      case asset: api.model.AssetOutput if asset.lockTime.millis > 0 => Some(asset.lockTime)
      case _                                                         => None
    }

    val hint = output.address.lockupScript match {
      case asset: LockupScript.Asset  => Hint.ofAsset(asset.scriptHint)
      case contract: LockupScript.P2C => Hint.ofContract(contract.scriptHint)
    }

    val outputType: OutputEntity.OutputType = output match {
      case _: api.model.AssetOutput    => OutputEntity.Asset
      case _: api.model.ContractOutput => OutputEntity.Contract
    }

    val message = output match {
      case asset: api.model.AssetOutput => Some(asset.message)
      case _: api.model.ContractOutput  => None
    }

    val tokens = if (output.tokens.isEmpty) {
      None
    } else {
      Some(
        output.tokens
          .groupBy(_.id)
          .map {
            case (id, tokens) =>
              val amount = tokens.map(_.amount).fold(U256.Zero)(_ addUnsafe _)
              Token(id, amount)
          }
          .toSeq)
    }

    OutputEntity(
      blockHash,
      new Transaction.Hash(txId),
      timestamp,
      outputType,
      hint.value,
      protocol.model.TxOutputRef.key(txId, index),
      output.attoAlphAmount.value,
      new Address(output.address.toBase58),
      tokens,
      mainChain,
      lockTime,
      message,
      index,
      txOrder,
      None
    )
  }

  // scalastyle:off magic.number
  def computeHashRate(targetBytes: ByteString)(implicit groupSetting: GroupSetting): BigInteger = {
    val target          = Target.unsafe(targetBytes)
    val blockTargetTime = Duration.ofSecondsUnsafe(64) //TODO add this to config
    HashRate.from(target, blockTargetTime)(groupSetting.groupConfig).value
  }
  // scalastyle:on magic.number
}
