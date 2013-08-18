package tj.fs

import java.io.{FilePermission, IOException, OutputStream}
import org.apache.hadoop.fs.Path
import org.apache.cassandra.utils.UUIDGen
import java.util.UUID
import java.nio.ByteBuffer
import tj.model.{SubBlockMeta, BlockMeta, FileType, INode}
import org.apache.hadoop.fs.permission.FsPermission
import scala.concurrent.Await
import scala.concurrent.duration._

case class FileSystemOutputStream(store: FileSystemStore, path: Path,
                                  blockSize: Long, subBlockSize: Long,
                                  bufferSize: Long) extends OutputStream {

  private var isClosed: Boolean = false

  private var blockId: UUID = UUIDGen.getTimeUUID

  private var subBlockOffset = 0
  private var blockOffset = 0
  private var position = 0
  private var outBuffer = ByteBuffer.allocate(subBlockSize.asInstanceOf[Int])

  private var subBlocksMeta = List[SubBlockMeta]()
  private var blocksMeta = List[BlockMeta]()

  private var isClosing = false

  private var bytesWrittenToBlock = 0

  def write(p1: Int) = {
    if (isClosed) {
      throw new IOException("Stream closed")
    }

  }

  override def write(buf: Array[Byte], offset: Int, length: Int) = {
    if (isClosed) {
      throw new IOException("Stream closed")
    }
    var lengthTemp = length
    var offsetTemp = offset
    while (lengthTemp > 0) {
      val lengthToWrite = math.min(subBlockSize - position, lengthTemp).asInstanceOf[Int]
      outBuffer = ByteBuffer.wrap(buf, offsetTemp, lengthToWrite)
      lengthTemp -= lengthToWrite
      offsetTemp += lengthToWrite
      position += lengthToWrite
      if (position == subBlockSize) {
        flush
      }
    }
  }

  private def endSubBlock = {
    val subBlockMeta = SubBlockMeta(UUIDGen.getTimeUUID, subBlockOffset, position)
    Await.ready(store.storeSubBlock(blockId, subBlockMeta, outBuffer), 10 seconds)
    subBlockOffset += position
    bytesWrittenToBlock += position
    subBlocksMeta = subBlocksMeta :+ subBlockMeta
    position = 0
  }

  private def endBlock = {
    val subBlockLengths = subBlocksMeta.map(_.length).sum
    val block = BlockMeta(blockId, blockOffset, subBlockLengths, subBlocksMeta)
    blocksMeta = blocksMeta :+ block
    val user = System.getProperty("user.name")
    val permissions = FsPermission.getDefault
    val timestamp = System.currentTimeMillis()
    val iNode = INode(user, user, permissions, FileType.FILE, blocksMeta, timestamp)
    Await.ready(store.storeINode(path, iNode), 10 seconds)
    blockOffset += subBlockLengths.asInstanceOf[Int]
    subBlocksMeta = List()
    subBlockOffset = 0
    blockId = UUIDGen.getTimeUUID
    bytesWrittenToBlock = 0
  }

  override def flush = {
    if (isClosed) {
      throw new IOException("Stream closed")
    }
    endSubBlock
    if (bytesWrittenToBlock >= blockSize || isClosing) {
      endBlock
    }
  }

  override def close = {
    if (!isClosed) {
      isClosing = true
      flush
      super.close
      isClosed = true
    }
  }
}
