package scan

import java.nio.file._

import scala.compat.java8.StreamConverters._
import scala.collection.SortedSet

import cats._
import cats.data._
import cats.implicits._

import mouse.all._

import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._

import org.atnos.eff.addon.monix._
import org.atnos.eff.addon.monix.task._
import org.atnos.eff.syntax.addon.monix.task._

import monix.eval._
import monix.execution._

import EffTypes._

import scala.concurrent.duration._


object Scanner {
  val Usage = "Scanner <path> [number of largest files to track]"

  type R = Fx.fx4[Task, FilesystemCmd, Either[String, ?], Writer[Log, ?]]

  implicit val s = Scheduler(ExecutionModel.BatchedExecution(32))

  def main(args: Array[String]): Unit = {
    val program = scanReport[R](args).map(println)

    program.runFilesystemCmds(DefaultFilesystem).runEither.runWriterUnsafe[Log]{
      case Error(msg) => System.err.println(msg)
      case Info(msg) => System.out.println(msg)
      case _ => ()
    }.runAsync.runSyncUnsafe(1.minute)
  }

  def scanReport[R: _task: _filesystem: _err: _log](args: Array[String]): Eff[R, String] = for {
    base <- optionEither(args.lift(0), s"Path to scan must be specified.\n$Usage")

    topN <- {
      val n = args.lift(1).getOrElse("10")
      fromEither(n.parseInt.leftMap(_ => s"Number of files must be numeric: $n"))
    }

    topNValid <- if (topN < 0) left[R, String, Int](s"Invalid number of files $topN") else topN.pureEff[R]

    start <- taskDelay(System.currentTimeMillis())

    base <- FilesystemCmd.filePath(base)

    scan <- pathScan[Fx.prepend[Reader[ScanConfig, ?], R]](base).runReader[ScanConfig](ScanConfig(topNValid))

    finish <- taskDelay(System.currentTimeMillis())

    _ <- tell(Log.info(s"Scan of $base completed in ${finish - start}ms"))

  } yield ReportFormat.largeFilesReport(scan, base.toString)

  def pathScan[R: _task: _filesystem: _config: _log](path: FilePath): Eff[R, PathScan] = path match {

    case f: File =>
      for {
        fs <- FileSize.ofFile(f)
        _ <- tell(Log.debug(s"File ${fs.file.path} Size ${ReportFormat.formatByteString(fs.size)}"))
      } yield PathScan(SortedSet(fs), fs.size, 1)

    case dir: Directory =>
      for {
        topN <- takeTopN
        fileList <- FilesystemCmd.listFiles(dir)
        childScans <- fileList.traverse(pathScan[R](_))
        _ <- {
          val dirCount = fileList.count(_.isInstanceOf[Directory])
          val fileCount = fileList.count(_.isInstanceOf[File])
          tell(Log.debug(s"Scanning directory '$dir': $dirCount subdirectories and $fileCount files"))
        }
      } yield childScans.combineAll(topN)

    case Other(_) =>
      PathScan.empty.pureEff[R]
  }


  def takeTopN[R: _config]: Eff[R, Monoid[PathScan]] = for {
    scanConfig <- ask
  } yield new Monoid[PathScan] {
    def empty: PathScan = PathScan.empty

    def combine(p1: PathScan, p2: PathScan): PathScan = PathScan(
      p1.largestFiles.union(p2.largestFiles).take(scanConfig.topN),
      p1.totalSize + p2.totalSize,
      p1.totalCount + p2.totalCount
    )
  }

}

sealed trait FilesystemCmd[+A]

object FilesystemCmd {

  implicit class EffFilesystemCmdOps[R, A](e: Eff[R, A]) {

    def runFilesystemCmds[U](fs: Filesystem)(implicit m: Member.Aux[FilesystemCmd, R, U]): Eff[U, A] = fs.runFilesystemCmds(e)
  }

  def filePath[R: _filesystem](path: String): Eff[R, FilePath] = Eff.send[FilesystemCmd, R, FilePath](MkFilePath(path))

  def length[R: _filesystem](file: File): Eff[R, Long] = Eff.send[FilesystemCmd, R, Long](Length(file))

  def listFiles[R: _filesystem](directory: Directory): Eff[R, List[FilePath]] = Eff.send[FilesystemCmd, R, List[FilePath]](ListFiles(directory))

}

case class MkFilePath(path: String) extends FilesystemCmd[FilePath]
case class Length(file: File) extends FilesystemCmd[Long]
case class ListFiles(directory: Directory) extends FilesystemCmd[List[FilePath]]

