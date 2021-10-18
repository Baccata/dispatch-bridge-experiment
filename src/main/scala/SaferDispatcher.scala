package foo

import scala.concurrent.Future
import cats.effect.std.Dispatcher
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import cats.effect.syntax.all._
import cats.effect.kernel.Outcome._

// No onError because these are communicated naturally via exceptions
trait DispatchObserver[F[_]] {

  def onCancel: F[Unit]

  // When the computation returns an algebraic failure and a value
  // cannot be produced (for instance OptionT.none)
  def onAlgebraicFailure(failed: F[Unit]): F[Unit]

  // When the computation returns an algebraic success.
  def onAlgebraicSuccess(success: F[Unit]): F[Unit]

}

trait SaferDispatcher[F[_]] {

  def unsafeToFuture[A](observer: DispatchObserver[F])(fa: F[A]): Future[A]
  // ... etc
}

object SaferDispatcher {

  def apply[F[_]: Async]: Resource[F, SaferDispatcher[F]] =
    Dispatcher[F].map(dispatcher =>
      new SaferDispatcher[F] {
        val F = Async[F]

        def unsafeToFuture[A](
            observer: DispatchObserver[F]
        )(fa: F[A]): Future[A] = dispatcher.unsafeToFuture {
          var awaitedValue: Option[AnyRef] = None
          F.uncancelable { poll =>
            poll(fa)
              .flatTap { a =>
                F.delay { awaitedValue = Some(a.asInstanceOf[AnyRef]) }.as(a)
              }
              .start
              .flatMap(fiber => poll(fiber.join).onCancel(fiber.cancel))
          }.flatTap {
            case Canceled() =>
              observer.onCancel
            case Errored(e) =>
              F.unit
            case Succeeded(algebraicResult) =>
              awaitedValue match {
                case Some(v) =>
                  // Success
                  observer.onAlgebraicSuccess(algebraicResult.void)
                case None =>
                  observer.onAlgebraicFailure(algebraicResult.void)
              }
          }.flatMap(_.embedNever)
        }
      }
    )

}
