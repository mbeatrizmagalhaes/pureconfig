package pureconfig.module

import cats.effect.{ IO, Timer, ContextShift }
import _root_.fs2.{ Stream, text }
import pureconfig.generic.auto._
import pureconfig.module.{ fs2 => testee }
import org.scalatest.{ FlatSpec, Matchers }
import pureconfig.error.ConfigReaderException
import org.scalatest.EitherValues._
import scala.concurrent.duration._
import cats.implicits._

import scala.concurrent.ExecutionContext

class fs2Suite extends FlatSpec with Matchers {

  val blockingEc = ExecutionContext.global

  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private def delayEachLine(stream: Stream[IO, String], delay: FiniteDuration) = {
    val byLine = stream.through(text.lines)
    Stream.fixedDelay[IO](delay).zipWith(byLine)((_, line) => line).intersperse("\n")
  }

  case class SomeCaseClass(somefield: Int, anotherfield: String)

  "streamConfig" should "load a case class from a byte stream" in {

    val someConfig = "somefield=1234\nanotherfield=some string"
    val configBytes = Stream.emit(someConfig).through(text.utf8Encode)

    val myConfig = testee.streamConfig[IO, SomeCaseClass](configBytes, blockingEc)

    myConfig.unsafeRunSync() shouldBe SomeCaseClass(1234, "some string")
  }

  it should "error when stream is blank" in {
    val blankStream = Stream.empty.covaryAll[IO, Byte]

    val configLoad = testee.streamConfig[IO, SomeCaseClass](blankStream, blockingEc)

    configLoad.attempt.unsafeRunSync().left.value shouldBe a[ConfigReaderException[_]]

  }

  it should "load a case class from a stream with delays" in {

    val someConfig = "somefield=1234\nanotherfield=some string"
    val configStream = Stream.emit(someConfig)
    val configBytes = delayEachLine(configStream, 200.milliseconds).through(text.utf8Encode)

    val myConfig = testee.streamConfig[IO, SomeCaseClass](configBytes, blockingEc)

    myConfig.unsafeRunSync() shouldBe SomeCaseClass(1234, "some string")
  }

  "saveConfigToStream" should "produce HOCON for given input" in {
    val someConfig = SomeCaseClass(1234, "some string")
    val configStream: Stream[IO, Byte] = testee.saveConfigToStream(someConfig)
    val result = configStream.through(text.utf8Decode).compile.foldMonoid.unsafeRunSync()

    result should (include("somefield") and include("1234") and include("anotherfield") and include("some string"))
  }
}
