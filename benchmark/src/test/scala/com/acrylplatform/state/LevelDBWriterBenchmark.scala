package com.acrylplatform.state

import java.io.File
import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

import com.typesafe.config.ConfigFactory
import com.acrylplatform.account._
import com.acrylplatform.common.state.ByteStr
import com.acrylplatform.common.utils.Base58
import com.acrylplatform.database.LevelDBWriter
import com.acrylplatform.db.LevelDBFactory
import com.acrylplatform.settings.{AcrylSettings, loadConfig}
import com.acrylplatform.state.LevelDBWriterBenchmark._
import com.acrylplatform.transaction.Asset
import com.acrylplatform.utils.Implicits.SubjectOps
import monix.reactive.subjects.Subject
import org.iq80.leveldb.{DB, Options}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.io.Codec

/**
  * Tests over real database. How to test:
  * 1. Download a database
  * 2. Import it: https://github.com/acrylplatform/Acryl/wiki/Export-and-import-of-the-blockchain#import-blocks-from-the-binary-file
  * 3. Run ExtractInfo to collect queries for tests
  * 4. Make Caches.MaxSize = 1
  * 5. Run this test
  */
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Threads(1)
@Fork(1)
@Warmup(iterations = 10)
@Measurement(iterations = 100)
class LevelDBWriterBenchmark {
  @Benchmark
  def readFullBlock_test(st: BlocksByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.db.blockById(st.allBlocks.random).get)
  }

  @Benchmark
  def readBlockHeader_test(st: BlocksByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.db.blockHeaderAndSize(st.allBlocks.random).get)
  }

  @Benchmark
  def transactionById_test(st: TransactionByIdSt, bh: Blackhole): Unit = {
    bh.consume(st.db.transactionInfo(st.allTxs.random).get)
  }

  @Benchmark
  def transactionByAddress_test(st: TransactionByAddressSt, bh: Blackhole): Unit = {
    bh.consume(st.db.addressTransactions(st.txsAddresses.random, Set.empty, 1, None).right.get)
  }

}

object LevelDBWriterBenchmark {

  @State(Scope.Benchmark)
  class TransactionByIdSt extends BaseSt {
    val allTxs: Vector[ByteStr] = load("transactionById", benchSettings.restTxsFile)(x => ByteStr(Base58.tryDecodeWithLimit(x).get))
  }

  @State(Scope.Benchmark)
  class TransactionByAddressSt extends BaseSt {
    val txsAddresses: Vector[Address] = load("transactionByAddress", benchSettings.txsAddressesFile)(x => Address.fromString(x).right.get)
  }

  @State(Scope.Benchmark)
  class BlocksByIdSt extends BaseSt {
    val allBlocks: Vector[ByteStr] = load("blocksById", benchSettings.blocksFile)(x => ByteStr(Base58.tryDecodeWithLimit(x).get))
  }

  @State(Scope.Benchmark)
  class BaseSt {
    protected val benchSettings: Settings = Settings.fromConfig(ConfigFactory.load())
    private val acrylSettings: AcrylSettings = {
      val config = loadConfig(ConfigFactory.parseFile(new File(benchSettings.networkConfigFile)))
      AcrylSettings.fromRootConfig(config)
    }

    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = acrylSettings.blockchainSettings.addressSchemeCharacter.toByte
    }

    private val rawDB: DB = {
      val dir = new File(acrylSettings.dbSettings.directory)
      if (!dir.isDirectory) throw new IllegalArgumentException(s"Can't find directory at '${acrylSettings.dbSettings.directory}'")
      LevelDBFactory.factory.open(dir, new Options)
    }

    private val ignoreSpendableBalanceChanged = Subject.empty[(Address, Asset)]

    val db = new LevelDBWriter(rawDB, ignoreSpendableBalanceChanged, acrylSettings.blockchainSettings.functionalitySettings, acrylSettings.dbSettings)

    @TearDown
    def close(): Unit = {
      rawDB.close()
    }

    protected def load[T](label: String, absolutePath: String)(f: String => T): Vector[T] = {
      scala.io.Source
        .fromFile(absolutePath)(Codec.UTF8)
        .getLines()
        .map(f)
        .toVector
    }
  }

  implicit class VectorOps[T](self: Vector[T]) {
    def random: T = self(ThreadLocalRandom.current().nextInt(self.size))
  }
}
