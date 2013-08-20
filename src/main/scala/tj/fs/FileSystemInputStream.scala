package tj.fs

import org.apache.hadoop.fs.{Path, FSInputStream}
import java.io.{IOException, InputStream}
import scala.concurrent.Await
import scala.concurrent.duration._

case class FileSystemInputStream(store: FileSystemStore, path: Path) extends FSInputStream {

  private val iNode = Await.result(store.retrieveINode(path), 10 seconds)
  private val fileLength: Long = iNode.blocks.map(_.length).sum

  private var currentPosition: Long = 0L

  private var blockStream: InputStream = null

  private var currentBlockSize: Long = -1

  private var isClosed: Boolean = false

  def seek(target: Long) = {
    if (target > fileLength) {
      throw new IOException("Cannot seek after EOF")
    }
    currentPosition = target
    currentBlockSize = -1
  }

  def getPos: Long = currentPosition

  def seekToNewSource(targetPos: Long): Boolean = false

  private def findBlock(targetPosition: Long): InputStream = {
    val blockIndex = iNode.blocks.indexWhere(b => b.offset + b.length > targetPosition)
    if (blockIndex == -1) {
      throw new IOException("Impossible situation: could not find position " + targetPosition)
    }
    val block = iNode.blocks(blockIndex)
    currentPosition = targetPosition
    currentBlockSize = block.length
    val offset = targetPosition - block.offset
    val bis = store.retrieveBlock(block)
    bis.skip(offset)
    bis
  }

  def read(): Int = {
    if (isClosed) {
      throw new IOException("Stream closed")
    }
    var result: Int = -1

    if (currentPosition < fileLength) {
      if (currentPosition > currentBlockSize) {
        if (blockStream != null) {
          blockStream.close()
        }
        blockStream = findBlock(currentPosition)
      }
      result = blockStream.read
      if (result >= 0) {
        currentPosition += 1
      }
    }
    result
  }

  override def available: Int = (fileLength - currentPosition).asInstanceOf[Int]

  override def read(buf: Array[Byte], off: Int, len: Int): Int = {
    if (isClosed) {
      throw new IOException("Stream closed")
    }
    if (buf == null) {
      throw new NullPointerException
    }
    if ((off < 0) || (len < 0) || (len > buf.length - off)) {
      throw new IndexOutOfBoundsException
    }

    var result: Int = 0
    if (len > 0) {
      while (result < len && currentPosition <= fileLength - 1) {
        if (currentPosition > currentBlockSize - 1) {
          if (blockStream != null) {
            blockStream.close()
          }
          blockStream = findBlock(currentPosition)
        }
        val realLen: Int = math.min(len - result, currentBlockSize + 1).asInstanceOf[Int]
        var readSize = blockStream.read(buf, off + result, realLen)
        result += readSize
        currentPosition += readSize
      }
      if (result == 0) {
        result = -1
      }
    }
    result
  }

  override def close() = {
    if (!isClosed) {
      if (blockStream != null) {
        blockStream.close()
      }
      super.close()
      isClosed = true
    }
  }
}
