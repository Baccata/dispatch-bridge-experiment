package foo

import scala.concurrent.Future
import cats.effect.std.Dispatcher
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import cats.effect.instances.all._
import cats.effect.syntax.all._
import cats.effect.kernel.Outcome._
import cats.effect.IOApp
import cats.effect.IOLocal
import cats.effect.std.Queue
import cats.effect.kernel.Deferred
import java.util.concurrent.CancellationException
import cats.data.OptionT
import cats.effect.kernel.Sync
import cats.kernel.Hash
import cats.effect.kernel.Ref
import cats.effect.kernel.Outcome

//*******************************************************************************************
// TEST
//******************************************************************************

/** Represents some type that exposes a soviet-interop interface that requires
  * some Future... like graphql-sangria for instance.
  */
class Soviet(f: Int => Future[Int]) {

  def call(int: Int): Future[Int] = f(int)

}

object Soviet {

  def make[F[_]: Async](bridge: DispatchBridge[F, Int])(
      f: Int => F[Int]
  ): Soviet = {
    val unlifted = bridge.makeImpure(f)
    new Soviet(unlifted)
  }

}

/** Represents what the users would like to have as an interface.
  */
trait Modern[F[_]] {

  def call(int: Int): F[Int]

}

object Modern {

  def make[F[_]: Sync](
      bridge: DispatchBridge[F, Int],
      soviet: Soviet
  ): Modern[F] =
    new Modern[F] {
      val callSafe = bridge.makePure(soviet.call)

      def call(int: Int): F[Int] =
        callSafe(int)
    }

}

object Main extends IOApp.Simple {

  type MIO[A] = OptionT[IO, A]

  def run: IO[Unit] = DispatchBridge[MIO, Int]
    .map { bridge =>
      val soviet = Soviet.make[MIO](bridge)(int =>
        if (int % 1 == 0) OptionT.none
        else OptionT.some(int)
      )
      val modern = Modern.make[MIO](bridge, soviet)
      modern
    }
    .use { modern =>
      modern.call(1)
    }
    .value
    .flatMap(IO.println)

}
