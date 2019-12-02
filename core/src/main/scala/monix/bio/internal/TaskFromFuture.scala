package monix.bio.internal

import monix.bio.WRYYY.Context
import monix.execution._
import monix.bio.{Task, WRYYY}
import monix.execution.cancelables.SingleAssignCancelable

import scala.util.control.NonFatal
import monix.execution.schedulers.TrampolinedRunnable

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

private[bio] object TaskFromFuture {
  /** Implementation for `Task.fromFuture`. */
  def strict[A](f: Future[A]): Task[A] = {
    f.value match {
      case None =>
        f match {
          // Do we have a CancelableFuture?
          case cf: CancelableFuture[A] @unchecked =>
            // Cancelable future, needs canceling
            rawAsync(startCancelable(_, _, cf, cf.cancelable))
          case _ =>
            // Simple future, convert directly
            rawAsync(startSimple(_, _, f))
        }
      case Some(value) =>
        Task.fromTry(value)
    }
  }

  /** Implementation for `Task.deferFutureAction`. */
  def deferAction[A](f: Scheduler => Future[A]): Task[A] =
    rawAsync[A] { (ctx, cb) =>
      implicit val sc = ctx.scheduler
      // Prevents violations of the Callback contract
      var streamErrors = true
      try {
        val future = f(sc)
        streamErrors = false

        future.value match {
          case Some(value) =>
            cb(value)
          case None =>
            future match {
              case cf: CancelableFuture[A] @unchecked =>
                startCancelable(ctx, cb, cf, cf.cancelable)
              case _ =>
                startSimple(ctx, cb, future)
            }
        }
      } catch {
        case ex if NonFatal(ex) =>
          if (streamErrors) cb.onError(ex)
          else sc.reportFailure(ex)
      }
    }

  def fromCancelablePromise[A](p: CancelablePromise[A]): Task[A] = {
    val start: Start[Throwable, A] = (ctx, cb) => {
      implicit val ec = ctx.scheduler
      if (p.isCompleted) {
        p.subscribe(trampolinedCB(cb, null))
      } else {
        val conn = ctx.connection
        val ref = SingleAssignCancelable()
        conn.push(ref)
        ref := p.subscribe(trampolinedCB(cb, conn))
      }
    }

    WRYYY.Async(
      start,
      trampolineBefore = false,
      trampolineAfter = false,
      restoreLocals = true
    )
  }

  private def rawAsync[A](start: (Context[Throwable], Callback[Throwable, A]) => Unit): Task[A] =
    WRYYY.Async(
      start,
      trampolineBefore = true,
      trampolineAfter = false,
      restoreLocals = true
    )

  private def startSimple[A](ctx: WRYYY.Context[Throwable], cb: Callback[Throwable, A], f: Future[A]) = {

    f.value match {
      case Some(value) =>
        cb(value)
      case None =>
        f.onComplete { result =>
          cb(result)
        }(ctx.scheduler)
    }
  }

  private def startCancelable[A](ctx: WRYYY.Context[Throwable], cb: Callback[Throwable, A], f: Future[A], c: Cancelable): Unit = {

    f.value match {
      case Some(value) =>
        cb(value)
      case None =>
        // Given a cancelable future, we should use it
        val conn = ctx.connection
        conn.push(c)(ctx.scheduler)
        // Async boundary
        f.onComplete { result =>
          conn.pop()
          cb(result)
        }(ctx.scheduler)
    }
  }

  private def trampolinedCB[A](cb: Callback[Throwable, A], conn: TaskConnection[Throwable])(
    implicit ec: ExecutionContext): Try[A] => Unit = {

    new (Try[A] => Unit) with TrampolinedRunnable {
      private[this] var value: Try[A] = _

      def apply(value: Try[A]): Unit = {
        this.value = value
        ec.execute(this)
      }

      def run(): Unit = {
        if (conn ne null) conn.pop()
        val v = value
        value = null
        cb(v)
      }
    }
  }
}
