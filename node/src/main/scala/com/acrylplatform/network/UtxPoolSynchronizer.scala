package com.acrylplatform.network

import com.google.common.cache.CacheBuilder
import com.acrylplatform.common.state.ByteStr
import com.acrylplatform.settings.SynchronizationSettings.UtxSynchronizerSettings
import com.acrylplatform.transaction.Transaction
import com.acrylplatform.utils.ScorexLogging
import com.acrylplatform.utx.UtxPool
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import monix.reactive.{Observable, OverflowStrategy}

import scala.util.{Failure, Success}

object UtxPoolSynchronizer extends ScorexLogging {

  def start(utx: UtxPool,
            settings: UtxSynchronizerSettings,
            allChannels: ChannelGroup,
            txSource: ChannelObservable[Transaction],
            blockSource: Observable[_]): CancelableFuture[Unit] = {
    implicit val scheduler: Scheduler = Scheduler.forkJoin(settings.parallelism, settings.maxThreads, "utx-pool-sync")

    val dummy = new Object()
    val knownTransactions = CacheBuilder
      .newBuilder()
      .maximumSize(settings.networkTxCacheSize)
      .build[ByteStr, Object]

    val blockCacheCleaning = blockSource
      .observeOn(scheduler)
      .foreachL(_ => knownTransactions.invalidateAll())
      .runAsyncLogErr

    val newTxSource = txSource
      .observeOn(scheduler)
      .filter {
        case (_, tx) =>
          var isNew = false
          knownTransactions.get(tx.id(), { () =>
            isNew = true; dummy
          })
          isNew
      }

    val synchronizerFuture = newTxSource
      .whileBusyBuffer(OverflowStrategy.DropOldAndSignal(settings.maxQueueSize, { dropped =>
        log.warn(s"UTX queue overflow: $dropped transactions dropped")
        None
      }))
      .mapParallelUnordered(settings.parallelism) {
        case (sender, transaction) =>
          Task {
            concurrent.blocking(utx.putIfNew(transaction).resultE) match {
              case Right(true) =>
                log.trace(s"Broadcasting ${transaction.id()} to ${allChannels.size()} peers except $sender")
                Some(allChannels.write(transaction, (_: Channel) != sender))
              case _ => None
            }
          }
      }
      .bufferTimedAndCounted(settings.maxBufferTime, settings.maxBufferSize)
      .filter(_.flatten.nonEmpty)
      .foreachL(_ => allChannels.flush())
      .runAsyncLogErr

    synchronizerFuture.onComplete {
      case Success(_)     => log.info("UtxPoolSynschronizer stops")
      case Failure(error) => log.error("Error in utx pool synchronizer", error)
    }

    synchronizerFuture.onComplete(_ => blockCacheCleaning.cancel())
    synchronizerFuture
  }
}
