package fs2

import fs2.internal.{Actor,Future,LinkedMap}
import fs2.util.{Async,Attempt,Effect,NonFatal}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}

import scala.concurrent.duration._

/**
 * Trampolined computation producing an `A` that may
 * include asynchronous steps. Arbitrary monadic expressions involving
 * `map` and `flatMap` are guaranteed to use constant stack space.
 * In addition, using `Task.async`, one may construct a `Task` from
 * callback-based APIs. This makes `Task` useful as a concurrency primitive
 * and as a control structure for wrapping callback-based APIs with a more
 * straightforward, monadic API.
 *
 * Task is also exception-safe. Any exceptions raised during processing may
 * be accessed via the `attempt` method, which converts a `Task[A]` to a
 * `Task[Attempt[A]]`.
 *
 * Unlike `scala.concurrent.Future`, `map` and `flatMap` do NOT spawn new
 * tasks and do not require an implicit `ExecutionContext`. Instead, `map`
 * and `flatMap` merely add to the current (trampolined) continuation that
 * will be run by the 'current' thread, unless explicitly forked via `Task.start`.
 *
 * `Task` also differs from `scala.concurrent.Future` in that it
 * does not represent a _running_ computation. Instead, we
 * reintroduce concurrency _explicitly_ using the `Task.start` function.
 * This simplifies our implementation and makes code easier to reason about,
 * since the order of effects and the points of allowed concurrency are made
 * fully explicit and do not depend on Scala's evaluation order.
 */
final class Task[+A](private[fs2] val get: Future[Attempt[A]]) {
  import Task.Callback

  def flatMap[B](f: A => Task[B]): Task[B] =
    new Task(get flatMap {
      case Left(e) => Future.now(Left(e))
      case Right(a) => Attempt(f(a)) match {
        case Left(e) => Future.now(Left(e))
        case Right(task) => task.get
      }
    })

  def map[B](f: A => B): Task[B] =
    new Task(get map { _.flatMap { a => Attempt(f(a)) } })

  /** 'Catches' exceptions in the given task and returns them as values. */
  def attempt: Task[Attempt[A]] =
    new Task(get map {
      case Left(e) => Right(Left(e))
      case Right(a) => Right(Right(a))
    })

  /**
   * Calls attempt and allows you to fold the `Attempt` up into a B 
   * by passing the `Throwable` to `f` and `A` to `g`.
   */
  def attemptFold[B](f: Throwable => B, g: A => B): Task[B] = 
    attempt.map(_.fold(f,g))

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function, calling Task.now on the result. Any nonmatching exceptions
   * are reraised.
   */
  def handle[B>:A](f: PartialFunction[Throwable,B]): Task[B] =
    handleWith(f andThen Task.now)

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function. Any nonmatching exceptions are reraised.
   */
  def handleWith[B>:A](f: PartialFunction[Throwable,Task[B]]): Task[B] =
    attempt flatMap {
      case Left(e) => f.lift(e) getOrElse Task.fail(e)
      case Right(a) => Task.now(a)
    }

  /**
   * Runs this `Task`, and if it fails with an exception, runs `t2`.
   * This is rather coarse-grained. Use `attempt`, `handle`, and
   * `flatMap` for more fine grained control of exception handling.
   */
  def or[B>:A](t2: Task[B]): Task[B] =
    new Task(this.get flatMap {
      case Left(e) => t2.get
      case a => Future.now(a)
    })

  /** Returns a task that performs all evaluation asynchronously. */
  def async(implicit S: Strategy): Task[A] = Task.start(this).flatMap(identity)

  /**
   * Run this computation to obtain either a result or an exception, then
   * invoke the given callback. Any pure, non-asynchronous computation at the
   * head of this `Future` will be forced in the calling thread. At the first
   * `Async` encountered, control is transferred to whatever thread backs the
   * `Async` and this function returns immediately.
   */
  def unsafeRunAsync(f: Attempt[A] => Unit): Unit =
    get.runAsync(f)

  /**
   * Run this computation and return the result as a standard library `Future`.
   * Like `unsafeRunAsync` but returns a standard library `Future` instead of requiring
   * a callback.
   */
  def unsafeRunAsyncFuture(): scala.concurrent.Future[A] = {
    val promise = scala.concurrent.Promise[A]
    unsafeRunAsync { e => e.fold(promise.failure, promise.success); () }
    promise.future
  }

