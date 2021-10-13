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

/** Attempts to logically link the two "shores" of an unsafe boundary so that
  * algebraic information can be retrieved and that cancellation propagates.
  */
trait DispatchBridge[F[_]] {

  def absorb[A](future: F[Future[A]]): F[A]

  def unsafeToFuture[A](io: F[A]): Future[A]

}

//
// TODO : add some inward cancellation propagation mechanism.
//
object DispatchBridge {

  // Outcome of a dispatch call. Either a failed algebraic computation,
  // or successful "summary" algebraic computation.
  type SingleOutcome[F[_]] = Either[F[AnyRef], F[Unit]]
  type OutcomePromise[F[_]] = Deferred[F, SingleOutcome[F]]

  // The very annoying part is that we cannot easily tie locally the
  // two sides of the soviet interop (without passing values around that is, which may
  // be .
  // So this POC uses a 1-element queue (could be a semaphore) to ensure that
  // the outer call and inner call are tied together. The problem is that it crumbles as soon
  // as the soviet interop needs to call `unsafeRun` more than once on its resource ...
  //
  // In think it's a sign that this problem simply cannot have a generic solution : without the
  // possibity to thread some sort of correlation value through the soviet-interface
  // (which we don't control), there is no way of tying both shores together. We can't even rely
  // on ThreadLocal mutable data structures because the soviet-interop might use a threadpool
  // under the hood without granting us any control over it...
  //
  // Oh well, I guess we're stuck in a state of knowing that people are gonna shoot themselves in
  // the foot, or constantly ask where Dispatcher should be instantiated.
  def apply[F[_]: Async]: Resource[F, DispatchBridge[F]] =
    (Queue.bounded[F, OutcomePromise[F]](1).toResource, Dispatcher[F]).mapN {
      (queue, dispatcher) =>
        new DispatchBridge[F] {
          val F = Async[F]

          def absorb[A](future: F[Future[A]]): F[A] =
            F.deferred[SingleOutcome[F]].flatTap(queue.offer).flatMap {
              promise =>
                F.fromFuture(future).background.use { backgroundOutcome =>
                  promise.get.flatMap {
                    case Left(fAnyRef) => fAnyRef.asInstanceOf[F[A]]
                    case Right(summary) =>
                      summary *> backgroundOutcome
                        .flatMap(_.embed(F.canceled.asInstanceOf[F[A]]))
                  }
                }
            }

          def unsafeToFuture[A](io: F[A]): Future[A] =
            dispatcher.unsafeToFuture {
              queue.take.flatMap { promise =>
                // Resorting to mutation to extract the "happy path" value from the monadic context,
                // as inspecting the Succeeded outcome using dispatcher is risky on algebraic sums,
                // such as OptionT, EitherT, ...
                var awaitedValue: Option[AnyRef] = None
                F.uncancelable { poll =>
                  poll(io)
                    .flatTap(r =>
                      F.delay { awaitedValue = Some(r.asInstanceOf[AnyRef]) }
                    )
                    .start
                    .flatMap(fiber => poll(fiber.join).onCancel(fiber.cancel))
                }.flatTap {
                  case Canceled() =>
                    promise
                      .complete(Left(F.canceled.asInstanceOf[F[AnyRef]]))
                      .void
                  case Errored(e) =>
                    promise.complete(Left(F.raiseError(e))).void
                  case Succeeded(awaitOutcome) =>
                    awaitedValue match {
                      case Some(v) =>
                        promise.complete(Right(awaitOutcome.void)).void
                      case None =>
                        promise
                          .complete(
                            Left(awaitOutcome.asInstanceOf[F[AnyRef]])
                          )
                          .void
                    }
                }.flatMap(_.embedNever)
              }
            }
        }
    }

}

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

  def make[F[_]: Async](bridge: DispatchBridge[F])(f: Int => F[Int]): Soviet =
    new Soviet(int => bridge.unsafeToFuture(f(int)))

}

/**
 * Represents what the users would like to have as an interface.
 */
trait Modern[F[_]] {

  def call(int: Int): F[Int]

}

object Modern {

  def make[F[_]: Sync](bridge: DispatchBridge[F], soviet: Soviet): Modern[F] =
    new Modern[F] {
      def call(int: Int): F[Int] =
        bridge.absorb(Sync[F].delay(soviet.call(int)))
    }

}

object Main extends IOApp.Simple {

  type MIO[A] = OptionT[IO, A]

  def run: IO[Unit] = DispatchBridge[MIO]
    .map { bridge =>
      val soviet = Soviet.make[MIO](bridge)(int =>
        if (int % 2 == 0) OptionT.none
        else OptionT.some(int)
      )
      val modern = Modern.make[MIO](bridge, soviet)
      modern
    }
    .use { modern =>
      modern.call(2)
    }
    .value
    .flatMap(IO.println)

}