trait Filesystem {

  def runFilesystemCmds[R, A, U](effects: Eff[R, A])(implicit m: Member.Aux[FilesystemCmd, R, U]): Eff[U, A] = {

    val sideEffect = new SideEffect[FilesystemCmd] {
      def apply[X](fsc: FilesystemCmd[X]): X = (fsc match {
        case MkFilePath(path) => filePath(path)
        case Length(file) => length(file)
        case ListFiles(directory) => listFiles(directory)
      }).asInstanceOf[X]

      def applicative[X, Tr[_] : Traverse](ms: Tr[FilesystemCmd[X]]): Tr[X] =
        ms.map(apply)
    }
    Interpret.interpretUnsafe(effects)(sideEffect)(m)
  }

  protected def filePath(path: String): FilePath

  protected def length(file: File): Long

  protected def listFiles(directory: Directory): List[FilePath]

}
object DefaultFilesystem extends Filesystem {

  protected def filePath(path: String): FilePath =
    if (Files.isRegularFile(Paths.get(path)))
      File(path.toString)
    else if (Files.isDirectory(Paths.get(path)))
      Directory(path)
    else
      Other(path)

  protected def length(file: File): Long = Files.size(Paths.get(file.path))

  protected def listFiles(directory: Directory) = {
    val files = Files.list(Paths.get(directory.path))
    try files.toScala[List].flatMap(path => filePath(path.toString) match {
      case Directory(path) => List(Directory(path))
      case File(path) => List(File(path))
      case Other(path) => List.empty
    })
    finally files.close()
  }

}

case class ScanConfig(topN: Int)

case class PathScan(largestFiles: SortedSet[FileSize], totalSize: Long, totalCount: Long)

object PathScan {

  def empty = PathScan(SortedSet.empty, 0, 0)

  def topNMonoid(n: Int): Monoid[PathScan] = new Monoid[PathScan] {
    def empty: PathScan = PathScan.empty

    def combine(p1: PathScan, p2: PathScan): PathScan = PathScan(
      p1.largestFiles.union(p2.largestFiles).take(n),
      p1.totalSize + p2.totalSize,
      p1.totalCount + p2.totalCount
    )
  }

}

case class FileSize(file: File, size: Long)

object FileSize {

  def ofFile[R: _filesystem](file: File): Eff[R, FileSize] = FilesystemCmd.length(file).map(FileSize(file, _))

  implicit val ordering: Ordering[FileSize] = Ordering.by[FileSize, Long](_.size).reverse

}

object EffTypes {

  type _filesystem[R] = FilesystemCmd |= R
  type _config[R] = Reader[ScanConfig, ?] <= R
  type _err[R] = Either[String, ?] <= R
  type _log[R] = Writer[Log, ?] <= R
}

sealed trait Log {def msg: String}
object Log {
  def error: String => Log = Error
  def info: String => Log = Info
  def debug: String => Log = Debug
}
case class Error(msg: String) extends Log
case class Info(msg: String) extends Log
case class Debug(msg: String) extends Log

//I prefer an closed set of disjoint cases over a series of isX(): Boolean tests, as provided by the Java API
//The problem with boolean test methods is they make it unclear what the complete set of possible states is, and which tests
//can overlap
sealed trait FilePath {
  def path: String
}

case class File(path: String) extends FilePath
case class Directory(path: String) extends FilePath
case class Other(path: String) extends FilePath

//Common pure code that is unaffected by the migration to Eff
object ReportFormat {

  def largeFilesReport(scan: PathScan, rootDir: String): String = {
    if (scan.largestFiles.nonEmpty) {
      s"Largest ${scan.largestFiles.size} file(s) found under path: $rootDir\n" +
        scan.largestFiles.map(fs => s"${(fs.size * 100)/scan.totalSize}%  ${formatByteString(fs.size)}  ${fs.file}").mkString("", "\n", "\n") +
        s"${scan.totalCount} total files found, having total size ${formatByteString(scan.totalSize)} bytes.\n"
    }
    else
      s"No files found under path: $rootDir"
  }

  def formatByteString(bytes: Long): String = {
    if (bytes < 1000)
      s"${bytes} B"
    else {
      val exp = (Math.log(bytes) / Math.log(1000)).toInt
      val pre = "KMGTPE".charAt(exp - 1)
      s"%.1f ${pre}B".format(bytes / Math.pow(1000, exp))
    }
  }
}