  /**
   * Runs this `Task` up until an async boundary. If the task completes synchronously,
   * the result is returned wrapped in a `Right`. If an async boundary is encountered,
   * a continuation is returned wrapped in a `Left`.
   * To return exceptions in an `Either`, use `unsafeAttemptRunSync()`.
   */
  def unsafeRunSync(): Either[Callback[A] => Unit, A] = get.runSync.map { _ match {
    case Left(e) => throw e
    case Right(a) => a
  }}

  /** Like `unsafeRunSync`, but returns exceptions as values. */
  def unsafeAttemptRunSync(): Either[Callback[A] => Unit, Attempt[A]] = get.runSync

  /**
   * Runs this `Task` up until an async boundary. If the task completes synchronously,
   * the result is returned. If an async boundary is encountered, `None` is returned.
   * To return exceptions in an `Either`, use `unsafeAttemptValue()`.
   */
  def unsafeValue(): Option[A] = unsafeRunSync.toOption

  /** Like `unsafeValue`, but returns exceptions as values. */
  def unsafeAttemptValue(): Option[Attempt[A]] = get.runSync.toOption

    /**
   * A `Task` which returns a `TimeoutException` after `timeout`,
   * and attempts to cancel the running computation.
   *
   * This method is unsafe because upon reaching the specified timeout, the running
   * task is interrupted at a non-determinstic point in its execution.
   */
  def unsafeTimed(timeout: FiniteDuration)(implicit S: Strategy, scheduler: Scheduler): Task[A] =
    new Task(get.timed(timeout).map(_.flatMap(x => x)))

  override def toString = "Task"

  /**
    * Ensures the result of this Task satisfies the given predicate,
    * or fails with the given value.
   */
  def ensure(failure: => Throwable)(f: A => Boolean): Task[A] =
    flatMap(a => if(f(a)) Task.now(a) else Task.fail(failure))

  /**
    * Returns a `Task` that, when run, races evaluation of `this` and `t`,
    * and returns the result of whichever completes first. The losing task
    * continues to execute in the background though its result will be sent
    * nowhere.
   */
  def race[B](t: Task[B])(implicit S: Strategy): Task[Either[A,B]] = {
    Task.ref[Either[A,B]].flatMap { ref =>
      ref.setRace(this map (Left(_)), t map (Right(_)))
          .flatMap { _ => ref.get }
    }
  }

  /** Create a `Task` that will evaluate `a` after at least the given delay. */
  def schedule(delay: FiniteDuration)(implicit strategy: Strategy, scheduler: Scheduler): Task[A] =
    Task.schedule((), delay) flatMap { _ => this }
}

object Task extends TaskPlatform with TaskInstances {

  type Callback[-A] = Attempt[A] => Unit

  /** A `Task` which fails with the given `Throwable`. */
  def fail(e: Throwable): Task[Nothing] = new Task(Future.now(Left(e)))

  /** Convert a strict value to a `Task`. Also see `delay`. */
  def now[A](a: A): Task[A] = new Task(Future.now(Right(a)))

  /**
   * Promote a non-strict value to a `Task`, catching exceptions in
   * the process. Note that since `Task` is unmemoized, this will
   * recompute `a` each time it is sequenced into a larger computation.
   * Memoize `a` with a lazy value before calling this function if
   * memoization is desired.
   */
  def delay[A](a: => A): Task[A] = suspend(now(a))

  /**
   * Produce `f` in the main trampolining loop, `Future.step`, using a fresh
   * call stack. The standard trampolining primitive, useful for avoiding
   * stack overflows.
   */
  def suspend[A](a: => Task[A]): Task[A] = new Task(Future.suspend(
    Attempt(a.get) match {
      case Left(e) => Future.now(Left(e))
      case Right(f) => f
  }))

  /** Create a `Future` that will evaluate `a` using the given `Strategy`. */
  def apply[A](a: => A)(implicit S: Strategy): Task[A] =
    async { cb => S(cb(Attempt(a))) }

