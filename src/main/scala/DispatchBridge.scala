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

/** Logically links the two "shores" of an unsafe boundary, via a correlation
  * key, so that algebraic information can be retrieved and that cancellation
  * propagates both ways.
  *
  * The user is expected to open a closure via the `makePure` method, which
  * instantiates some atomic state against a given key under the hood. There can
  * be only one running closure per key at any time.
  *
  * All calls to functions unlifted by the `makeImpure` methods result in a
  * lookup to the state associated to the key. Cancelation happening on both
  * sides propagate accordingly, and potentially dispatched algebraic effects
  * (monad transformers) are kept track of in a "first in, first out" kind of
  * way.
  *
  * If the closure completes successfully (whether via a success or not), the
  * algebraic effects are reconstructed on the safe side.
  *
  * If any computation is canceled (on the inner side or the outer side), all
  * computations tied to the same key get canceled.
  *
  * If a function unlifted via "makeImpure" is called without a closure having
  * been opened, that computation is simply canceled.
  *
  * If a function lifted via "makePure" is called whilst there is already a
  * closure running against the same key, the call fails with an
  * "AlreadyRunningClosure" exception.
  */
trait DispatchBridge[F[_], Key] {

  def makePure[A](f: Key => Future[A]): Key => F[A]
  def makeImpure[A](f: Key => F[A]): Key => Future[A]

}

object DispatchBridge {

  def apply[F[_]: Async, Key: Hash]: Resource[F, DispatchBridge[F, Key]] =
    (Async[F].ref(Map.empty[Key, Closure[F]]).toResource, SaferDispatcher[F])
      .mapN {
        new DispatchBridgeImpl(_, _)
      }

  final case class Closure[F[_]](
      summary: Ref[F, F[Unit]],
      unhappySignal: Deferred[F, F[Unit]],
      cancelToken: F[Unit]
  )

  type StateRef[F[_], Key] = Ref[F, Map[Key, Closure[F]]]

  final case class AlreadyRunningClosure[Key](key: Key) extends Throwable {
    override def getMessage(): String =
      "A closure associated to Key is already running"
  }

}

import DispatchBridge._
class DispatchBridgeImpl[F[_]: Async, Key: Hash](
    stateRef: StateRef[F, Key],
    dispatcher: SaferDispatcher[F]
) extends DispatchBridge[F, Key] {

  private val F = Async[F]

  def makePure[A](f: Key => Future[A]): Key => F[A] = { (key: Key) =>
    val fut = f(key)
    closure[A](key, F.fromFuture(F.delay(fut)))
  }

  def makeImpure[A](f: Key => F[A]): Key => Future[A] = { (key: Key) =>
    dispatchSupervised(key, f(key))
  }

  private def closure[A](key: Key, lifted: F[A]): F[A] =
    (F.ref(F.unit), F.deferred[F[Unit]], F.deferred[Unit]).tupled.flatMap {
      (summary, unhappy, cancelPromise) =>
        stateRef
          .modify[F[A]] { map =>
            map.get(key) match {
              case Some(_) =>
                val error = F.raiseError[A](AlreadyRunningClosure(key))
                (map, error)
              case None =>
                val closure = Closure(summary, unhappy, cancelPromise.get)
                val compute = lifted
                  .race(unhappy.get)
                  .flatMap {
                    case Left(happy) => summary.get.as(happy)
                    case Right(unhappy) =>
                      summary.get *> unhappy.asInstanceOf[F[A]]
                  }
                  .guarantee(stateRef.update(_ - key))
                (map + (key -> closure), compute)
            }
          }
          .flatten
          .guaranteeCase {
            case Outcome.Canceled() => cancelPromise.complete(()).void
            case _                  => F.unit
          }
    }

  private def dispatchSupervised[A](key: Key, io: F[A]): Future[A] = {
    val getClosure = stateRef.get.map(_.get(key)).flatMap {
      case Some(a) => F.pure(a)
      case None    =>
        // add some pluggable behaviour for when closure can't be found
        F.canceled.productR(F.never)
    }

    val observer = new DispatchObserver[F] {
      def onCancel: F[Unit] =
        getClosure.flatMap(_.unhappySignal.complete(F.canceled).void)

      def onAlgebraicFailure(failed: F[Unit]): F[Unit] =
        getClosure.flatMap(_.unhappySignal.complete(failed).void)

      def onAlgebraicSuccess(success: F[Unit]): F[Unit] =
        getClosure.flatMap(_.summary.update(_ *> success))
    }

    dispatcher.unsafeToFuture(observer) {
      // Checking that the closure exists before starting the computation
      getClosure.flatMap { closure =>
        io.race(closure.cancelToken).flatMap {
          case Left(value) => F.pure(value)
          case Right(_)    => F.canceled >> F.never
        }
      }
    }
  }

}
