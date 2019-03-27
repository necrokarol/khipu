package khipu.store

import akka.actor.ActorSystem
import akka.event.LogSource
import akka.event.Logging
import java.io.File
import kesque.HashKeyValueTable
import kesque.Kesque
import kesque.TKeyVal
import kesque.TVal
import khipu.UInt256
import khipu.domain.Account
import khipu.rlp
import khipu.service.ServiceBoard
import khipu.store.datasource.KesqueDataSource
import khipu.trie
import khipu.trie.BranchNode
import khipu.trie.ByteArraySerializable
import khipu.trie.ExtensionNode
import khipu.trie.LeafNode
import khipu.trie.Node
import khipu.util
import org.apache.kafka.common.record.CompressionType
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object KesqueCompactor {
  implicit lazy val system = ActorSystem("khipu")
  import system.dispatcher
  lazy val serviceBoard = ServiceBoard(system)

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  object NodeReader {
    final case class TNode(node: Node, blockNumber: Long)
  }
  class NodeReader[V](topic: String, nodeTable: HashKeyValueTable)(vSerializer: ByteArraySerializable[V]) {
    import NodeReader._
    private val log = Logging(system, this)

    private val start = System.nanoTime
    private var nodeCount = 0
    private var entityCount = 0

    /** override me to define the behavior */
    protected def entityGot(entity: V, blockNumber: Long) {}
    protected def nodeGot(kv: TKeyVal) {}

    private def toEntity(valueBytes: Array[Byte]): V = vSerializer.fromBytes(valueBytes)

    private def processEntity(entity: V, blockNumber: Long) = {
      entityCount += 1
      if (entityCount % 1000 == 0) {
        log.info(s"[comp] got $topic entities $entityCount, at #$blockNumber")
      }

      entityGot(entity, blockNumber)
    }

    def processNode(tnode: TNode) {

      tnode match {
        case TNode(LeafNode(key, value), blockNumber) => processEntity(toEntity(value), blockNumber)
        case TNode(ExtensionNode(shardedKey, next), blockNumber) =>
          next match {
            case Left(nodeId) => getNode(nodeId, blockNumber) map processNode
            case Right(node)  => processNode(TNode(node, blockNumber))
          }
        case TNode(BranchNode(children, terminator), blockNumber) =>
          children.map {
            case Some(Left(nodeId)) => getNode(nodeId, blockNumber) map processNode
            case Some(Right(node))  => processNode(TNode(node, blockNumber))
            case None               =>
          }

          terminator match {
            case Some(value) => processEntity(toEntity(value), blockNumber)
            case None        =>
          }
      }
    }

    def getNode(key: Array[Byte], blockNumber: Long): Option[TNode] = {
      val encodedOpt = if (key.length < 32) {
        Some(key, blockNumber)
      } else {
        nodeTable.read(key, topic, bypassCache = true) match {
          case Some(TVal(bytes, mixedOffset, blockNumber)) =>
            nodeCount += 1
            if (nodeCount % 1000 == 0) {
              val elapsed = (System.nanoTime - start) / 1000000000
              val speed = nodeCount / math.max(1, elapsed)
              log.info(s"[comp] $topic nodes $nodeCount $speed/s, at #$blockNumber")
            }

            nodeGot(TKeyVal(key, bytes, mixedOffset, blockNumber))
            Some(bytes, blockNumber)

          case None =>
            log.warning(s"$topic Node not found ${khipu.toHexString(key)}, trie is inconsistent")
            None
        }
      }

      encodedOpt map {
        case (encoded, blockNumber) => TNode(rlp.decode[Node](encoded)(Node.nodeDec), blockNumber)
      }
    }

  }

  final class NodeWriter(topic: String, nodeTable: HashKeyValueTable, toFileNo: Int) {
    private val buf = new mutable.ArrayBuffer[TKeyVal]()

    private var _maxOffset = Int.MinValue
    def maxOffset = _maxOffset

    def write(kv: TKeyVal) {
      buf += kv
      if (buf.size > 100) { // keep the batched size around 4096 (~ 32*100 bytes)
        val kvs = buf map {
          case TKeyVal(key, value, mixedOffset, timestamp) =>
            val (_, offset) = HashKeyValueTable.toFileNoAndOffset(mixedOffset)
            _maxOffset = math.max(_maxOffset, offset)
            TKeyVal(key, value, offset, timestamp)
        }
        nodeTable.write(kvs, topic, toFileNo)

        buf foreach {
          case TKeyVal(key, _, mixedOffset, _) =>
            nodeTable.removeIndexEntry(key, mixedOffset, topic)
        }

        buf.clear()
      }
    }

    def flush() {
      val kvs = buf map {
        case TKeyVal(key, value, mixedOffset, timestamp) =>
          val (_, offset) = HashKeyValueTable.toFileNoAndOffset(mixedOffset)
          TKeyVal(key, value, offset, timestamp)
      }
      nodeTable.write(kvs, topic, toFileNo)

      buf foreach {
        case TKeyVal(key, _, mixedOffset, _) =>
          nodeTable.removeIndexEntry(key, mixedOffset, topic)
      }

      buf.clear()
    }
  }

  /**
   * Used for testing only
   */
  private def initTablesBySelf() = {
    val khipuPath = new File(classOf[KesqueDataSource].getProtectionDomain.getCodeSource.getLocation.toURI).getParentFile.getParentFile
    //val configDir = new File(khipuPath, "../src/universal/conf")
    val configDir = new File(khipuPath, "conf")
    val configFile = new File(configDir, "kafka.server.properties")
    val kafkaProps = {
      val props = org.apache.kafka.common.utils.Utils.loadProps(configFile.getAbsolutePath)
      props.put("log.dirs", util.Config.kesqueDir)
      props
    }

    val kesque = new Kesque(kafkaProps)
    val futureTables = Future.sequence(List(
      Future(kesque.getTable(Array(KesqueDataSource.account), 4096, CompressionType.NONE, 1024)),
      Future(kesque.getTable(Array(KesqueDataSource.storage), 4096, CompressionType.NONE, 1024)),
      Future(kesque.getTable(Array(KesqueDataSource.evmcode), 24576)),
      Future(kesque.getTimedTable(Array(
        KesqueDataSource.header,
        KesqueDataSource.body,
        KesqueDataSource.receipts,
        KesqueDataSource.td
      ), 102400))
    ))
    val List(accountTable, storageTable, evmcodeTable, blockTable) = Await.result(futureTables, Duration.Inf)

    val blockHeaderDataSource = new KesqueDataSource(blockTable, KesqueDataSource.header)
    val blockHeaderStorage = new BlockHeaderStorage(blockHeaderDataSource)

    (kesque, accountTable, storageTable, blockHeaderStorage)
  }

  // --- simple test
  def main(args: Array[String]) {
    //val (kesque, accountTable, storageTable, blockHeaderStorage) = initTableBySelf()
    val serviceBoard = ServiceBoard(system)
    val storages = serviceBoard.storages
    val kesque = storages.kesque
    val accountTable = storages.accountNodeDataSource.table
    val storageTable = storages.storageNodeDataSource.table
    val blockHeaderStorage = storages.blockHeaderStorage

    val compactor = new KesqueCompactor(kesque, accountTable, storageTable, blockHeaderStorage, 7225555, 0, 1)
    compactor.start()
  }
}
final class KesqueCompactor(
    kesque:             Kesque,
    accountTable:       HashKeyValueTable,
    storageTable:       HashKeyValueTable,
    blockHeaderStorage: BlockHeaderStorage,
    blockNumber:        Long,
    fromFileNo:         Int,
    toFileNo:           Int
) {
  import KesqueCompactor._

  val log = Logging(system, this)

  private val targetStorageTable = kesque.getTable(Array(KesqueDataSource.storage), 4096, CompressionType.NONE, 1024)
  private val targetAccountTable = kesque.getTable(Array(KesqueDataSource.account), 4096, CompressionType.NONE, 1024)

  private val storageWriter = new NodeWriter(KesqueDataSource.storage, targetStorageTable, toFileNo)
  private val accountWriter = new NodeWriter(KesqueDataSource.account, targetAccountTable, toFileNo)

  private val storageReader = new NodeReader[UInt256](KesqueDataSource.storage, storageTable)(trie.rlpUInt256Serializer) {
    override def nodeGot(kv: TKeyVal) {
      val (fileno, _) = HashKeyValueTable.toFileNoAndOffset(kv.offset)
      if (fileno == fromFileNo) {
        storageWriter.write(kv)
      }
    }
  }

  private val accountReader = new NodeReader[Account](KesqueDataSource.account, accountTable)(Account.accountSerializer) {
    override def entityGot(account: Account, blocknumber: Long) {
      // try to extracted storage node hash
      if (account.stateRoot != Account.EmptyStorageRootHash) {
        storageReader.getNode(account.stateRoot.bytes, blocknumber) map storageReader.processNode
      }
    }

    override def nodeGot(kv: TKeyVal) {
      val (fileno, _) = HashKeyValueTable.toFileNoAndOffset(kv.offset)
      if (fileno == fromFileNo) {
        accountWriter.write(kv)
      }
    }
  }

  def start() {
    load()
    postAppend()
    gc()
  }

  private def load() {
    log.info(s"[comp] loading nodes of #$blockNumber")
    for {
      hash <- blockHeaderStorage.getBlockHash(blockNumber)
      header <- blockHeaderStorage.get(hash)
    } {
      val stateRoot = header.stateRoot.bytes
      accountReader.getNode(stateRoot, blockNumber) map accountReader.processNode
    }
    storageWriter.flush()
    accountWriter.flush()
    log.info(s"[comp] all nodes loaded of #$blockNumber")
  }

  /**
   * should stop world during postAppend()
   */
  private def postAppend() {
    log.info(s"[comp] post append storage from offset ${storageWriter.maxOffset + 1} ...")
    val storageTask = new Thread {
      override def run() {
        storageTable.iterateOver(storageWriter.maxOffset + 1, KesqueDataSource.storage) {
          kv => storageWriter.write(kv)
        }
      }
    }

    log.info(s"[comp] post append account from offset ${accountWriter.maxOffset + 1} ...")
    val accountTask = new Thread {
      override def run() {
        accountTable.iterateOver(accountWriter.maxOffset + 1, KesqueDataSource.account) {
          kv => accountWriter.write(kv)
        }
      }
    }

    storageTask.start
    accountTask.start

    storageTask.join
    accountTask.join
    log.info(s"[comp] post append done.")
  }

  /**
   * should stop world during gc()
   */
  private def gc() {
    log.info(s"[comp] gc ...")
    targetStorageTable.removeIndexEntries(KesqueDataSource.storage) {
      case (k, mixedOffset) =>
        val (fileNo, _) = HashKeyValueTable.toFileNoAndOffset(mixedOffset)
        fileNo == fromFileNo
    }

    targetAccountTable.removeIndexEntries(KesqueDataSource.account) {
      case (k, mixedOffset) =>
        val (fileNo, _) = HashKeyValueTable.toFileNoAndOffset(mixedOffset)
        fileNo == fromFileNo
    }
    log.info(s"[comp] gc done.")
  }
}