  /**
    * Given `t: Task[A]`, `start(t)` returns a `Task[Task[A]]`. After `flatMap`-ing
    * into the outer task, `t` will be running in the background, and the inner task
    * is conceptually a future which can be forced at any point via `flatMap`.

    * For example:

    * {{{
     for {
       f <- Task.start { expensiveTask1 }
       // at this point, `expensive1` is evaluating in background
       g <- Task.start { expensiveTask2 }
       // now both `expensiveTask2` and `expensiveTask1` are running
       result1 <- f
       // we have forced `f`, so now only `expensiveTask2` may be running
       result2 <- g
       // we have forced `g`, so now nothing is running and we have both results
     } yield (result1 + result2)
   }}}
  */
  def start[A](t: Task[A])(implicit S: Strategy): Task[Task[A]] =
    ref[A].flatMap { ref => ref.set(t) map (_ => ref.get) }

  /**
    * Like [[async]], but run the callback in the same thread in the same
    * thread, rather than evaluating the callback using a `Strategy`.
   */
  def unforkedAsync[A](register: (Attempt[A] => Unit) => Unit): Task[A] =
    async(register)(Strategy.sequential)

  /**
    * Create a `Task` from an asynchronous computation, which takes the form
    * of a function with which we can register a callback. This can be used
    * to translate from a callback-based API to a straightforward monadic
    * version. The callback is run using the strategy `S`.
   */
  // Note: `register` does not use the `Attempt` alias due to scalac inference limitation
  def async[A](register: (Either[Throwable,A] => Unit) => Unit)(implicit S: Strategy): Task[A] =
    new Task[A](Future.Async(cb => register {
      a => try { S { cb(a).run } } catch { case NonFatal(e) => cb(Left(e)).run }
    }))

  /**
   * Create a `Task` from a `scala.concurrent.Future`.
   */
  def fromFuture[A](fut: => scala.concurrent.Future[A])(implicit S: Strategy, E: scala.concurrent.ExecutionContext): Task[A] =
    async { cb => fut.onComplete {
      case scala.util.Success(a) => cb(Right(a))
      case scala.util.Failure(t) => cb(Left(t))
    }}

  /** Create a `Task` that will evaluate `a` after at least the given delay. */
  def schedule[A](a: => A, delay: FiniteDuration)(implicit S: Strategy, scheduler: Scheduler): Task[A] =
    async { cb => scheduler.delayedStrategy(delay)(cb(Attempt(a))) }

  def traverse[A,B](v: Seq[A])(f: A => Task[B]): Task[Vector[B]] =
    v.reverse.foldLeft(Task.now(Vector.empty[B])) {
      (tl,hd) => f(hd) flatMap { b => tl.map(b +: _) }
    }

  def parallelTraverse[A,B](s: Seq[A])(f: A => Task[B])(implicit S: Strategy): Task[Vector[B]] =
    traverse(s)(f andThen Task.start) flatMap { tasks => traverse(tasks)(identity) }

  private trait MsgId
  private trait Msg[A]
  private object Msg {
    final case class Read[A](cb: Callback[(A, Long)], id: MsgId) extends Msg[A]
    final case class Nevermind[A](id: MsgId, cb: Callback[Boolean]) extends Msg[A]
    final case class Set[A](r: Attempt[A]) extends Msg[A]
    final case class TrySet[A](id: Long, r: Attempt[A], cb: Callback[Boolean]) extends Msg[A]
  }

  def ref[A](implicit S: Strategy, F: Async[Task]): Task[Ref[A]] = Task.delay {
    var result: Attempt[A] = null
    // any waiting calls to `access` before first `set`
    var waiting: LinkedMap[MsgId, Callback[(A, Long)]] = LinkedMap.empty
    // id which increases with each `set` or successful `modify`
    var nonce: Long = 0

    lazy val actor: Actor[Msg[A]] = Actor.actor[Msg[A]] {
      case Msg.Read(cb, idf) =>
        if (result eq null) waiting = waiting.updated(idf, cb)
        else { val r = result; val id = nonce; S { cb(r.map((_,id))) } }

      case Msg.Set(r) =>
        if (result eq null) {
          nonce += 1L
          val id = nonce
          waiting.values.foreach(cb => S { cb(r.map((_,id))) })
          waiting = LinkedMap.empty
        }
        result = r

      case Msg.TrySet(id, r, cb) =>
        if (id == nonce) {
          nonce += 1L; val id2 = nonce
          waiting.values.foreach(cb => S { cb(r.map((_,id2))) })
          waiting = LinkedMap.empty
          result = r
          cb(Right(true))
        }
        else cb(Right(false))

      case Msg.Nevermind(id, cb) =>
        val interrupted = waiting.get(id).isDefined
        waiting = waiting - id
        S { cb (Right(interrupted)) }
    }

    new Ref(actor)(S, F)
  }

  class Ref[A] private[fs2](actor: Actor[Msg[A]])(implicit S: Strategy, protected val F: Async[Task]) extends Async.Ref[Task,A] {

    def access: Task[(A, Attempt[A] => Task[Boolean])] =
      Task.delay(new MsgId {}).flatMap { mid =>
        getStamped(mid).map { case (a, id) =>
          val set = (a: Attempt[A]) =>
            Task.unforkedAsync[Boolean] { cb => actor ! Msg.TrySet(id, a, cb) }
          (a, set)
        }
      }

    /**
     * Return a `Task` that submits `t` to this ref for evaluation.
     * When it completes it overwrites any previously `put` value.
     */
    def set(t: Task[A]): Task[Unit] =
      Task.delay { S { t.unsafeRunAsync { r => actor ! Msg.Set(r) } }}

    private def getStamped(msg: MsgId): Task[(A,Long)] =
      Task.unforkedAsync[(A,Long)] { cb => actor ! Msg.Read(cb, msg) }

    /** Return the most recently completed `set`, or block until a `set` value is available. */
    override def get: Task[A] = Task.delay(new MsgId {}).flatMap { mid => getStamped(mid).map(_._1) }

    /** Like `get`, but returns a `Task[Unit]` that can be used cancel the subscription. */
    def cancellableGet: Task[(Task[A], Task[Unit])] = Task.delay {
      val id = new MsgId {}
      val get = getStamped(id).map(_._1)
      val cancel = Task.unforkedAsync[Unit] {
        cb => actor ! Msg.Nevermind(id, r => cb(r.map(_ => ())))
      }
      (get, cancel)
    }

    /**
     * Runs `t1` and `t2` simultaneously, but only the winner gets to
     * `set` to this `ref`. The loser continues running but its reference
     * to this ref is severed, allowing this ref to be garbage collected
     * if it is no longer referenced by anyone other than the loser.
     */
    def setRace(t1: Task[A], t2: Task[A]): Task[Unit] = Task.delay {
      val ref = new AtomicReference(actor)
      val won = new AtomicBoolean(false)
      val win = (res: Attempt[A]) => {
        // important for GC: we don't reference this ref
        // or the actor directly, and the winner destroys any
        // references behind it!
        if (won.compareAndSet(false, true)) {
          val actor = ref.get
          ref.set(null)
          actor ! Msg.Set(res)
        }
      }
      t1.unsafeRunAsync(win)
      t2.unsafeRunAsync(win)
    }
  }
}

/* Prefer an `Async` but will settle for implicit `Effect`. */
private[fs2] trait TaskInstancesLowPriority {

  protected class EffectTask extends Effect[Task] {
    def pure[A](a: A) = Task.now(a)
    def flatMap[A,B](a: Task[A])(f: A => Task[B]): Task[B] = a flatMap f
    override def delay[A](a: => A) = Task.delay(a)
    def suspend[A](fa: => Task[A]) = Task.suspend(fa)
    def fail[A](err: Throwable) = Task.fail(err)
    def attempt[A](t: Task[A]) = t.attempt
    def unsafeRunAsync[A](t: Task[A])(cb: Attempt[A] => Unit): Unit = t.unsafeRunAsync(cb)
    override def toString = "Effect[Task]"
  }

  implicit val effectInstance: Effect[Task] = new EffectTask
}

private[fs2] trait TaskInstances extends TaskInstancesLowPriority {

  implicit def asyncInstance(implicit S:Strategy): Async[Task] = new EffectTask with Async[Task] {
    def ref[A]: Task[Async.Ref[Task,A]] = Task.ref[A](S, this)
    override def toString = "Async[Task]"
  }
}
